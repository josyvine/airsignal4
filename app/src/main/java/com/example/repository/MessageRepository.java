package com.example.repository;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;

import androidx.lifecycle.LiveData;

import com.example.database.AppDatabase;
import com.example.database.MessageDao;
import com.example.database.MessageEntity;
import com.example.utils.AirLogger;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MessageRepository {

    private static final String TAG = "MessageRepository";
    private final MessageDao messageDao;
    private final Context context;
    private final ExecutorService executorService;

    public interface OnMessageInsertedCallback {
        void onInserted(long messageId);
    }

    public MessageRepository(Context context) {
        this.context = context.getApplicationContext();
        AppDatabase db = AppDatabase.getInstance(this.context);
        this.messageDao = db.messageDao();
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public LiveData<List<MessageEntity>> getMessagesForConversationLiveData(String recipientAddress) {
        // Sync system SMS for this recipient in background to ensure all history is present
        syncSystemSmsForRecipient(recipientAddress);
        return messageDao.getMessagesLiveDataForConversation(recipientAddress);
    }

    public void insertMessage(MessageEntity message, OnMessageInsertedCallback callback) {
        executorService.execute(() -> {
            try {
                long id = messageDao.insertMessage(message);
                AirLogger.i(TAG, "Inserted message to Room DB with id=" + id);
                if (callback != null) {
                    callback.onInserted(id);
                }
            } catch (Exception e) {
                AirLogger.e(TAG, "Failed to insert message into Room DB", e);
            }
        });
    }

    public void updateStatus(long messageId, String status) {
        executorService.execute(() -> {
            try {
                messageDao.updateStatus(messageId, status);
                AirLogger.i(TAG, "Updated message status to " + status + " for msgId=" + messageId);
            } catch (Exception e) {
                AirLogger.e(TAG, "Failed to update message status", e);
            }
        });
    }

    public void markAsRead(String recipientAddress) {
        executorService.execute(() -> {
            try {
                messageDao.markConversationAsRead(recipientAddress);
            } catch (Exception e) {
                AirLogger.e(TAG, "Failed to mark conversation as read", e);
            }
        });
    }

    /**
     * Reads system SMS provider (Inbox & Sent) for the specific recipient address and caches into local Room database.
     */
    public void syncSystemSmsForRecipient(String recipientAddress) {
        if (recipientAddress == null || recipientAddress.trim().isEmpty()) return;

        executorService.execute(() -> {
            try {
                ContentResolver contentResolver = context.getContentResolver();
                Uri smsUri = Telephony.Sms.CONTENT_URI;
                String[] projection = new String[]{
                        Telephony.Sms._ID,
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE,
                        Telephony.Sms.TYPE
                };

                String selection = Telephony.Sms.ADDRESS + " = ? OR " + Telephony.Sms.ADDRESS + " = ?";
                String[] selectionArgs = new String[]{recipientAddress, recipientAddress.replace("+91", "").trim()};
                String sortOrder = Telephony.Sms.DATE + " ASC";

                Cursor cursor = contentResolver.query(smsUri, projection, selection, selectionArgs, sortOrder);

                if (cursor != null && cursor.moveToFirst()) {
                    int bodyIndex = cursor.getColumnIndex(Telephony.Sms.BODY);
                    int dateIndex = cursor.getColumnIndex(Telephony.Sms.DATE);
                    int typeIndex = cursor.getColumnIndex(Telephony.Sms.TYPE);
                    int addressIndex = cursor.getColumnIndex(Telephony.Sms.ADDRESS);

                    do {
                        String body = cursor.getString(bodyIndex);
                        long date = cursor.getLong(dateIndex);
                        int type = cursor.getInt(typeIndex);
                        String addr = cursor.getString(addressIndex);

                        String sender = (type == Telephony.Sms.MESSAGE_TYPE_INBOX) ? addr : "me";
                        String recipient = (type == Telephony.Sms.MESSAGE_TYPE_INBOX) ? "me" : addr;
                        String status = (type == Telephony.Sms.MESSAGE_TYPE_SENT) ? "SENT" : "DELIVERED";

                        MessageEntity entity = new MessageEntity(sender, recipient, body, date, "SMS", status, -1);
                        entity.isRead = true;

                        messageDao.insertMessage(entity);
                    } while (cursor.moveToNext());

                    cursor.close();
                    AirLogger.i(TAG, "System SMS sync complete for " + recipientAddress);
                }
            } catch (Exception e) {
                AirLogger.e(TAG, "Failed syncing system SMS provider for address: " + recipientAddress, e);
            }
        });
    }
}