package com.example.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.example.knowledge.PhoneticImageTransceiver;
import com.example.models.TemplateToken;
import com.example.utils.AirLogger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioReceiver {

    private static final String TAG = "AudioReceiver";

    public static final int DEFAULT_SAMPLE_RATE = 48000;
    public static final int FALLBACK_SAMPLE_RATE = 44100;
    public static final byte SYNC_PREAMBLE = (byte) 0xAA;
    public static final byte START_FRAME_DELIMITER = (byte) 0x7E;

    public static final int MAX_STREAM_BUFFER_SIZE = 32768; // 32 KB maximum image/file buffer

    // Standardized handshake command strings
    public static final String CMD_ACTIVATE_RECEIVER = "AIR_CMD:ACTIVATE_RECEIVER";
    public static final String CMD_RECEIVER_READY = "AIR_ACK:RECEIVER_READY";

    private int baudRate = 1200; // 300, 600, 1200, 2400
    private int activeSampleRate = DEFAULT_SAMPLE_RATE;
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private AudioRecord audioRecord;
    private AudioReceiverListener listener;

    public interface AudioReceiverListener {
        void onByteDecoded(byte b);
        void onFrameDecoded(byte[] frameData);
        void onTokenDecoded(TemplateToken token);
        void onReceiverActivationCommand();
        void onReceiverReadyAckReceived();
        void onError(Exception e);
    }

    // Legacy listener interface for backward compatibility
    public interface AudioDecoderListener {
        void onByteDecoded(byte b);
    }

    public AudioReceiver(AudioDecoderListener legacyListener) {
        this.listener = new AudioReceiverListener() {
            @Override
            public void onByteDecoded(byte b) {
                if (legacyListener != null) legacyListener.onByteDecoded(b);
            }

            @Override
            public void onFrameDecoded(byte[] frameData) {}

            @Override
            public void onTokenDecoded(TemplateToken token) {}

            @Override
            public void onReceiverActivationCommand() {}

            @Override
            public void onReceiverReadyAckReceived() {}

            @Override
            public void onError(Exception e) {}
        };
    }

    public AudioReceiver(AudioReceiverListener listener) {
        this.listener = listener;
    }

    public void setBaudRate(int baudRate) {
        if (baudRate > 0) {
            this.baudRate = baudRate;
        }
    }

    public int getBaudRate() {
        return baudRate;
    }

    public int getActiveSampleRate() {
        return activeSampleRate;
    }

    public boolean isListening() {
        return isListening.get();
    }

    @SuppressLint("MissingPermission")
    public void startListening() {
        if (isListening.get()) return;

        // Hardware Compatibility Probe Matrix (Supports Huawei EMUI, ColorOS, and Standard Android)
        int[] sampleRates = new int[]{48000, 44100, 16000, 8000};
        int[] audioSources = new int[]{
                MediaRecorder.AudioSource.VOICE_RECOGNITION, // Unlocked on Huawei EMUI during calls
                9,                                           // AudioSource.UNPROCESSED (Direct hardware ADC)
                MediaRecorder.AudioSource.CAMCORDER,         // Secondary ambient mic
                MediaRecorder.AudioSource.MIC,               // Standard mic (Oppo / Samsung)
                MediaRecorder.AudioSource.DEFAULT
        };

        boolean initialized = false;

        for (int source : audioSources) {
            for (int rate : sampleRates) {
                try {
                    int minBufferSize = AudioRecord.getMinBufferSize(
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                    );

                    if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                        continue;
                    }

                    int bufferSize = Math.max(minBufferSize * 4, 8192);

                    audioRecord = new AudioRecord(
                            source,
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize
                    );

                    if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                        activeSampleRate = rate;
                        initialized = true;
                        AirLogger.i(TAG, "AudioRecord successfully initialized with Source=" + sourceToString(source) +
                                ", SampleRate=" + rate + " Hz, Baud=" + baudRate);
                        break;
                    } else {
                        audioRecord.release();
                        audioRecord = null;
                    }
                } catch (Exception e) {
                    if (audioRecord != null) {
                        try {
                            audioRecord.release();
                        } catch (Exception ignored) {}
                        audioRecord = null;
                    }
                }
            }
            if (initialized) break;
        }

        if (!initialized || audioRecord == null) {
            AirLogger.e(TAG, "AudioRecord failed to initialize across all hardware probe configurations.");
            if (listener != null) {
                listener.onError(new IllegalStateException("Microphone hardware probe failed across all sample rates."));
            }
            return;
        }

        try {
            isListening.set(true);
            audioRecord.startRecording();
            AirLogger.i(TAG, "AudioReceiver recording started actively.");
            new Thread(this::listenLoop).start();
        } catch (Exception e) {
            AirLogger.e(TAG, "Failed starting AudioRecord stream", e);
            if (listener != null) listener.onError(e);
            stopListening();
        }
    }

    private double calculateRmsEnergy(short[] buffer, int readSize) {
        double sum = 0;
        for (int i = 0; i < readSize; i++) {
            sum += buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / readSize);
    }

    private void listenLoop() {
        // Initialize the native DSP engine for the active hardware sampling rate
        GGWaveEngine ggwaveEngine = GGWaveEngine.getInstance();
        boolean ggwaveReady = ggwaveEngine.init(activeSampleRate);

        int pcmFrameSize = 1024;
        short[] pcmBuffer = new short[pcmFrameSize];

        double samplesPerBit = (double) activeSampleRate / (double) baudRate;
        int bitSampleLen = Math.max((int) Math.round(samplesPerBit), 1);
        short[] bitBuffer = new short[bitSampleLen];

        int currentByteAccumulator = 0;
        int bitCount = 0;
        int consecutiveSilenceCount = 0;
        int consecutiveZeroEnergyCount = 0;

        // Frame Detection State Machine
        boolean isLockedOnPreamble = false;
        boolean isAccumulatingImage = false;
        ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream();

        // Rolling sliding window for cellular carrier and local air-gap discovery
        StringBuilder slidingWindow = new StringBuilder();

        while (isListening.get()) {
            if (audioRecord == null || audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                break;
            }

            int read = audioRecord.read(pcmBuffer, 0, pcmBuffer.length);
            if (read > 0) {
                // 1. Primary DSP Path: Feed raw PCM audio frames directly to GGWave native demodulator
                if (ggwaveReady) {
                    byte[] decodedPayload = ggwaveEngine.decode(pcmBuffer, read);
                    if (decodedPayload != null && decodedPayload.length > 0) {
                        AirLogger.i(TAG, "GGWave native decoder received valid error-corrected frame (" + decodedPayload.length + " bytes)");
                        handleDecodedPayload(decodedPayload);
                    }
                }

                // Diagnostic check to verify non-zero PCM energy
                double currentRms = calculateRmsEnergy(pcmBuffer, read);
                if (currentRms == 0.0) {
                    consecutiveZeroEnergyCount++;
                    if (consecutiveZeroEnergyCount % 100 == 1) {
                        AirLogger.w(TAG, "DIAGNOSTIC WARNING: Zero-energy PCM buffer detected (" 
                                + consecutiveZeroEnergyCount + " consecutive frames). Operating system call privacy filters may be silencing the microphone input.");
                    }
                } else {
                    consecutiveZeroEnergyCount = 0;
                }

                // 2. Secondary/Fallback DSP Path: Continuous bit detection loop for FSK/Handshakes
                int bitVal = AudioDecoder.detectBit(pcmBuffer, 0, Math.min(read, bitSampleLen), activeSampleRate);

                if (bitVal == -1) {
                    consecutiveSilenceCount++;

                    // If an image stream was accumulated and silence interval is reached, deliver the complete stream
                    if (isAccumulatingImage && frameBuffer.size() > 50 && consecutiveSilenceCount > 30) {
                        byte[] fullStreamBytes = frameBuffer.toByteArray();
                        AirLogger.i(TAG, "End of audio transmission detected via silence interval. Delivering full Phonetic Image (" + fullStreamBytes.length + " bytes).");
                        if (listener != null) {
                            listener.onFrameDecoded(fullStreamBytes);
                        }
                        isLockedOnPreamble = false;
                        isAccumulatingImage = false;
                        consecutiveSilenceCount = 0;
                        frameBuffer.reset();
                        slidingWindow.setLength(0);
                    }
                    continue;
                }

                consecutiveSilenceCount = 0;
                currentByteAccumulator = (currentByteAccumulator << 1) | (bitVal & 1);
                bitCount++;

                if (bitCount == 8) {
                    byte completedByte = (byte) (currentByteAccumulator & 0xFF);
                    currentByteAccumulator = 0;
                    bitCount = 0;

                    if (listener != null) {
                        listener.onByteDecoded(completedByte);
                    }

                    char c = (char) (completedByte & 0xFF);
                    if (slidingWindow.length() > 64) {
                        slidingWindow.deleteCharAt(0);
                    }
                    slidingWindow.append(c);
                    String currentWindowStr = slidingWindow.toString();

                    // 1. Check for remote RECEIVER_READY ACK (Sender side)
                    if (!isAccumulatingImage && currentWindowStr.contains(CMD_RECEIVER_READY)) {
                        AirLogger.i(TAG, "Acoustic AIR_ACK:RECEIVER_READY detected! Remote receiver answered and listening.");
                        if (listener != null) {
                            listener.onReceiverReadyAckReceived();
                        }
                        slidingWindow.setLength(0);
                        isLockedOnPreamble = false;
                        frameBuffer.reset();
                        continue;
                    }

                    // 2. Check for remote ACTIVATE_RECEIVER acoustic handshake command (Receiver side)
                    if (!isAccumulatingImage && currentWindowStr.contains(CMD_ACTIVATE_RECEIVER)) {
                        AirLogger.i(TAG, "Remote ACTIVATE_RECEIVER command detected!");
                        if (listener != null) {
                            listener.onReceiverActivationCommand();
                        }
                        slidingWindow.setLength(0);
                        isLockedOnPreamble = false;
                        frameBuffer.reset();
                        continue;
                    }

                    // 3. Direct Sliding Lock for Phonetic Image Stream
                    if (!isAccumulatingImage && currentWindowStr.contains(PhoneticImageTransceiver.PHONETIC_IMG_PREAMBLE)) {
                        AirLogger.i(TAG, "Phonetic Image preamble detected via sliding window! Locking stream.");
                        isLockedOnPreamble = true;
                        isAccumulatingImage = true;
                        frameBuffer.reset();
                        byte[] preambleBytes = PhoneticImageTransceiver.PHONETIC_IMG_PREAMBLE.getBytes(StandardCharsets.UTF_8);
                        frameBuffer.write(preambleBytes, 0, preambleBytes.length);
                        slidingWindow.setLength(0);
                        continue;
                    }

                    // 4. Standard 0x7E delimiter lock fallback
                    if (!isLockedOnPreamble) {
                        if (completedByte == START_FRAME_DELIMITER) {
                            isLockedOnPreamble = true;
                            isAccumulatingImage = false;
                            frameBuffer.reset();
                        }
                    } else {
                        frameBuffer.write(completedByte);

                        byte[] currentBufferBytes = frameBuffer.toByteArray();

                        // Mode 4 Check: If 16 bytes accumulated, attempt TemplateToken validation
                        if (!isAccumulatingImage && frameBuffer.size() == TemplateToken.TOKEN_BYTE_SIZE) {
                            TemplateToken token = TemplateToken.fromByteArray(currentBufferBytes);
                            if (token != null && token.isValid()) {
                                AirLogger.i(TAG, "Mode 4 Token detected automatically! ID=" + token.getTemplateId());
                                if (listener != null) {
                                    listener.onTokenDecoded(token);
                                }
                                isLockedOnPreamble = false;
                                frameBuffer.reset();
                                continue;
                            }
                        }

                        // Accumulate Phonetic Base64 Image
                        if (isAccumulatingImage) {
                            String preview = new String(currentBufferBytes, StandardCharsets.UTF_8);
                            int firstHash = preview.indexOf('#');
                            int lastHash = preview.lastIndexOf('#');
                            if (firstHash != -1 && lastHash > firstHash && (preview.endsWith("#") || countOccurrences(preview, '#') >= 2)) {
                                AirLogger.i(TAG, "Complete Phonetic Image stream accumulated (" + currentBufferBytes.length + " bytes). Delivering.");
                                if (listener != null) {
                                    listener.onFrameDecoded(currentBufferBytes);
                                }
                                isLockedOnPreamble = false;
                                isAccumulatingImage = false;
                                frameBuffer.reset();
                                slidingWindow.setLength(0);
                                continue;
                            }
                        }

                        // Mode 2/3 Raw Binary Packet Frame flush
                        if (!isAccumulatingImage && frameBuffer.size() == 263 && currentBufferBytes[0] == 0x53) {
                            if (listener != null) {
                                listener.onFrameDecoded(currentBufferBytes);
                            }
                            isLockedOnPreamble = false;
                            frameBuffer.reset();
                        } else if (!isAccumulatingImage && frameBuffer.size() >= MAX_STREAM_BUFFER_SIZE) {
                            if (listener != null) {
                                listener.onFrameDecoded(currentBufferBytes);
                            }
                            isLockedOnPreamble = false;
                            frameBuffer.reset();
                        }
                    }
                }
            }
        }

        // Flush remaining frame if stream ended
        if (frameBuffer.size() > 0 && listener != null) {
            listener.onFrameDecoded(frameBuffer.toByteArray());
        }
    }

    private void handleDecodedPayload(byte[] payload) {
        if (payload == null || payload.length == 0) return;

        String asText = new String(payload, StandardCharsets.UTF_8);

        if (asText.contains(CMD_RECEIVER_READY)) {
            AirLogger.i(TAG, "GGWave decoded AIR_ACK:RECEIVER_READY");
            if (listener != null) listener.onReceiverReadyAckReceived();
            return;
        }

        if (asText.contains(CMD_ACTIVATE_RECEIVER)) {
            AirLogger.i(TAG, "GGWave decoded AIR_CMD:ACTIVATE_RECEIVER");
            if (listener != null) listener.onReceiverActivationCommand();
            return;
        }

        if (payload.length == TemplateToken.TOKEN_BYTE_SIZE) {
            TemplateToken token = TemplateToken.fromByteArray(payload);
            if (token != null && token.isValid()) {
                AirLogger.i(TAG, "GGWave decoded TemplateToken! ID=" + token.getTemplateId());
                if (listener != null) {
                    listener.onTokenDecoded(token);
                }
                return;
            }
        }

        if (listener != null) {
            listener.onFrameDecoded(payload);
        }
    }

    private int countOccurrences(String str, char ch) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == ch) count++;
        }
        return count;
    }

    public void stopListening() {
        isListening.set(false);
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                AirLogger.e(TAG, "Error releasing AudioRecord", e);
            } finally {
                audioRecord = null;
            }
        }
        AirLogger.i(TAG, "AudioReceiver stopped listening");
    }

    private String sourceToString(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.MIC: return "MIC";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION: return "VOICE_COMMUNICATION";
            case MediaRecorder.AudioSource.CAMCORDER: return "CAMCORDER";
            case 9: return "UNPROCESSED";
            case MediaRecorder.AudioSource.DEFAULT: return "DEFAULT";
            default: return "SOURCE_" + source;
        }
    }
}