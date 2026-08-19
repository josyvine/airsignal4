package com.example.utils;

import android.util.Base64;
import com.example.models.DataPacket;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DataPacketManager {

    private static final String TAG = "DataPacketManager";
    
    // Mode 1: SMS Chunking Constants
    private static final int MAX_SMS_PAYLOAD_SIZE = 120; // safe chunk size inside 160 char SMS
    
    // Mode 2 & 3: Raw Audio Binary Constants
    private static final int MAX_BINARY_PAYLOAD_SIZE = 256; 
    public static final byte MODE_RAW_BINARY_STREAM = 0x03;

    // =========================================================================
    // Mode 1: SMS / Text Base64 Packet Management
    // =========================================================================

    public static List<DataPacket> createPackets(byte[] binaryData) {
        List<DataPacket> packets = new ArrayList<>();
        String fileId = UUID.randomUUID().toString().substring(0, 8);

        String base64Data = Base64.encodeToString(binaryData, Base64.NO_WRAP);
        int totalLength = base64Data.length();
        int totalPackets = (int) Math.ceil((double) totalLength / MAX_SMS_PAYLOAD_SIZE);

        for (int i = 0; i < totalPackets; i++) {
            int start = i * MAX_SMS_PAYLOAD_SIZE;
            int end = Math.min(start + MAX_SMS_PAYLOAD_SIZE, totalLength);
            String chunk = base64Data.substring(start, end);

            long crc = EncryptionUtils.calculateCRC32(chunk.getBytes());
            packets.add(new DataPacket(fileId, i + 1, totalPackets, chunk, crc));
        }

        return packets;
    }

    public static String formatSmsPacket(DataPacket packet) {
        return String.format("AIR_START|ID:%s|PART:%03d/%03d|DATA:%s|CRC:%d",
                packet.getFileId(),
                packet.getPacketIndex(),
                packet.getTotalPackets(),
                packet.getPayload(),
                packet.getChecksum());
    }

    public static DataPacket parseSmsPacket(String rawSms) {
        if (rawSms == null || !rawSms.startsWith("AIR_START|")) {
            return null;
        }

        try {
            String[] parts = rawSms.split("\\|");
            String fileId = parts[1].replace("ID:", "");
            String[] partInfo = parts[2].replace("PART:", "").split("/");
            int index = Integer.parseInt(partInfo[0]);
            int total = Integer.parseInt(partInfo[1]);
            String payload = parts[3].replace("DATA:", "");
            long crc = Long.parseLong(parts[4].replace("CRC:", ""));

            return new DataPacket(fileId, index, total, payload, crc);
        } catch (Exception e) {
            AirLogger.e(TAG, "Error parsing SMS packet", e);
            return null;
        }
    }

    // =========================================================================
    // Mode 2 & 3: Raw Binary Audio Packet Management (Zero Base64 Overhead)
    // =========================================================================

    /**
     * Slices an arbitrary binary file (up to 500 KB) into dense 256-byte chunks.
     * Generates a 7-byte binary header per packet to maximize 2400 Baud audio throughput.
     */
    public static List<byte[]> createBinaryPackets(byte[] binaryData) {
        List<byte[]> packets = new ArrayList<>();
        if (binaryData == null || binaryData.length == 0) return packets;

        int totalPackets = (int) Math.ceil((double) binaryData.length / MAX_BINARY_PAYLOAD_SIZE);

        for (int i = 0; i < totalPackets; i++) {
            int start = i * MAX_BINARY_PAYLOAD_SIZE;
            int length = Math.min(MAX_BINARY_PAYLOAD_SIZE, binaryData.length - start);
            
            byte[] chunkPayload = new byte[length];
            System.arraycopy(binaryData, start, chunkPayload, 0, length);
            
            byte[] framedPacket = formatBinaryPacket(i + 1, totalPackets, chunkPayload);
            packets.add(framedPacket);
        }

        return packets;
    }

    /**
     * Constructs the compact 7-byte audio binary header + CRC16:
     * [1-byte Mode] [2-byte Index] [2-byte Total] [N-byte Payload] [2-byte CRC16]
     */
    private static byte[] formatBinaryPacket(int packetIndex, int totalPackets, byte[] payload) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            
            baos.write(MODE_RAW_BINARY_STREAM);
            
            baos.write((packetIndex >> 8) & 0xFF);
            baos.write(packetIndex & 0xFF);
            
            baos.write((totalPackets >> 8) & 0xFF);
            baos.write(totalPackets & 0xFF);
            
            baos.write(payload);
            
            byte[] dataWithoutCrc = baos.toByteArray();
            int crc16 = calculateCrc16(dataWithoutCrc);
            
            baos.write((crc16 >> 8) & 0xFF);
            baos.write(crc16 & 0xFF);
            
            return baos.toByteArray();
        } catch (Exception e) {
            AirLogger.e(TAG, "Error formatting binary packet", e);
            return new byte[0];
        }
    }

    /**
     * Parses an incoming demodulated audio frame.
     * Returns a DataPacket object containing the raw bytes in the string payload field via Base64 (internal transport only).
     */
    public static DataPacket parseBinaryPacket(byte[] rawFrame) {
        if (rawFrame == null || rawFrame.length < 8) return null;
        
        if (rawFrame[0] != MODE_RAW_BINARY_STREAM) return null;

        try {
            int packetIndex = ((rawFrame[1] & 0xFF) << 8) | (rawFrame[2] & 0xFF);
            int totalPackets = ((rawFrame[3] & 0xFF) << 8) | (rawFrame[4] & 0xFF);
            
            int payloadLength = rawFrame.length - 7; // Subtract Mode(1) + Idx(2) + Total(2) + CRC(2)
            if (payloadLength <= 0) return null;

            byte[] payload = new byte[payloadLength];
            System.arraycopy(rawFrame, 5, payload, 0, payloadLength);
            
            // Extract & Verify CRC-16
            byte[] withoutCrc = new byte[rawFrame.length - 2];
            System.arraycopy(rawFrame, 0, withoutCrc, 0, rawFrame.length - 2);
            int computedCrc = calculateCrc16(withoutCrc);
            
            int frameCrc = ((rawFrame[rawFrame.length - 2] & 0xFF) << 8) | (rawFrame[rawFrame.length - 1] & 0xFF);
            
            if (computedCrc != frameCrc) {
                AirLogger.w(TAG, "Binary Packet CRC mismatch. Expected: " + frameCrc + ", Got: " + computedCrc);
                return null; // Reject corrupted acoustic packet
            }
            
            // Re-use DataPacket model: encode pure binary temporarily as Base64 for database SQLite storage
            String fileId = "AUDIO_BIN"; 
            String base64Payload = Base64.encodeToString(payload, Base64.NO_WRAP);
            
            return new DataPacket(fileId, packetIndex, totalPackets, base64Payload, computedCrc);
        } catch (Exception e) {
            AirLogger.e(TAG, "Error parsing binary packet", e);
            return null;
        }
    }

    /**
     * Standard CCITT CRC-16 implementation.
     */
    private static int calculateCrc16(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc = ((crc >>> 8) | (crc << 8)) & 0xFFFF;
            crc ^= (b & 0xFF);
            crc ^= (crc & 0xFF) >> 4;
            crc ^= (crc << 12) & 0xFFFF;
            crc ^= ((crc & 0xFF) << 5) & 0xFFFF;
        }
        return crc & 0xFFFF;
    }
}