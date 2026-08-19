package com.example.audio;

public class ErrorCorrection {

    // Simple Hamming(7,4) error correction helper for acoustic bitstream
    public static int encodeHamming74(int nibble) {
        int d0 = (nibble >> 0) & 1;
        int d1 = (nibble >> 1) & 1;
        int d2 = (nibble >> 2) & 1;
        int d3 = (nibble >> 3) & 1;

        int p0 = d0 ^ d1 ^ d3;
        int p1 = d0 ^ d2 ^ d3;
        int p2 = d1 ^ d2 ^ d3;

        return (p0 << 0) | (p1 << 1) | (d0 << 2) | (p2 << 3) | (d1 << 4) | (d2 << 5) | (d3 << 6);
    }

    public static int decodeHamming74(int code) {
        int p0 = (code >> 0) & 1;
        int p1 = (code >> 1) & 1;
        int d0 = (code >> 2) & 1;
        int p2 = (code >> 3) & 1;
        int d1 = (code >> 4) & 1;
        int d2 = (code >> 5) & 1;
        int d3 = (code >> 6) & 1;

        int s0 = p0 ^ d0 ^ d1 ^ d3;
        int s1 = p1 ^ d0 ^ d2 ^ d3;
        int s2 = p2 ^ d1 ^ d2 ^ d3;

        int syndrome = (s2 << 2) | (s1 << 1) | s0;

        // Correct single bit error if syndrome != 0
        if (syndrome == 3) d0 ^= 1;
        else if (syndrome == 5) d1 ^= 1;
        else if (syndrome == 6) d2 ^= 1;
        else if (syndrome == 7) d3 ^= 1;

        return (d3 << 3) | (d2 << 2) | (d1 << 1) | d0;
    }
}
