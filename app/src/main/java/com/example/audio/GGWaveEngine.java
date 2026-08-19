package com.example.audio;

import com.example.utils.AirLogger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * High-performance Java JNI bridge to the native GGWave C++ DSP acoustic data modem.
 * Handles Multi-Frequency Shift Keying (MFSK), Reed-Solomon Forward Error Correction (FEC),
 * and audio buffer modulation/demodulation for AirSignal3.
 */
public class GGWaveEngine {
    private static final String TAG = "GGWaveEngine";

    // GGWave Transmission Protocol Identifiers
    public static final int PROTOCOL_AUDIBLE_NORMAL   = 1; // 1200-2400Hz (AMR / Cellular Voice Call safe)
    public static final int PROTOCOL_AUDIBLE_FAST     = 2; // High-speed audible burst transfer
    public static final int PROTOCOL_AUDIBLE_FASTEST  = 3; // Maximum audible throughput (local air-gap)
    public static final int PROTOCOL_ULTRASONIC_HD    = 4; // Near-ultrasound (18kHz - 20kHz)
    public static final int PROTOCOL_DTMF             = 5; // Standard dual-tone multi-frequency fallback

    // Default Audio Hardware Constants
    public static final int DEFAULT_SAMPLE_RATE = 48000;
    public static final int DEFAULT_SAMPLES_PER_FRAME = 1024;
    public static final int MAX_PAYLOAD_BYTES_PER_FRAME = 256;

    private static volatile GGWaveEngine sInstance;
    private final AtomicBoolean mIsInitialized = new AtomicBoolean(false);
    private final AtomicBoolean mLibraryLoaded = new AtomicBoolean(false);
    private final ReentrantLock mLock = new ReentrantLock();

    private int mCurrentSampleRate = DEFAULT_SAMPLE_RATE;

    // --- Native JNI Interface Declarations ---
    private native boolean initNative(int sampleRate);
    private native short[] encodeNative(byte[] payload, int protocolId, int volume);
    private native byte[] decodeNative(short[] pcmBuffer, int length);
    private native void releaseNative();

    private GGWaveEngine() {
        try {
            System.loadLibrary("ggwave-native");
            mLibraryLoaded.set(true);
            AirLogger.i(TAG, "Native library 'libggwave-native.so' successfully loaded.");
        } catch (UnsatisfiedLinkError e) {
            mLibraryLoaded.set(false);
            AirLogger.e(TAG, "FATAL: Could not load native library 'libggwave-native.so'", e);
        }
    }

    /**
     * Singleton instance provider.
     */
    public static GGWaveEngine getInstance() {
        if (sInstance == null) {
            synchronized (GGWaveEngine.class) {
                if (sInstance == null) {
                    sInstance = new GGWaveEngine();
                }
            }
        }
        return sInstance;
    }

    /**
     * Initializes the native DSP engine with the active hardware sample rate.
     *
     * @param sampleRate Input/Output sample rate (e.g. 48000 or 44100 Hz).
     * @return true if native instance was successfully allocated.
     */
    public boolean init(int sampleRate) {
        if (!mLibraryLoaded.get()) {
            AirLogger.e(TAG, "Cannot initialize: Native library is not loaded.");
            return false;
        }

        mLock.lock();
        try {
            if (mIsInitialized.get() && mCurrentSampleRate == sampleRate) {
                return true;
            }

            if (mIsInitialized.get()) {
                releaseNative();
                mIsInitialized.set(false);
            }

            boolean success = initNative(sampleRate);
            if (success) {
                mCurrentSampleRate = sampleRate;
                mIsInitialized.set(true);
                AirLogger.i(TAG, "Native GGWave instance initialized at " + sampleRate + " Hz");
            } else {
                AirLogger.e(TAG, "Native initNative() failed to allocate ggwave_Instance.");
            }
            return success;
        } catch (Throwable t) {
            AirLogger.e(TAG, "Exception during native engine initialization", t);
            return false;
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Modulates raw binary bytes into a PCM 16-bit audio waveform using the specified protocol.
     *
     * @param payload    Raw binary byte array to transmit.
     * @param protocolId One of PROTOCOL_AUDIBLE_NORMAL, PROTOCOL_AUDIBLE_FAST, etc.
     * @param volume     Output amplitude (0 to 100).
     * @return Array of 16-bit PCM audio samples, or null if encoding fails.
     */
    public short[] encode(byte[] payload, int protocolId, int volume) {
        if (payload == null || payload.length == 0) {
            AirLogger.w(TAG, "encode() called with empty or null payload.");
            return null;
        }

        if (!ensureInitialized()) {
            return null;
        }

        mLock.lock();
        try {
            int clampedVolume = Math.max(0, Math.min(100, volume));
            short[] waveform = encodeNative(payload, protocolId, clampedVolume);

            if (waveform == null || waveform.length == 0) {
                AirLogger.e(TAG, "encodeNative() returned empty waveform for payload size: " + payload.length);
                return null;
            }

            return waveform;
        } catch (Throwable t) {
            AirLogger.e(TAG, "Error encoding payload via native engine", t);
            return null;
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Feeds incoming PCM microphone audio samples directly to the native demodulator.
     * Automatically applies FFT, detects MFSK tones, and executes Reed-Solomon error correction.
     *
     * @param pcmBuffer Raw 16-bit PCM audio samples captured from AudioRecord.
     * @param length    Number of valid samples in pcmBuffer.
     * @return Fully decoded and validated payload bytes, or null if no complete packet was found in this window.
     */
    public byte[] decode(short[] pcmBuffer, int length) {
        if (pcmBuffer == null || length <= 0) {
            return null;
        }

        if (!ensureInitialized()) {
            return null;
        }

        mLock.lock();
        try {
            return decodeNative(pcmBuffer, length);
        } catch (Throwable t) {
            AirLogger.e(TAG, "Error decoding PCM buffer via native engine", t);
            return null;
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Releases native memory allocations and DSP instance handles.
     */
    public void release() {
        mLock.lock();
        try {
            if (mIsInitialized.get()) {
                releaseNative();
                mIsInitialized.set(false);
                AirLogger.i(TAG, "Native GGWave engine successfully released.");
            }
        } catch (Throwable t) {
            AirLogger.e(TAG, "Error during native release", t);
        } finally {
            mLock.unlock();
        }
    }

    public boolean isInitialized() {
        return mIsInitialized.get() && mLibraryLoaded.get();
    }

    public int getCurrentSampleRate() {
        return mCurrentSampleRate;
    }

    private boolean ensureInitialized() {
        if (!mIsInitialized.get()) {
            return init(mCurrentSampleRate);
        }
        return true;
    }
}