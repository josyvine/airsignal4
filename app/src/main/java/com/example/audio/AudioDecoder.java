package com.example.audio;

import com.example.models.TemplateToken;
import com.example.utils.AirLogger;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public class AudioDecoder {

    private static final String TAG = "AudioDecoder";

    public static final int MARK_FREQ = 1200;   // Bit 1 (Hz)
    public static final int SPACE_FREQ = 2200;  // Bit 0 (Hz)

    public static final byte SYNC_PREAMBLE = (byte) 0xAA;
    public static final byte START_FRAME_DELIMITER = (byte) 0x7E;

    // Minimum RMS energy threshold to distinguish in-call signal from silence
    private static final double MIN_ENERGY_THRESHOLD = 300.0;

    // Standard In-Call Telephony Dual-Frequency Grid (Hz)
    public static final int[] DTMF_ROW_FREQS = new int[]{697, 770, 852, 941};
    public static final int[] DTMF_COL_FREQS = new int[]{1209, 1336, 1477, 1633};

    private static final char[][] DTMF_MATRIX = new char[][]{
            {'1', '2', '3', 'A'},
            {'4', '5', '6', 'B'},
            {'7', '8', '9', 'C'},
            {'*', '0', '#', 'D'}
    };

    /**
     * Decodes in-call audio dual-frequencies from a PCM buffer segment.
     * Returns the detected symbol character or '?' if silence/noise.
     */
    public static char detectDtmfSymbol(short[] pcm, int offset, int length, int sampleRate) {
        if (pcm == null || length <= 0 || offset + length > pcm.length) {
            return '?';
        }

        double totalEnergy = calculateRmsEnergy(pcm, offset, length);
        if (totalEnergy < MIN_ENERGY_THRESHOLD) {
            return '?'; // Silence or background noise
        }

        // Find dominant row frequency
        int bestRow = -1;
        double maxRowPower = 0.0;
        for (int r = 0; r < DTMF_ROW_FREQS.length; r++) {
            double power = calculateGoertzelPower(pcm, offset, length, DTMF_ROW_FREQS[r], sampleRate);
            if (power > maxRowPower) {
                maxRowPower = power;
                bestRow = r;
            }
        }

        // Find dominant column frequency
        int bestCol = -1;
        double maxColPower = 0.0;
        for (int c = 0; c < DTMF_COL_FREQS.length; c++) {
            double power = calculateGoertzelPower(pcm, offset, length, DTMF_COL_FREQS[c], sampleRate);
            if (power > maxColPower) {
                maxColPower = power;
                bestCol = c;
            }
        }

        // Validate frequency power ratio
        if (bestRow != -1 && bestCol != -1 && maxRowPower > 5000.0 && maxColPower > 5000.0) {
            return DTMF_MATRIX[bestRow][bestCol];
        }

        return '?';
    }

    /**
     * Converts a pair of in-call DTMF symbols back into a single data byte.
     */
    public static int dtmfPairToByte(char high, char low) {
        int h = dtmfCharToNibble(high);
        int l = dtmfCharToNibble(low);
        if (h == -1 || l == -1) return -1;
        return ((h << 4) | l) & 0xFF;
    }

    private static int dtmfCharToNibble(char c) {
        char upper = Character.toUpperCase(c);
        switch (upper) {
            case '0': return 0x0;
            case '1': return 0x1;
            case '2': return 0x2;
            case '3': return 0x3;
            case '4': return 0x4;
            case '5': return 0x5;
            case '6': return 0x6;
            case '7': return 0x7;
            case '8': return 0x8;
            case '9': return 0x9;
            case 'A': return 0xA;
            case 'B': return 0xB;
            case 'C': return 0xC;
            case 'D': return 0xD;
            case '*': return 0xE;
            case '#': return 0xF;
            default: return -1;
        }
    }

    /**
     * Legacy backward-compatible bit detector.
     */
    public static int detectBit(short[] pcmBuffer, int sampleRate) {
        if (pcmBuffer == null || pcmBuffer.length == 0) return 0;
        return detectBit(pcmBuffer, 0, pcmBuffer.length, sampleRate);
    }

    /**
     * High-speed sub-array bit detector avoiding memory allocation.
     */
    public static int detectBit(short[] pcm, int offset, int length, int sampleRate) {
        if (pcm == null || length <= 0 || offset + length > pcm.length) {
            return 0;
        }

        double totalEnergy = calculateRmsEnergy(pcm, offset, length);
        if (totalEnergy < MIN_ENERGY_THRESHOLD) {
            return -1; // Silence or background noise
        }

        double markPower = calculateGoertzelPower(pcm, offset, length, MARK_FREQ, sampleRate);
        double spacePower = calculateGoertzelPower(pcm, offset, length, SPACE_FREQ, sampleRate);

        return (markPower > spacePower) ? 1 : 0;
    }

    /**
     * Demodulates a continuous PCM audio buffer into a list of raw data bytes.
     * Checks GGWave native MFSK demodulator first, then falls back to Goertzel bitstream extraction.
     */
    public static byte[] decodeFrameFromPcm(short[] pcmStream, int sampleRate, int baudRate) {
        if (pcmStream == null || pcmStream.length == 0 || baudRate <= 0) {
            return new byte[0];
        }

        // Primary DSP Path: GGWave native decoding
        GGWaveEngine engine = GGWaveEngine.getInstance();
        if (engine.init(sampleRate)) {
            byte[] nativeDecoded = engine.decode(pcmStream, pcmStream.length);
            if (nativeDecoded != null && nativeDecoded.length > 0) {
                AirLogger.i(TAG, "decodeFrameFromPcm: GGWave successfully decoded " + nativeDecoded.length + " bytes.");
                return nativeDecoded;
            }
        }

        // Secondary/Fallback DSP Path: FSK bit-slicing
        double samplesPerBit = (double) sampleRate / (double) baudRate;
        int totalBits = (int) (pcmStream.length / samplesPerBit);

        if (totalBits < 8) return new byte[0];

        List<Integer> rawBits = new ArrayList<>();

        for (int b = 0; b < totalBits; b++) {
            int offset = (int) Math.round(b * samplesPerBit);
            int len = (int) Math.round((b + 1) * samplesPerBit) - offset;

            if (offset + len <= pcmStream.length) {
                int bitVal = detectBit(pcmStream, offset, len, sampleRate);
                if (bitVal != -1) {
                    rawBits.add(bitVal);
                } else {
                    rawBits.add(0);
                }
            }
        }

        // Reconstruct bytes from bitstream
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        int currentByte = 0;
        int bitCount = 0;

        for (int bit : rawBits) {
            currentByte = (currentByte << 1) | (bit & 1);
            bitCount++;

            if (bitCount == 8) {
                byteStream.write((byte) (currentByte & 0xFF));
                currentByte = 0;
                bitCount = 0;
            }
        }

        byte[] allBytes = byteStream.toByteArray();
        return extractPayloadFromFramedBytes(allBytes);
    }

    /**
     * Mode 4: Directly extracts and validates a 16-byte TemplateToken from raw PCM audio.
     */
    public static TemplateToken decodeTokenFromPcm(short[] pcmStream, int sampleRate, int baudRate) {
        byte[] payload = decodeFrameFromPcm(pcmStream, sampleRate, baudRate);
        if (payload == null || payload.length < TemplateToken.TOKEN_BYTE_SIZE) {
            return null;
        }

        // Locate valid 16-byte token slice with matching CRC16
        for (int i = 0; i <= payload.length - TemplateToken.TOKEN_BYTE_SIZE; i++) {
            byte[] candidate = new byte[TemplateToken.TOKEN_BYTE_SIZE];
            System.arraycopy(payload, i, candidate, 0, TemplateToken.TOKEN_BYTE_SIZE);

            TemplateToken token = TemplateToken.fromByteArray(candidate);
            if (token != null && token.isValid()) {
                AirLogger.i(TAG, "Successfully demodulated valid TemplateToken ID=" + token.getTemplateId());
                return token;
            }
        }

        return null;
    }

    /**
     * Hunts for sync preamble (0xAA 0xAA 0xAA 0x7E) and extracts the enclosed payload.
     */
    private static byte[] extractPayloadFromFramedBytes(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length < 4) {
            return rawBytes != null ? rawBytes : new byte[0];
        }

        int syncIndex = -1;
        for (int i = 0; i < rawBytes.length - 1; i++) {
            if (rawBytes[i] == START_FRAME_DELIMITER) {
                syncIndex = i + 1;
                break;
            }
        }

        if (syncIndex != -1 && syncIndex < rawBytes.length) {
            byte[] payload = new byte[rawBytes.length - syncIndex];
            System.arraycopy(rawBytes, syncIndex, payload, 0, payload.length);
            return payload;
        }

        return rawBytes;
    }

    /**
     * Single-bin discrete Fourier transform (Goertzel Algorithm).
     */
    public static double calculateGoertzelPower(short[] pcm, int offset, int length, double targetFreq, int sampleRate) {
        double k = Math.round(((double) length * targetFreq) / (double) sampleRate);
        double omega = (2.0 * Math.PI * k) / (double) length;
        double cosine = Math.cos(omega);
        double coeff = 2.0 * cosine;

        double q0 = 0.0;
        double q1 = 0.0;
        double q2 = 0.0;

        for (int i = offset; i < offset + length; i++) {
            q0 = coeff * q1 - q2 + (double) pcm[i];
            q2 = q1;
            q1 = q0;
        }

        return (q1 * q1 + q2 * q2 - q1 * q2 * coeff);
    }

    /**
     * Computes RMS energy of a PCM buffer segment.
     */
    public static double calculateRmsEnergy(short[] pcm, int offset, int length) {
        if (length <= 0) return 0.0;
        double sum = 0.0;
        for (int i = offset; i < offset + length; i++) {
            sum += (double) pcm[i] * (double) pcm[i];
        }
        return Math.sqrt(sum / (double) length);
    }
}