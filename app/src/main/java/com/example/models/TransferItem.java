package com.example.models;

public class TransferItem {
    
    // Core Modality Types
    public static final String MODE_SMS_DATA = "SMS_DATA";
    public static final String MODE_AUDIO_DATA = "AUDIO_DATA";
    public static final String MODE_PHONETIC_TOKEN = "PHONETIC_TOKEN";
    public static final String MODE_RAW_BINARY_AUDIO_2400 = "RAW_BINARY_2400";
    
    // Standard Status Flags
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_TRANSFERRING = "TRANSFERRING";
    public static final String STATUS_RECEIVING = "RECEIVING";
    public static final String STATUS_PAUSED_WAITING = "PAUSED_WAITING";
    public static final String STATUS_ASSEMBLING = "ASSEMBLING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    private long id;
    private String fileId;
    private String filename;
    private long size;
    private int progress; // 0 to 100
    private String status; 
    private String mode; 
    private int totalPackets;
    private int receivedPackets;

    /**
     * Legacy constructor (Fallback for components that don't explicitly pass fileId)
     */
    public TransferItem(long id, String filename, long size, int progress, String status, String mode, int totalPackets, int receivedPackets) {
        this.id = id;
        this.fileId = "UNKNOWN_" + System.currentTimeMillis();
        this.filename = filename;
        this.size = size;
        this.progress = progress;
        this.status = status;
        this.mode = mode;
        this.totalPackets = totalPackets;
        this.receivedPackets = receivedPackets;
    }

    /**
     * Modern Complete Constructor
     */
    public TransferItem(long id, String fileId, String filename, long size, int progress, String status, String mode, int totalPackets, int receivedPackets) {
        this.id = id;
        this.fileId = (fileId != null && !fileId.trim().isEmpty()) ? fileId : "UNKNOWN_" + System.currentTimeMillis();
        this.filename = filename;
        this.size = size;
        this.progress = progress;
        this.status = status;
        this.mode = mode;
        this.totalPackets = totalPackets;
        this.receivedPackets = receivedPackets;
    }
    
    /**
     * Creation constructor for new outbound transfers
     */
    public TransferItem(String fileId, String filename, long size, int progress, String status, String mode, int totalPackets, int receivedPackets) {
        this.id = 0; // Assigned by SQLite
        this.fileId = fileId;
        this.filename = filename;
        this.size = size;
        this.progress = progress;
        this.status = status;
        this.mode = mode;
        this.totalPackets = totalPackets;
        this.receivedPackets = receivedPackets;
    }

    public long getId() { 
        return id; 
    }
    
    public void setId(long id) {
        this.id = id;
    }

    public String getFileId() { 
        return fileId; 
    }

    public void setFileId(String fileId) { 
        this.fileId = fileId; 
    }

    public String getFilename() { 
        return filename; 
    }
    
    public void setFilename(String filename) {
        this.filename = filename;
    }

    public long getSize() { 
        return size; 
    }
    
    public void setSize(long size) {
        this.size = size;
    }

    public int getProgress() { 
        return progress; 
    }

    public void setProgress(int progress) { 
        this.progress = progress; 
    }

    public String getStatus() { 
        return status; 
    }

    public void setStatus(String status) { 
        this.status = status; 
    }

    public String getMode() { 
        return mode; 
    }
    
    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getTotalPackets() { 
        return totalPackets; 
    }
    
    public void setTotalPackets(int totalPackets) {
        this.totalPackets = totalPackets;
    }

    public int getReceivedPackets() { 
        return receivedPackets; 
    }

    public void setReceivedPackets(int receivedPackets) { 
        this.receivedPackets = receivedPackets; 
    }
    
    public boolean isComplete() {
        return STATUS_COMPLETED.equalsIgnoreCase(status) || (receivedPackets >= totalPackets && totalPackets > 0);
    }
}