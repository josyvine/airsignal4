package com.example.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.telecom.Call;

import com.example.knowledge.PhoneticImageTransceiver;
import com.example.models.TemplateToken;
import com.example.services.AirSignalInCallService;
import com.example.utils.AirLogger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioEncoder {

    private static final String TAG = "AudioEncoder";

    public static final int SAMPLE_RATE = 48000;
    public static final int FALLBACK_SAMPLE_RATE = 44100;
    public static final int MARK_FREQ = 1200;   // Bit 1 (Hz)
    public static final int SPACE_FREQ = 2200;  // Bit 0 (Hz)

    public static final byte SYNC_PREAMBLE = (byte) 0xAA;
    public static final byte START_FRAME_DELIMITER = (byte) 0x7E;

    // Standardized handshake command strings for automated two-way connection synchronization
    public static final String CMD_ACTIVATE_RECEIVER = "AIR_CMD:ACTIVATE_RECEIVER";
    public static final String CMD_RECEIVER_READY = "AIR_ACK:RECEIVER_READY";

    private int baudRate = 1200; // 300, 600, 1200, 2400
    private final AtomicBoolean isTransmitting = new AtomicBoolean(false);
    private AudioTrack activeAudioTrack;

    public interface OnTransmissionProgressListener {
        void onProgress(int currentPacket, int totalPackets, int percent);
        void onComplete();
        void onError(Exception e);
    }

    public AudioEncoder(int baudRate) {
        setBaudRate(baudRate);
    }

    public void setBaudRate(int baudRate) {
        if (baudRate <= 0) {
            this.baudRate = 1200;
        } else {
            this.baudRate = baudRate;
        }
    }

    public int getBaudRate() {
        return baudRate;
    }

    public boolean isTransmitting() {
        return isTransmitting.get();
    }

    public void cancelTransmission() {
        isTransmitting.set(false);
        if (activeAudioTrack != null) {
            try {
                activeAudioTrack.pause();
                activeAudioTrack.flush();
                activeAudioTrack.stop();
                activeAudioTrack.release();
            } catch (Exception ignored) {
            } finally {
                activeAudioTrack = null;
            }
        }
    }

    /**
     * Receiver-side Automation: Transmits the acoustic handshake acknowledgment to announce call answer.
     */
    public void transmitReceiverReadyAck(final OnTransmissionProgressListener listener) {
        AirLogger.i(TAG, "Transmitting receiver ready acoustic acknowledgment (AIR_ACK:RECEIVER_READY)...");
        byte[] ackBytes = CMD_RECEIVER_READY.getBytes(StandardCharsets.UTF_8);
        transmitDataOverAudio(ackBytes, listener);
    }

    /**
     * Transmitter-side Command: Transmits the ACTIVATE_RECEIVER acoustic handshake command over the call.
     */
    public void transmitActivationCommand(final OnTransmissionProgressListener listener) {
        AirLogger.i(TAG, "Transmitting remote ACTIVATE_RECEIVER acoustic command...");
        byte[] commandBytes = CMD_ACTIVATE_RECEIVER.getBytes(StandardCharsets.UTF_8);
        transmitDataOverAudio(commandBytes, listener);
    }

    /**
     * Transmits a single raw byte payload asynchronously.
     */
    public void transmitDataOverAudio(byte[] data) {
        transmitDataOverAudio(data, null);
    }

    /**
     * Core synchronous execution for encoding and playing acoustic data.
     * This blocks the calling thread until the audio finishes playing.
     */
    private void executeTransmissionSync(byte[] data, OnTransmissionProgressListener listener) throws Exception {
        AirLogger.i(TAG, "Generating GGWave DSP acoustic waveform (" + data.length + " bytes)...");

        GGWaveEngine engine = GGWaveEngine.getInstance();
        boolean initialized = engine.init(SAMPLE_RATE);
        short[] pcmSamples = null;

        if (initialized) {
            // PROTOCOL_AUDIBLE_NORMAL is specifically designed to survive active cellular voice calls
            pcmSamples = engine.encode(data, GGWaveEngine.PROTOCOL_AUDIBLE_NORMAL, 80);
        }

        // Fallback to internal continuous-phase FSK if native engine is unavailable
        if (pcmSamples == null || pcmSamples.length == 0) {
            AirLogger.w(TAG, "GGWave encoding returned empty. Falling back to internal continuous-phase FSK synthesizer.");
            byte[] framedData = applyFrameEncapsulation(data);
            pcmSamples = generateContinuousPhaseFsk(framedData, baudRate);
            playPcmTrack(pcmSamples, FALLBACK_SAMPLE_RATE);
        } else {
            playPcmTrack(pcmSamples, SAMPLE_RATE);
        }

        if (listener != null) {
            listener.onProgress(1, 1, 100);
            listener.onComplete();
        }
    }

    /**
     * Transmits a single payload over the speaker asynchronously.
     */
    public void transmitDataOverAudio(final byte[] data, final OnTransmissionProgressListener listener) {
        if (data == null || data.length == 0) {
            if (listener != null) listener.onError(new IllegalArgumentException("Data payload is empty"));
            return;
        }

        new Thread(() -> {
            isTransmitting.set(true);
            try {
                executeTransmissionSync(data, listener);
            } catch (Exception e) {
                AirLogger.e(TAG, "Transmission failed", e);
                if (listener != null) listener.onError(e);
            } finally {
                isTransmitting.set(false);
            }
        }).start();
    }

    /**
     * Mode 4: Transmits a 16-byte TemplateToken as an ultra-fast sub-second audio burst.
     */
    public void transmitPhoneticToken(final TemplateToken token, final OnTransmissionProgressListener listener) {
        if (token == null) {
            if (listener != null) listener.onError(new IllegalArgumentException("TemplateToken is null"));
            return;
        }
        byte[] tokenBytes = token.toByteArray();
        transmitDataOverAudio(tokenBytes, listener);
    }

    /**
     * Dedicated Feature: Transmits a list of Phonetic Base64 Dictionary words as a structured sequence.
     */
    public void transmitPhoneticBase64Sequence(final List<String> phoneticWords, final OnTransmissionProgressListener listener) {
        if (phoneticWords == null || phoneticWords.isEmpty()) {
            if (listener != null) listener.onError(new IllegalArgumentException("Phonetic words list is empty"));
            return;
        }
        byte[] payload = PhoneticImageTransceiver.formatTokensForTransmission(phoneticWords);
        transmitDataOverAudio(payload, listener);
    }

    /**
     * Mode 2: Streams a multi-packet raw binary dataset incrementally.
     * Uses a single background thread to process chunks sequentially, preventing thread explosions.
     */
    public void transmitRawStream(final List<byte[]> packets, final OnTransmissionProgressListener listener) {
        if (packets == null || packets.isEmpty()) {
            if (listener != null) listener.onError(new IllegalArgumentException("Packet list is empty"));
            return;
        }

        new Thread(() -> {
            isTransmitting.set(true);
            try {
                int totalPackets = packets.size();

                for (int i = 0; i < totalPackets; i++) {
                    if (!isTransmitting.get()) break;

                    byte[] packetData = packets.get(i);
                    
                    // Execute synchronously so we don't spawn 100+ threads simultaneously
                    executeTransmissionSync(packetData, null);

                    int progressPercent = (int) (((i + 1) / (float) totalPackets) * 100);
                    if (listener != null) {
                        listener.onProgress(i + 1, totalPackets, progressPercent);
                    }

                    // Add a brief acoustic silence gap between chunks to allow the receiver's DSP to finalize the frame
                    Thread.sleep(200); 
                }

                if (isTransmitting.get() && listener != null) {
                    listener.onComplete();
                }
            } catch (Exception e) {
                AirLogger.e(TAG, "Stream transmission error", e);
                if (listener != null) listener.onError(e);
            } finally {
                cancelTransmission();
            }
        }).start();
    }

    /**
     * Prepends sync bytes (0xAA 0xAA 0xAA 0x7E) for receiver preamble detection.
     */
    private byte[] applyFrameEncapsulation(byte[] payload) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(SYNC_PREAMBLE);
        baos.write(SYNC_PREAMBLE);
        baos.write(SYNC_PREAMBLE);
        baos.write(START_FRAME_DELIMITER);

        if (payload != null) {
            baos.write(payload, 0, payload.length);
        }
        return baos.toByteArray();
    }

    /**
     * Sample-accurate, continuous-phase FSK modulator.
     */
    public static short[] generateContinuousPhaseFsk(byte[] data, int baud) {
        if (data == null || data.length == 0) return new short[0];

        double samplesPerBit = (double) FALLBACK_SAMPLE_RATE / (double) baud;
        int totalBits = data.length * 8;
        int totalSamples = (int) Math.round(totalBits * samplesPerBit);

        short[] output = new short[totalSamples];
        double currentPhase = 0.0;
        int sampleIndex = 0;

        for (byte b : data) {
            for (int bit = 7; bit >= 0; bit--) {
                int bitVal = (b >>> bit) & 1;
                double targetFreq = (bitVal == 1) ? MARK_FREQ : SPACE_FREQ;
                double phaseIncrement = (2.0 * Math.PI * targetFreq) / FALLBACK_SAMPLE_RATE;

                for (int s = 0; s < samplesPerBit && sampleIndex < totalSamples; s++) {
                    output[sampleIndex++] = (short) (Math.sin(currentPhase) * 32767.0);
                    currentPhase += phaseIncrement;
                    if (currentPhase >= 2.0 * Math.PI) {
                        currentPhase -= 2.0 * Math.PI;
                    }
                }
            }
        }
        return output;
    }

    private void playPcmTrack(short[] pcmSamples) {
        playPcmTrack(pcmSamples, SAMPLE_RATE);
    }

    private void playPcmTrack(short[] pcmSamples, int sampleRate) {
        byte[] pcmBytes = convertShortsToBytes(pcmSamples);

        activeAudioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(pcmBytes.length)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();

        activeAudioTrack.write(pcmBytes, 0, pcmBytes.length);
        activeAudioTrack.play();

        long durationMs = (long) (((double) pcmSamples.length / (double) sampleRate) * 1000.0) + 100;
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException ignored) {
        }

        try {
            activeAudioTrack.stop();
            activeAudioTrack.release();
        } catch (Exception ignored) {
        } finally {
            activeAudioTrack = null;
        }
    }

    private byte[] convertShortsToBytes(short[] shorts) {
        byte[] bytes = new byte[shorts.length * 2];
        for (int i = 0; i < shorts.length; i++) {
            bytes[i * 2] = (byte) (shorts[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((shorts[i] >>> 8) & 0xFF);
        }
        return bytes;
    }
}