package com.example.audio;

public class ToneGenerator {

    public static final int SAMPLE_RATE = 44100;
    public static final int MARK_FREQ = 1200;  // Bit '1'
    public static final int SPACE_FREQ = 2200; // Bit '0'

    public static short[] generateTone(int frequency, int durationMs) {
        int numSamples = (SAMPLE_RATE * durationMs) / 1000;
        short[] sample = new short[numSamples];
        double angleStep = 2.0 * Math.PI * frequency / SAMPLE_RATE;
        double currentAngle = 0.0;

        for (int i = 0; i < numSamples; i++) {
            sample[i] = (short) (Math.sin(currentAngle) * 32767);
            currentAngle += angleStep;
        }

        return sample;
    }
}
