package com.example.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PhoneticBase64Dictionary {

    public static final int BLOCK_CHUNK_SIZE = 500; // Each phonetic word represents 500 Base64 characters

    // Forward Lookup: Word -> 500-Char Base64 Block
    private static final Map<String, String> WORD_TO_BLOCK = new HashMap<>();
    
    // Reverse Lookup: 500-Char Base64 Block -> Word
    private static final Map<String, String> BLOCK_TO_WORD = new HashMap<>();

    static {
        initializeDictionary();
    }

    private static void initializeDictionary() {
        // Standard NATO Phonetic Keywords
        String[] words = {
                "ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOXTROT",
                "GOLF", "HOTEL", "INDIA", "JULIETT", "KILO", "LIMA",
                "MIKE", "NOVEMBER", "OSCAR", "PAPA", "QUEBEC", "ROMEO",
                "SIERRA", "TANGO", "UNIFORM", "VICTOR", "WHISKEY", "XRAY",
                "YANKEE", "ZULU"
        };

        // 1. Pre-built standard WebP / JPEG / PNG Container & Palette Signatures
        // (Representing standard image metadata, Huffman tables, and compression headers)
        registerBlock("ALPHA", generateStandardBlock("iVBORw0KGgoAAAANSUhEUgAA", 'A')); // Standard PNG Signature & IHDR Block
        registerBlock("BRAVO", generateStandardBlock("/9j/4AAQSkZJRgABAQEASABIAAD", 'B')); // Standard JPEG SOI & APP0 Header Block
        registerBlock("CHARLIE", generateStandardBlock("UklGRlYAAABXRUJQVlA4TFAAAA", 'C')); // Standard Lossless WebP RIFF/VP8 Header Block
        registerBlock("DELTA", generateStandardBlock("AAAAFmx0ZXh0AAAAAABDb3B5", 'D')); // Color Palette & EXIF Profile Block 1
        registerBlock("ECHO", generateStandardBlock("eNrtwTEBAAAAwqD1T20ND6AA", 'E')); // Uniform Neutral Background Block
        registerBlock("FOXTROT", generateStandardBlock("PD94bWwgdmVyc2lvbj0iMS4w", 'F')); // XML Metadata Header Block
        registerBlock("GOLF", generateStandardBlock("AP//////////////////////", 'G')); // Solid Light Space / Gradient Block
        registerBlock("HOTEL", generateStandardBlock("AAAAAAAAAAAAAAAAAAAAAAAA", 'H')); // Solid Zero / Alpha Channel Block
        registerBlock("INDIA", generateStandardBlock("77u/PD94bWwgdmVyc2lvbj0i", 'I')); // UTF-8 BOM Image Manifest Block
        registerBlock("JULIETT", generateStandardBlock("R0lGODlhAQABAIAAAAAAAP///", 'J')); // Minimal GIF / Canvas Container Block
        registerBlock("KILO", generateStandardBlock("Qk02BAAAAAAAADYAAAAoAAAA", 'K')); // BMP Device Independent Header Block
        registerBlock("LIMA", generateStandardBlock("SUkqAAgAAAASAAABAwABAAAA", 'L')); // TIFF Big-Endian Metadata Block
        registerBlock("MIKE", generateStandardBlock("TU0AKgAAAAgADgEBAAMAAAAB", 'M')); // TIFF Little-Endian Metadata Block
        registerBlock("NOVEMBER", generateStandardBlock("iVBORw0KGgoAAAANSUhEUgAC", 'N')); // Secondary High-Res PNG Header Block
        registerBlock("OSCAR", generateStandardBlock("/9j/4AAQSkZJRgABAgEASABIAAE", 'O')); // Secondary JPEG High-Quality Quantization Block
        registerBlock("PAPA", generateStandardBlock("UklGRiIAAABXRUJQVlA4IBYA", 'P')); // WebP Extended Alpha Chunk Block
        registerBlock("QUEBEC", generateStandardBlock("eNo9zLEJwCAQAMFvF5hY2Fn", 'Q')); // Standard Deflate Compression Matrix Block
        registerBlock("ROMEO", generateStandardBlock("AAAAAXNSR0IArs4c6QAAAARn", 'R')); // sRGB Color Profile Block
        registerBlock("SIERRA", generateStandardBlock("QUFBQUFBQUFBQUFBQUFBQUFB", 'S')); // Repetitive High-Frequency Byte Pattern Block
        registerBlock("TANGO", generateStandardBlock("////////////////////////", 'T')); // White Space Saturation Block
        registerBlock("UNIFORM", generateStandardBlock("000000000000000000000000", 'U')); // Black Space Saturation Block
        registerBlock("VICTOR", generateStandardBlock("eNp1zEERAAAIAyD1r28Gf8gE", 'V')); // ZLIB Compressed Spatial Data Block
        registerBlock("WHISKEY", generateStandardBlock("PHN2ZyB4bWxucz0iaHR0cDov", 'W')); // SVG Vector Wrapper Block
        registerBlock("XRAY", generateStandardBlock("data:image/webp;base64,U", 'X')); // Direct Data URI WebP Prefix Block
        registerBlock("YANKEE", generateStandardBlock("data:image/jpeg;base64,/9", 'Y')); // Direct Data URI JPEG Prefix Block
        registerBlock("ZULU", generateStandardBlock("data:image/png;base64,iV", 'Z')); // Direct Data URI PNG Prefix Block
    }

    private static void registerBlock(String word, String base64Block) {
        WORD_TO_BLOCK.put(word.toUpperCase(Locale.US), base64Block);
        BLOCK_TO_WORD.put(base64Block, word.toUpperCase(Locale.US));
    }

    private static String generateStandardBlock(String prefix, char fillChar) {
        StringBuilder sb = new StringBuilder(prefix);
        while (sb.length() < BLOCK_CHUNK_SIZE) {
            sb.append(fillChar);
        }
        return sb.substring(0, BLOCK_CHUNK_SIZE);
    }

    /**
     * Slices an input Base64 string and substitutes matching 500-character blocks with phonetic words.
     */
    public static List<String> encodeBase64ToPhoneticTokens(String base64Input) {
        if (base64Input == null || base64Input.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> tokens = new ArrayList<>();
        int length = base64Input.length();
        int index = 0;

        while (index < length) {
            int end = Math.min(index + BLOCK_CHUNK_SIZE, length);
            String chunk = base64Input.substring(index, end);

            if (chunk.length() == BLOCK_CHUNK_SIZE && BLOCK_TO_WORD.containsKey(chunk)) {
                // Exact dictionary match: substitute 500 characters with 1 word!
                tokens.add(BLOCK_TO_WORD.get(chunk));
            } else {
                // Unique literal chunk: tagged with LIT prefix
                tokens.add("LIT:" + chunk);
            }
            index = end;
        }

        return tokens;
    }

    /**
     * Expands a sequence of phonetic tokens back into the exact original Base64 string.
     */
    public static String decodePhoneticTokensToBase64(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return "";
        }

        StringBuilder base64Builder = new StringBuilder();

        for (String token : tokens) {
            if (token == null || token.trim().isEmpty()) continue;
            String clean = token.trim();

            if (clean.startsWith("LIT:")) {
                // Extract literal Base64 characters
                base64Builder.append(clean.substring(4));
            } else {
                String word = clean.toUpperCase(Locale.US);
                if (WORD_TO_BLOCK.containsKey(word)) {
                    // Expand 1 word into the exact 500-character Base64 block
                    base64Builder.append(WORD_TO_BLOCK.get(word));
                } else {
                    // Fallback for custom or unknown literal tokens
                    base64Builder.append(clean);
                }
            }
        }

        return base64Builder.toString();
    }

    public static boolean containsWord(String word) {
        return word != null && WORD_TO_BLOCK.containsKey(word.toUpperCase(Locale.US));
    }

    public static String getBlockForWord(String word) {
        if (word == null) return null;
        return WORD_TO_BLOCK.get(word.toUpperCase(Locale.US));
    }
}