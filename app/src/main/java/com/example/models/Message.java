package com.example.models;

public class Message {
    private long id;
    private String sender;
    private String receiver;
    private String message;
    private long timestamp;
    private String type; // "SMS", "SMS_DATA", "AUDIO_DATA"
    private String status; // "SENDING", "SENT", "DELIVERED", "FAILED"

    public Message(long id, String sender, String receiver, String message, long timestamp, String type, String status) {
        this.id = id;
        this.sender = sender;
        this.receiver = receiver;
        this.message = message;
        this.timestamp = timestamp;
        this.type = type;
        this.status = status;
    }

    public long getId() { return id; }
    public String getSender() { return sender; }
    public String getReceiver() { return receiver; }
    public String getMessage() { return message; }
    public long getTimestamp() { return timestamp; }
    public String getType() { return type; }
    public String getStatus() { return status; }

    public void setId(long id) { this.id = id; }
    public void setSender(String sender) { this.sender = sender; }
    public void setReceiver(String receiver) { this.receiver = receiver; }
    public void setMessage(String message) { this.message = message; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public void setType(String type) { this.type = type; }
    public void setStatus(String status) { this.status = status; }
}
