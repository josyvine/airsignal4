package com.example.knowledge;

import com.example.models.TemplateToken;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PhoneticTokenManager {

    // 32-Symbol NATO & Digit Base-32 Codebook (Each symbol = 5 bits: 2^5 = 32)
    private static final String[] BASE32_WORDS = {
            "ALPHA",    // 00000 (0)
            "BRAVO",    // 00001 (1)
            "CHARLIE",  // 00010 (2)
            "DELTA",    // 00011 (3)
            "ECHO",     // 00100 (4)
            "FOXTROT",  // 00101 (5)
            "GOLF",     // 00110 (6)
            "HOTEL",    // 00111 (7)
            "INDIA",    // 01000 (8)
            "JULIETT",  // 01001 (9)
            "KILO",     // 01010 (10)
            "LIMA",     // 01011 (11)
            "MIKE",     // 01100 (12)
            "NOVEMBER", // 01101 (13)
            "OSCAR",    // 01110 (14)
            "PAPA",     // 01111 (15)
            "QUEBEC",   // 10000 (16)
            "ROMEO",    // 10001 (17)
            "SIERRA",   // 10010 (18)
            "TANGO",    // 10011 (19)
            "UNIFORM",  // 10100 (20)
            "VICTOR",   // 10101 (21)
            "WHISKEY",  // 10110 (22)
            "XRAY",     // 10111 (23)
            "YANKEE",   // 11000 (24)
            "ZULU",     // 11001 (25)
            "ZERO",     // 11010 (26)
            "ONE",      // 11011 (27)
            "TWO",      // 11100 (28)
            "THREE",    // 11101 (29)
            "FOUR",     // 11110 (30)
            "FIVE"      // 11111 (31)
    };

    private static final char[] BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345".toCharArray();

    private static final Map<String, Integer> WORD_TO_INDEX = new HashMap<>();
    private static final Map<Character, Integer> CHAR_TO_INDEX = new HashMap<>();

    static {
        for (int i = 0; i < BASE32_WORDS.length; i++) {
            WORD_TO_INDEX.put(BASE32_WORDS[i].toUpperCase(Locale.US), i);
        }
        for (int i = 0; i < BASE32_CHARS.length; i++) {
            CHAR_TO_INDEX.put(BASE32_CHARS[i], i);
        }
    }

    /**
     * Converts a TemplateToken (16 bytes = 128 bits) into a NATO Phonetic Sentence (26 words).
     */
    public static String encodeToPhoneticWords(TemplateToken token) {
        if (token == null) return "";
        byte[] data = token.toByteArray();
        int[] fiveBitIndices = convert8BitTo5Bit(data);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fiveBitIndices.length; i++) {
            sb.append(BASE32_WORDS[fiveBitIndices[i]]);
            if (i < fiveBitIndices.length - 1) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

    /**
     * Parses a NATO Phonetic Sentence back into a validated TemplateToken.
     */
    public static TemplateToken decodeFromPhoneticWords(String phoneticSentence) {
        if (phoneticSentence == null || phoneticSentence.trim().isEmpty()) {
            return null;
        }

        String[] words = phoneticSentence.trim().toUpperCase(Locale.US).split("[\\s,-]+");
        List<Integer> indices = new ArrayList<>();

        for (String word : words) {
            String clean = word.trim();
            if (clean.isEmpty()) continue;
            if (WORD_TO_INDEX.containsKey(clean)) {
                indices.add(WORD_TO_INDEX.get(clean));
            } else {
                return null; // Invalid word not in codebook
            }
        }

        int[] fiveBitIndices = new int[indices.size()];
        for (int i = 0; i < indices.size(); i++) {
            fiveBitIndices[i] = indices.get(i);
        }

        byte[] rawBytes = convert5BitTo8Bit(fiveBitIndices, TemplateToken.TOKEN_BYTE_SIZE);
        if (rawBytes == null) return null;

        return TemplateToken.fromByteArray(rawBytes);
    }

    /**
     * Encodes a TemplateToken into a compact Base-32 alphanumeric text string (e.g. "AB7ST-EZ49K-X921Q").
     */
    public static String encodeToBase32String(TemplateToken token) {
        if (token == null) return "";
        byte[] data = token.toByteArray();
        int[] fiveBitIndices = convert8BitTo5Bit(data);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fiveBitIndices.length; i++) {
            sb.append(BASE32_CHARS[fiveBitIndices[i]]);
            if ((i + 1) % 5 == 0 && (i + 1) < fiveBitIndices.length) {
                sb.append("-");
            }
        }
        return sb.toString();
    }

    /**
     * Decodes a Base-32 alphanumeric text string back into a TemplateToken.
     */
    public static TemplateToken decodeFromBase32String(String base32Code) {
        if (base32Code == null || base32Code.trim().isEmpty()) {
            return null;
        }

        String cleaned = base32Code.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.US);
        int[] fiveBitIndices = new int[cleaned.length()];

        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (CHAR_TO_INDEX.containsKey(c)) {
                fiveBitIndices[i] = CHAR_TO_INDEX.get(c);
            } else {
                return null; // Unknown character
            }
        }

        byte[] rawBytes = convert5BitTo8Bit(fiveBitIndices, TemplateToken.TOKEN_BYTE_SIZE);
        if (rawBytes == null) return null;

        return TemplateToken.fromByteArray(rawBytes);
    }

    // =========================================================================
    // Bitwise 8-bit to 5-bit (and vice versa) Conversion Algorithms
    // =========================================================================

    private static int[] convert8BitTo5Bit(byte[] data) {
        int totalBits = data.length * 8;
        int total5BitSymbols = (int) Math.ceil((double) totalBits / 5.0);
        int[] symbols = new int[total5BitSymbols];

        int buffer = 0;
        int bitsInBuffer = 0;
        int symbolIndex = 0;

        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsInBuffer += 8;

            while (bitsInBuffer >= 5) {
                symbols[symbolIndex++] = (buffer >>> (bitsInBuffer - 5)) & 0x1F;
                bitsInBuffer -= 5;
            }
        }

        if (bitsInBuffer > 0) {
            symbols[symbolIndex] = (buffer << (5 - bitsInBuffer)) & 0x1F;
        }

        return symbols;
    }

    private static byte[] convert5BitTo8Bit(int[] symbols, int expectedByteLength) {
        byte[] output = new byte[expectedByteLength];
        int buffer = 0;
        int bitsInBuffer = 0;
        int byteIndex = 0;

        for (int sym : symbols) {
            buffer = (buffer << 5) | (sym & 0x1F);
            bitsInBuffer += 5;

            while (bitsInBuffer >= 8 && byteIndex < expectedByteLength) {
                output[byteIndex++] = (byte) ((buffer >>> (bitsInBuffer - 8)) & 0xFF);
                bitsInBuffer -= 8;
            }
        }

        if (byteIndex < expectedByteLength) {
            return null; // Truncated or incomplete data
        }

        return output;
    }
}