package com.example.models;

public class CallLogItem {
    private long id;
    private String number;
    private String name;
    private String type; // "INCOMING", "OUTGOING", "MISSED", "AUDIO_DATA", "DIALLED"
    private long duration; // seconds
    private long timestamp;

    public CallLogItem(long id, String number, String type, long duration, long timestamp) {
        this.id = id;
        this.number = number;
        this.name = "";
        this.type = type;
        this.duration = duration;
        this.timestamp = timestamp;
    }

    public CallLogItem(long id, String number, String name, String type, long duration, long timestamp) {
        this.id = id;
        this.number = number;
        this.name = (name != null) ? name.trim() : "";
        this.type = type;
        this.duration = duration;
        this.timestamp = timestamp;
    }

    public long getId() { 
        return id; 
    }

    public void setId(long id) { 
        this.id = id; 
    }

    public String getNumber() { 
        return number; 
    }

    public void setNumber(String number) { 
        this.number = number; 
    }

    public String getName() { 
        return name; 
    }

    public void setName(String name) { 
        this.name = (name != null) ? name.trim() : ""; 
    }

    public String getContactName() { 
        return name; 
    }

    public void setContactName(String contactName) { 
        this.name = (contactName != null) ? contactName.trim() : ""; 
    }

    public String getType() { 
        return type; 
    }

    public void setType(String type) { 
        this.type = type; 
    }

    public long getDuration() { 
        return duration; 
    }

    public void setDuration(long duration) { 
        this.duration = duration; 
    }

    public long getTimestamp() { 
        return timestamp; 
    }

    public void setTimestamp(long timestamp) { 
        this.timestamp = timestamp; 
    }

    public boolean hasContactName() {
        return name != null && !name.trim().isEmpty() && !name.equalsIgnoreCase(number);
    }

    public String getDisplayName() {
        return hasContactName() ? name : number;
    }
}