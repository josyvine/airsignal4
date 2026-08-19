package com.example.knowledge;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.example.audio.AudioEncoder;
import com.example.utils.AirLogger;
import com.example.utils.FileAssembler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class PhoneticImageTransceiver {

    private static final String TAG = "PhoneticImageTransceiver";
    public static final String PHONETIC_IMG_PREAMBLE = "PHON_IMG::";

    public interface OnPhoneticTransferListener {
        void onProgress(int step, int totalSteps, String statusMessage);
        void onSuccess(int totalTokensSent, int originalBase64Length);
        void onError(Exception e);
    }

    public static class PhoneticTransferEstimate {
        public int tokenCount;
        public int base64Length;
        public int payloadBytes;
        public int estimatedSeconds;
        public List<String> tokens;

        public PhoneticTransferEstimate(int tokenCount, int base64Length, int payloadBytes, int estimatedSeconds, List<String> tokens) {
            this.tokenCount = tokenCount;
            this.base64Length = base64Length;
            this.payloadBytes = payloadBytes;
            this.estimatedSeconds = estimatedSeconds;
            this.tokens = tokens;
        }
    }

    /**
     * Pre-calculates the NATO token count, payload size, and estimated duration for UI preview dialogs.
     */
    public static PhoneticTransferEstimate calculateTransferMetrics(File imageFile, int baudRate) {
        if (imageFile == null || !imageFile.exists()) return null;
        try (FileInputStream fis = new FileInputStream(imageFile)) {
            byte[] fileBytes = new byte[(int) imageFile.length()];
            int read = fis.read(fileBytes);
            if (read != fileBytes.length) return null;

            String rawBase64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
            List<String> phoneticTokens = PhoneticBase64Dictionary.encodeBase64ToPhoneticTokens(rawBase64);
            byte[] payload = formatTokensForTransmission(phoneticTokens);

            int baud = (baudRate > 0) ? baudRate : 1200;
            int audioSeconds = (int) Math.ceil((payload.length * 8.0) / (double) baud);
            int totalEstimatedSeconds = 5 + 1 + 5 + audioSeconds;

            return new PhoneticTransferEstimate(
                    phoneticTokens.size(),
                    rawBase64.length(),
                    payload.length,
                    totalEstimatedSeconds,
                    phoneticTokens
            );
        } catch (Exception e) {
            AirLogger.e(TAG, "Error calculating phonetic transfer metrics", e);
            return null;
        }
    }

    /**
     * SENDER: Converts an image file to Base64, applies Phonetic Dictionary block substitution,
     * and transmits the compressed phonetic token stream over audio.
     */
    public static void sendImageViaPhoneticDictionary(
            final Context context,
            final File imageFile,
            final AudioEncoder encoder,
            final OnPhoneticTransferListener listener) {

        if (imageFile == null || !imageFile.exists()) {
            if (listener != null) listener.onError(new IllegalArgumentException("Image file does not exist."));
            return;
        }

        if (encoder == null) {
            if (listener != null) listener.onError(new IllegalArgumentException("AudioEncoder is null."));
            return;
        }

        new Thread(() -> {
            try {
                if (listener != null) listener.onProgress(1, 4, "Reading image bytes from disk...");

                // 1. Read raw image bytes
                byte[] fileBytes = new byte[(int) imageFile.length()];
                try (FileInputStream fis = new FileInputStream(imageFile)) {
                    int read = fis.read(fileBytes);
                    if (read != fileBytes.length) {
                        throw new IllegalStateException("Incomplete image file read.");
                    }
                }

                if (listener != null) listener.onProgress(2, 4, "Encoding to Base64 stream...");

                // 2. Convert to Base64
                String rawBase64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
                int originalLength = rawBase64.length();

                if (listener != null) listener.onProgress(3, 4, "Applying Phonetic Dictionary substitution...");

                // 3. Substitute blocks with pre-built dictionary words (ALPHA, BRAVO, CHARLIE...)
                List<String> phoneticTokens = PhoneticBase64Dictionary.encodeBase64ToPhoneticTokens(rawBase64);

                // 4. Format into transmission payload with sync preamble and closure
                byte[] transmissionPayload = formatTokensForTransmission(phoneticTokens);

                if (listener != null) listener.onProgress(4, 4, "Modulating audio stream...");

                AirLogger.i(TAG, "Transmitting image. Original Base64 chars: " + originalLength +
                        ", Dictionary tokens: " + phoneticTokens.size() + ", Payload size: " + transmissionPayload.length + " bytes.");

                // 5. Transmit audio tones
                encoder.transmitDataOverAudio(transmissionPayload, new AudioEncoder.OnTransmissionProgressListener() {
                    @Override
                    public void onProgress(int currentPacket, int totalPackets, int percent) {
                        if (listener != null) {
                            listener.onProgress(4, 4, "Transmitting: " + percent + "%");
                        }
                    }

                    @Override
                    public void onComplete() {
                        if (listener != null) {
                            listener.onSuccess(phoneticTokens.size(), originalLength);
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        if (listener != null) listener.onError(e);
                    }
                });

            } catch (Exception e) {
                AirLogger.e(TAG, "Failed sending image via phonetic dictionary", e);
                if (listener != null) listener.onError(e);
            }
        }).start();
    }

    /**
     * RECEIVER: Accepts incoming phonetic tokens, expands every word back into its full Base64 block,
     * decodes the exact original binary image, saves it directly to public Downloads/AirSignal_Transfers,
     * registers it with MediaScanner, and triggers the UI popup.
     */
    public static void receiveAndReconstructImage(
            final Context context,
            final List<String> receivedTokens,
            final String outputFileName) {

        if (context == null || receivedTokens == null || receivedTokens.isEmpty()) {
            return;
        }

        new Thread(() -> {
            try {
                AirLogger.i(TAG, "Reconstructing image from " + receivedTokens.size() + " phonetic tokens.");

                // 1. Expand phonetic tokens back into the complete Base64 string
                String reconstructedBase64 = PhoneticBase64Dictionary.decodePhoneticTokensToBase64(receivedTokens);

                if (reconstructedBase64.isEmpty()) {
                    AirLogger.w(TAG, "Base64 expansion resulted in empty string.");
                    return;
                }

                // 2. Decode Base64 back into the exact original binary bytes
                byte[] exactImageBytes = Base64.decode(reconstructedBase64, Base64.NO_WRAP);

                // 3. Save directly to public Downloads/AirSignal_Transfers/ folder
                File outputDir = FileAssembler.getReceivedFilesDir(context);

                String finalName = (outputFileName != null && !outputFileName.isEmpty())
                        ? outputFileName
                        : "photo_rx_" + System.currentTimeMillis() + ".webp";

                File outputFile = new File(outputDir, finalName);
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    fos.write(exactImageBytes);
                    fos.flush();
                }

                // 4. Register with Android MediaScanner so it appears instantly in File Manager and Gallery
                MediaScannerConnection.scanFile(
                        context.getApplicationContext(),
                        new String[]{outputFile.getAbsolutePath()},
                        new String[]{"image/webp"},
                        (path, uri) -> AirLogger.i(TAG, "MediaScanner indexed reconstructed image: " + path)
                );

                AirLogger.i(TAG, "Exact image successfully restored to storage: " + outputFile.getAbsolutePath() +
                        " (" + exactImageBytes.length + " bytes)");

                // 5. Zero-Touch UI Display: Auto-pop up the exact picture on the receiver's screen
                new Handler(Looper.getMainLooper()).post(() -> {
                    VisualRenderer.showLosslessImageDialog(context, exactImageBytes, finalName);
                });

            } catch (Exception e) {
                AirLogger.e(TAG, "Failed reconstructing image from phonetic tokens", e);
            }
        }).start();
    }

    /**
     * Serializes a list of phonetic tokens into a delimited payload with preamble and trailing closure '#'.
     */
    public static byte[] formatTokensForTransmission(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        sb.append(PHONETIC_IMG_PREAMBLE);
        for (int i = 0; i < tokens.size(); i++) {
            sb.append(tokens.get(i));
            if (i < tokens.size() - 1) {
                sb.append("|");
            }
        }
        sb.append("#"); // Trailing closure delimiter to announce stream completion
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parses an incoming demodulated audio byte array back into a list of phonetic tokens.
     */
    public static List<String> parseTransmissionToTokens(byte[] rawPayload) {
        if (rawPayload == null || rawPayload.length == 0) {
            return new ArrayList<>();
        }

        String payloadStr = new String(rawPayload, StandardCharsets.UTF_8);
        int preambleIndex = payloadStr.indexOf(PHONETIC_IMG_PREAMBLE);
        if (preambleIndex == -1) {
            return new ArrayList<>();
        }

        String data = payloadStr.substring(preambleIndex + PHONETIC_IMG_PREAMBLE.length());
        
        // Strip trailing metadata or noise delimiters if attached
        if (data.contains("#")) {
            data = data.substring(0, data.indexOf('#'));
        }

        String[] splitTokens = data.split("\\|");
        List<String> list = new ArrayList<>();
        for (String token : splitTokens) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }
}