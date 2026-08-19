package com.example.receivers;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.example.activities.ConversationActivity;
import com.example.database.AppDatabase;
import com.example.database.DatabaseHelper;
import com.example.database.MessageEntity;
import com.example.database.TransferDatabase;
import com.example.models.DataPacket;
import com.example.models.Message;
import com.example.utils.AirLogger;
import com.example.utils.DataPacketManager;

public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsReceiver";
    private static final String NOTIFICATION_CHANNEL_ID = "sms_incoming_channel";

    // Deduplication tracking to prevent double notifications & double database entries
    private static String lastMessageKey = "";
    private static long lastMessageTime = 0;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        AirLogger.i(TAG, "SMS onReceive action: " + action);

        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(action) ||
                Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(action)) {

            SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (messages == null || messages.length == 0) {
                AirLogger.e(TAG, "Received SMS intent but getMessagesFromIntent returned null or empty");
                return;
            }

            StringBuilder bodyBuilder = new StringBuilder();
            String sender = "";
            long timestamp = System.currentTimeMillis();

            for (SmsMessage sms : messages) {
                if (sms != null) {
                    if (sender.isEmpty() && sms.getOriginatingAddress() != null) {
                        sender = sms.getOriginatingAddress();
                    }
                    if (sms.getTimestampMillis() > 0) {
                        timestamp = sms.getTimestampMillis();
                    }
                    if (sms.getMessageBody() != null) {
                        bodyBuilder.append(sms.getMessageBody());
                    }
                }
            }

            String body = bodyBuilder.toString();

            // Deduplicate simultaneous SMS_DELIVER and SMS_RECEIVED broadcasts (within 3 seconds)
            String currentKey = sender + "|" + body;
            long now = System.currentTimeMillis();
            if (currentKey.equals(lastMessageKey) && (now - lastMessageTime < 3000)) {
                AirLogger.i(TAG, "Duplicate SMS broadcast ignored for sender: " + sender);
                return;
            }
            lastMessageKey = currentKey;
            lastMessageTime = now;

            AirLogger.i(TAG, "Incoming SMS from " + sender + " (len=" + body.length() + ")");

            if (body.startsWith("AIR_START|")) {
                // SMS DATA PACKET MODE
                DataPacket packet = DataPacketManager.parseSmsPacket(body);
                if (packet != null) {
                    TransferDatabase.getInstance(context).savePacket(packet);
                    AirLogger.i(TAG, "Received AirSignal Data Packet #" + packet.getPacketIndex());
                    Toast.makeText(context, "AirSignal Data Packet #" + packet.getPacketIndex() + " received!", Toast.LENGTH_SHORT).show();
                }
            } else {
                // NORMAL CHAT MODE
                // 1. Save to SQLite DatabaseHelper
                Message msg = new Message(0, sender, "me", body, timestamp, "SMS", "DELIVERED");
                long id = DatabaseHelper.getInstance(context).insertMessage(msg);
                AirLogger.i(TAG, "Received regular SMS from " + sender + ", saved to SQLite DB with msgId=" + id);

                // 2. Save to Room AppDatabase
                final String finalSender = sender;
                final String finalBody = body;
                final long finalTimestamp = timestamp;
                final long finalId = id;

                new Thread(() -> {
                    try {
                        MessageEntity entity = new MessageEntity(finalSender, "me", finalBody, finalTimestamp, "SMS", "DELIVERED", -1);
                        entity.isRead = false;
                        AppDatabase.getInstance(context).messageDao().insertMessage(entity);
                        AirLogger.i(TAG, "Saved incoming SMS to Room AppDatabase for " + finalSender);
                    } catch (Exception e) {
                        AirLogger.e(TAG, "Failed saving incoming SMS to Room AppDatabase", e);
                    }
                }).start();

                // 3. Write to System Inbox & Show Notification
                writeToSystemInbox(context, sender, body, timestamp);
                showSmsNotification(context, sender, body);

                // 4. Broadcast local refresh intent to active UI screens
                try {
                    Intent notifyIntent = new Intent("com.example.ACTION_SMS_RECEIVED");
                    notifyIntent.setPackage(context.getPackageName());
                    notifyIntent.putExtra("sender", sender);
                    notifyIntent.putExtra("body", body);
                    notifyIntent.putExtra("message_id", finalId);
                    context.sendBroadcast(notifyIntent);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void writeToSystemInbox(Context context, String sender, String body, long timestamp) {
        try {
            ContentValues values = new ContentValues();
            values.put(Telephony.Sms.ADDRESS, sender);
            values.put(Telephony.Sms.BODY, body);
            values.put(Telephony.Sms.DATE, timestamp);
            values.put(Telephony.Sms.READ, 0);
            values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX);
            context.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, values);
            AirLogger.i(TAG, "Successfully wrote incoming SMS to system provider Inbox");
        } catch (Exception e) {
            AirLogger.e(TAG, "Could not write incoming SMS to system provider: " + e.getMessage());
        }
    }

    private void showSmsNotification(Context context, String sender, String body) {
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "Incoming Messages",
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Notifications for incoming SMS messages");
                channel.enableVibration(true);
                channel.enableLights(true);
                manager.createNotificationChannel(channel);
            }

            Intent targetIntent = new Intent(context, ConversationActivity.class);
            targetIntent.putExtra("target_recipient", sender);
            targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    (int) (System.currentTimeMillis() & 0xfffffff),
                    targetIntent,
                    flags
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.sym_action_chat)
                    .setContentTitle("New SMS from " + sender)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent);

            manager.notify((int) (System.currentTimeMillis() % 10000), builder.build());
            AirLogger.i(TAG, "Posted incoming SMS notification for " + sender);
        } catch (Exception e) {
            AirLogger.e(TAG, "Failed to post SMS notification", e);
        }
    }
}