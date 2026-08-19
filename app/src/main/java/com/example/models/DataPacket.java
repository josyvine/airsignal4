package com.example.models;

public class DataPacket {
    private String fileId;
    private int packetIndex;
    private int totalPackets;
    private String payload;
    private long checksum;

    public DataPacket(String fileId, int packetIndex, int totalPackets, String payload, long checksum) {
        this.fileId = fileId;
        this.packetIndex = packetIndex;
        this.totalPackets = totalPackets;
        this.payload = payload;
        this.checksum = checksum;
    }

    public String getFileId() { return fileId; }
    public int getPacketIndex() { return packetIndex; }
    public int getTotalPackets() { return totalPackets; }
    public String getPayload() { return payload; }
    public long getChecksum() { return checksum; }
}
