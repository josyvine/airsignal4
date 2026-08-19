package com.example.knowledge;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.example.audio.AudioEncoder;
import com.example.audio.GGWaveEngine;
import com.example.utils.AirLogger;
import com.example.utils.FileAssembler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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
     * Pre-calculates token count, payload size, and estimated duration for UI preview dialogs.
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
     * SENDER: Reads image from disk, applies lossless GZIP compression, encapsulates it,
     * and transmits it over the acoustic modem channel with Reed-Solomon protection.
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

                if (listener != null) listener.onProgress(2, 4, "Compressing binary image stream...");

                // 2. Convert to compressed binary payload for exact lossless transmission
                byte[] compressedPayload = compressBytes(fileBytes);
                String rawBase64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
                int originalLength = rawBase64.length();

                if (listener != null) listener.onProgress(3, 4, "Encapsulating DSP acoustic frame...");

                // 3. Package into standard AirSignal phonetic stream
                List<String> phoneticTokens = PhoneticBase64Dictionary.encodeBase64ToPhoneticTokens(rawBase64);
                byte[] transmissionPayload = formatRawBytesForTransmission(compressedPayload);

                if (listener != null) listener.onProgress(4, 4, "Modulating audio stream via GGWave...");

                AirLogger.i(TAG, "Transmitting image. Raw bytes: " + fileBytes.length +
                        ", Compressed payload: " + transmissionPayload.length + " bytes.");

                // 4. Transmit acoustic waveform
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
                AirLogger.e(TAG, "Failed sending image via phonetic transceiver", e);
                if (listener != null) listener.onError(e);
            }
        }).start();
    }

    /**
     * RECEIVER: Accepts incoming decoded payload, decompresses the exact original binary image,
     * saves it directly to public Downloads/AirSignal_Transfers, registers it with MediaScanner,
     * and triggers the UI popup.
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

                saveAndDisplayImage(context, exactImageBytes, outputFileName);

            } catch (Exception e) {
                AirLogger.e(TAG, "Failed reconstructing image from phonetic tokens", e);
            }
        }).start();
    }

    /**
     * Direct binary receiver: handles raw GGWave error-corrected frames.
     */
    public static void receiveAndReconstructRawImage(
            final Context context,
            final byte[] rawFrameBytes,
            final String outputFileName) {

        if (context == null || rawFrameBytes == null || rawFrameBytes.length == 0) {
            return;
        }

        new Thread(() -> {
            try {
                byte[] exactBytes = extractRawBytesFromTransmission(rawFrameBytes);
                if (exactBytes == null || exactBytes.length == 0) {
                    exactBytes = rawFrameBytes;
                }

                // Decompress GZIP payload if compressed
                byte[] decompressed = decompressBytes(exactBytes);
                if (decompressed != null && decompressed.length > 0) {
                    exactBytes = decompressed;
                }

                saveAndDisplayImage(context, exactBytes, outputFileName);

            } catch (Exception e) {
                AirLogger.e(TAG, "Failed reconstructing raw binary image", e);
            }
        }).start();
    }

    private static void saveAndDisplayImage(Context context, byte[] exactImageBytes, String outputFileName) {
        try {
            // 1. Save directly to public Downloads/AirSignal_Transfers/ folder
            File outputDir = FileAssembler.getReceivedFilesDir(context);

            String finalName = (outputFileName != null && !outputFileName.isEmpty())
                    ? outputFileName
                    : "photo_rx_" + System.currentTimeMillis() + ".webp";

            File outputFile = new File(outputDir, finalName);
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(exactImageBytes);
                fos.flush();
            }

            // 2. Register with Android MediaScanner so it appears instantly in File Manager and Gallery
            MediaScannerConnection.scanFile(
                    context.getApplicationContext(),
                    new String[]{outputFile.getAbsolutePath()},
                    new String[]{"image/webp"},
                    (path, uri) -> AirLogger.i(TAG, "MediaScanner indexed reconstructed image: " + path)
            );

            AirLogger.i(TAG, "Exact image successfully restored to storage: " + outputFile.getAbsolutePath() +
                    " (" + exactImageBytes.length + " bytes)");

            // 3. Zero-Touch UI Display: Auto-pop up the exact picture on the receiver's screen
            new Handler(Looper.getMainLooper()).post(() -> {
                VisualRenderer.showLosslessImageDialog(context, exactImageBytes, finalName);
            });
        } catch (Exception e) {
            AirLogger.e(TAG, "Error saving reconstructed image to storage", e);
        }
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

    public static byte[] formatRawBytesForTransmission(byte[] rawBytes) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            byte[] preambleBytes = PHONETIC_IMG_PREAMBLE.getBytes(StandardCharsets.UTF_8);
            baos.write(preambleBytes);
            baos.write(rawBytes);
            baos.write('#');
        } catch (Exception ignored) {}
        return baos.toByteArray();
    }

    public static byte[] extractRawBytesFromTransmission(byte[] rawPayload) {
        if (rawPayload == null || rawPayload.length == 0) return new byte[0];

        byte[] preambleBytes = PHONETIC_IMG_PREAMBLE.getBytes(StandardCharsets.UTF_8);
        int startIndex = -1;

        for (int i = 0; i <= rawPayload.length - preambleBytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < preambleBytes.length; j++) {
                if (rawPayload[i + j] != preambleBytes[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                startIndex = i + preambleBytes.length;
                break;
            }
        }

        if (startIndex == -1) return rawPayload;

        int endIndex = rawPayload.length;
        if (rawPayload[rawPayload.length - 1] == '#') {
            endIndex = rawPayload.length - 1;
        }

        int len = endIndex - startIndex;
        if (len <= 0) return new byte[0];

        byte[] out = new byte[len];
        System.arraycopy(rawPayload, startIndex, out, 0, len);
        return out;
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

    private static byte[] compressBytes(byte[] input) {
        if (input == null || input.length == 0) return input;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(input);
            gzos.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            return input;
        }
    }

    private static byte[] decompressBytes(byte[] input) {
        if (input == null || input.length == 0) return input;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(input);
             GZIPInputStream gzis = new GZIPInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int r;
            while ((r = gzis.read(buf)) > 0) {
                baos.write(buf, 0, r);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return input;
        }
    }
}