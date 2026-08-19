package com.example.database;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

@Entity(tableName = "messages")
public class MessageEntity {

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    public long id;

    @ColumnInfo(name = "sender")
    public String sender;

    @ColumnInfo(name = "recipient")
    public String recipient;

    @ColumnInfo(name = "body")
    public String body;

    @ColumnInfo(name = "timestamp")
    public long timestamp;

    @ColumnInfo(name = "type")
    public String type; // "SMS" or "MMS"

    @ColumnInfo(name = "status")
    public String status; // "PENDING", "SENT", "DELIVERED", "FAILED"

    @ColumnInfo(name = "sub_id")
    public int subId; // SIM subscription ID (-1 for default, subId for specific SIM)

    @ColumnInfo(name = "is_read")
    public boolean isRead;

    // Required default constructor for Room
    public MessageEntity() {
    }

    @Ignore
    public MessageEntity(long id, String sender, String recipient, String body, long timestamp, String type, String status, int subId, boolean isRead) {
        this.id = id;
        this.sender = sender;
        this.recipient = recipient;
        this.body = body;
        this.timestamp = timestamp;
        this.type = type;
        this.status = status;
        this.subId = subId;
        this.isRead = isRead;
    }

    @Ignore
    public MessageEntity(String sender, String recipient, String body, long timestamp, String type, String status, int subId) {
        this.sender = sender;
        this.recipient = recipient;
        this.body = body;
        this.timestamp = timestamp;
        this.type = type;
        this.status = status;
        this.subId = subId;
        this.isRead = true;
    }

    public boolean isOutgoing() {
        return "me".equalsIgnoreCase(sender) || "out".equalsIgnoreCase(type);
    }

    public String getFormattedTime() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a", Locale.getDefault());
            return sdf.format(new Date(timestamp));
        } catch (Exception e) {
            return "";
        }
    }
}