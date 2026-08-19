package com.example.database;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.example.models.CallLogItem;
import com.example.models.Message;
import com.example.models.User;

import java.util.ArrayList;
import java.util.List;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "airsignal.db";
    private static final int DATABASE_VERSION = 1;

    // Table Names
    public static final String TABLE_USERS = "users";
    public static final String TABLE_MESSAGES = "messages";
    public static final String TABLE_CALLS = "calls";

    // Common columns
    public static final String KEY_ID = "id";

    // Users Columns
    public static final String KEY_USER_NAME = "name";
    public static final String KEY_USER_PHONE = "phone";
    public static final String KEY_USER_PHOTO = "photo";

    // Messages Columns
    public static final String KEY_MSG_SENDER = "sender";
    public static final String KEY_MSG_RECEIVER = "receiver";
    public static final String KEY_MSG_BODY = "message";
    public static final String KEY_MSG_TIME = "timestamp";
    public static final String KEY_MSG_TYPE = "type";
    public static final String KEY_MSG_STATUS = "status";

    // Calls Columns
    public static final String KEY_CALL_NUMBER = "number";
    public static final String KEY_CALL_TYPE = "type";
    public static final String KEY_CALL_DURATION = "duration";
    public static final String KEY_CALL_TIME = "time";

    private static DatabaseHelper instance;
    private final Context mContext;

    public static synchronized DatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.mContext = context != null ? context.getApplicationContext() : null;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_USERS_TABLE = "CREATE TABLE " + TABLE_USERS + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_USER_NAME + " TEXT,"
                + KEY_USER_PHONE + " TEXT UNIQUE,"
                + KEY_USER_PHOTO + " TEXT" + ")";

        String CREATE_MESSAGES_TABLE = "CREATE TABLE " + TABLE_MESSAGES + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_MSG_SENDER + " TEXT,"
                + KEY_MSG_RECEIVER + " TEXT,"
                + KEY_MSG_BODY + " TEXT,"
                + KEY_MSG_TIME + " INTEGER,"
                + KEY_MSG_TYPE + " TEXT,"
                + KEY_MSG_STATUS + " TEXT" + ")";

        String CREATE_CALLS_TABLE = "CREATE TABLE " + TABLE_CALLS + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_CALL_NUMBER + " TEXT,"
                + KEY_CALL_TYPE + " TEXT,"
                + KEY_CALL_DURATION + " INTEGER,"
                + KEY_CALL_TIME + " INTEGER" + ")";

        db.execSQL(CREATE_USERS_TABLE);
        db.execSQL(CREATE_MESSAGES_TABLE);
        db.execSQL(CREATE_CALLS_TABLE);

        seedInitialData(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_USERS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_MESSAGES);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CALLS);
        onCreate(db);
    }

    private void seedInitialData(SQLiteDatabase db) {
        long now = System.currentTimeMillis();

        // Seed Users
        ContentValues user1 = new ContentValues();
        user1.put(KEY_USER_NAME, "Alice Vance");
        user1.put(KEY_USER_PHONE, "+15550192831");
        db.insert(TABLE_USERS, null, user1);

        ContentValues user2 = new ContentValues();
        user2.put(KEY_USER_NAME, "Bob Miller");
        user2.put(KEY_USER_PHONE, "+15550148822");
        db.insert(TABLE_USERS, null, user2);

        // Seed Messages
        ContentValues msg1 = new ContentValues();
        msg1.put(KEY_MSG_SENDER, "+15550192831");
        msg1.put(KEY_MSG_RECEIVER, "me");
        msg1.put(KEY_MSG_BODY, "AirSignal packet test complete. Offline channel active!");
        msg1.put(KEY_MSG_TIME, now - 3600000);
        msg1.put(KEY_MSG_TYPE, "SMS");
        msg1.put(KEY_MSG_STATUS, "DELIVERED");
        db.insert(TABLE_MESSAGES, null, msg1);

        ContentValues msg2 = new ContentValues();
        msg2.put(KEY_MSG_SENDER, "me");
        msg2.put(KEY_MSG_RECEIVER, "+15550192831");
        msg2.put(KEY_MSG_BODY, "Awesome! Switching to SMS Data Mode for packet chunking.");
        msg2.put(KEY_MSG_TIME, now - 1800000);
        msg2.put(KEY_MSG_TYPE, "SMS_DATA");
        msg2.put(KEY_MSG_STATUS, "SENT");
        db.insert(TABLE_MESSAGES, null, msg2);

        // Seed Call Log
        ContentValues call1 = new ContentValues();
        call1.put(KEY_CALL_NUMBER, "+15550192831");
        call1.put(KEY_CALL_TYPE, "AUDIO_DATA");
        call1.put(KEY_CALL_DURATION, 145);
        call1.put(KEY_CALL_TIME, now - 7200000);
        db.insert(TABLE_CALLS, null, call1);
    }

    public long insertMessage(Message message) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KEY_MSG_SENDER, message.getSender());
        values.put(KEY_MSG_RECEIVER, message.getReceiver());
        values.put(KEY_MSG_BODY, message.getMessage());
        values.put(KEY_MSG_TIME, message.getTimestamp());
        values.put(KEY_MSG_TYPE, message.getType());
        values.put(KEY_MSG_STATUS, message.getStatus());
        long id = db.insert(TABLE_MESSAGES, null, values);
        notifyMessageChanged();
        return id;
    }

    public void updateMessageStatus(long id, String status) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KEY_MSG_STATUS, status);
        db.update(TABLE_MESSAGES, values, KEY_ID + "=?", new String[]{String.valueOf(id)});
        notifyMessageChanged();
    }

    public int deleteMessage(long id) {
        SQLiteDatabase db = this.getWritableDatabase();
        int rowsDeleted = db.delete(TABLE_MESSAGES, KEY_ID + "=?", new String[]{String.valueOf(id)});
        if (rowsDeleted > 0) {
            notifyMessageChanged();
        }
        return rowsDeleted;
    }

    public int deleteMessagesByNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) return 0;
        SQLiteDatabase db = this.getWritableDatabase();

        String raw = phoneNumber.trim();
        String cleaned = raw.replaceAll("[^0-9]", "");
        if (cleaned.length() > 10) {
            cleaned = cleaned.substring(cleaned.length() - 10);
        }

        int rowsDeleted = db.delete(
                TABLE_MESSAGES,
                KEY_MSG_SENDER + "=? OR " + KEY_MSG_RECEIVER + "=? OR " +
                KEY_MSG_SENDER + " LIKE ? OR " + KEY_MSG_RECEIVER + " LIKE ?",
                new String[]{raw, raw, "%" + cleaned, "%" + cleaned}
        );

        if (rowsDeleted > 0) {
            notifyMessageChanged();
        }
        return rowsDeleted;
    }

    public int deleteAllMessages() {
        SQLiteDatabase db = this.getWritableDatabase();
        int rowsDeleted = db.delete(TABLE_MESSAGES, null, null);
        if (rowsDeleted > 0) {
            notifyMessageChanged();
        }
        return rowsDeleted;
    }

    private void notifyMessageChanged() {
        if (mContext != null) {
            try {
                Intent intent = new Intent("com.example.ACTION_SMS_RECEIVED");
                intent.setPackage(mContext.getPackageName());
                mContext.sendBroadcast(intent);
            } catch (Exception ignored) {
            }
        }
    }

    public List<Message> getAllMessages() {
        List<Message> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_MESSAGES + " ORDER BY " + KEY_MSG_TIME + " ASC", null);
        if (cursor.moveToFirst()) {
            do {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_ID));
                String sender = cursor.getString(cursor.getColumnIndexOrThrow(KEY_MSG_SENDER));
                String receiver = cursor.getString(cursor.getColumnIndexOrThrow(KEY_MSG_RECEIVER));
                String body = cursor.getString(cursor.getColumnIndexOrThrow(KEY_MSG_BODY));
                long time = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_MSG_TIME));
                String type = cursor.getString(cursor.getColumnIndexOrThrow(KEY_MSG_TYPE));
                String status = cursor.getString(cursor.getColumnIndexOrThrow(KEY_MSG_STATUS));

                list.add(new Message(id, sender, receiver, body, time, type, status));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return list;
    }

    public List<User> getAllUsers() {
        List<User> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_USERS, null);
        if (cursor.moveToFirst()) {
            do {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_ID));
                String name = cursor.getString(cursor.getColumnIndexOrThrow(KEY_USER_NAME));
                String phone = cursor.getString(cursor.getColumnIndexOrThrow(KEY_USER_PHONE));
                String photo = cursor.getString(cursor.getColumnIndexOrThrow(KEY_USER_PHOTO));

                list.add(new User(id, name, phone, photo));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return list;
    }

    public long insertUser(User user) {
        if (user == null) return -1;
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KEY_USER_NAME, user.getName());
        values.put(KEY_USER_PHONE, user.getPhone());
        values.put(KEY_USER_PHOTO, user.getPhoto());
        return db.insertWithOnConflict(TABLE_USERS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<CallLogItem> getAllCalls() {
        List<CallLogItem> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_CALLS + " ORDER BY " + KEY_CALL_TIME + " DESC", null);
        if (cursor.moveToFirst()) {
            do {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_ID));
                String number = cursor.getString(cursor.getColumnIndexOrThrow(KEY_CALL_NUMBER));
                String type = cursor.getString(cursor.getColumnIndexOrThrow(KEY_CALL_TYPE));
                long duration = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_CALL_DURATION));
                long time = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_CALL_TIME));

                list.add(new CallLogItem(id, number, type, duration, time));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return list;
    }

    public long insertCall(CallLogItem item) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KEY_CALL_NUMBER, item.getNumber());
        values.put(KEY_CALL_TYPE, item.getType());
        values.put(KEY_CALL_DURATION, item.getDuration());
        values.put(KEY_CALL_TIME, item.getTimestamp());
        return db.insert(TABLE_CALLS, null, values);
    }

    public int deleteCall(long id) {
        SQLiteDatabase db = this.getWritableDatabase();
        return db.delete(TABLE_CALLS, KEY_ID + "=?", new String[]{String.valueOf(id)});
    }
}