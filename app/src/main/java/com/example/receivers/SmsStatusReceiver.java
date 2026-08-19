package com.example.receivers;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.widget.Toast;

import com.example.database.AppDatabase;
import com.example.database.DatabaseHelper;
import com.example.utils.AirLogger;

public class SmsStatusReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsStatusReceiver";
    public static final String ACTION_MESSAGE_STATUS_UPDATED = "com.example.ACTION_MESSAGE_STATUS_UPDATED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        String recipient = intent.getStringExtra("recipient");
        long messageId = intent.getLongExtra("message_id", -1);

        if ("com.example.ACTION_SMS_SENT".equals(action)) {
            int resultCode = getResultCode();
            String status;
            String errorReason = "";

            switch (resultCode) {
                case Activity.RESULT_OK:
                    status = "SENT";
                    errorReason = "SMS Sent successfully";
                    break;
                case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                    status = "FAILED";
                    errorReason = "RESULT_ERROR_GENERIC_FAILURE (Check carrier network, SIM, or SMS center settings)";
                    break;
                case SmsManager.RESULT_ERROR_NO_SERVICE:
                    status = "FAILED";
                    errorReason = "RESULT_ERROR_NO_SERVICE (No cellular signal available)";
                    break;
                case SmsManager.RESULT_ERROR_NULL_PDU:
                    status = "FAILED";
                    errorReason = "RESULT_ERROR_NULL_PDU (Invalid SMS PDU)";
                    break;
                case SmsManager.RESULT_ERROR_RADIO_OFF:
                    status = "FAILED";
                    errorReason = "RESULT_ERROR_RADIO_OFF (Airplane mode or radio turned off)";
                    break;
                case SmsManager.RESULT_ERROR_LIMIT_EXCEEDED:
                    status = "FAILED";
                    errorReason = "RESULT_ERROR_LIMIT_EXCEEDED (Queue limit exceeded)";
                    break;
                default:
                    status = "FAILED";
                    errorReason = "Unknown Result Code: " + resultCode;
                    break;
            }

            if (resultCode == Activity.RESULT_OK) {
                AirLogger.i(TAG, "SMS SENT SUCCESS to " + recipient + " (msgId=" + messageId + ")");
            } else {
                AirLogger.e(TAG, "SMS SEND FAILED to " + recipient + " (msgId=" + messageId + "). Reason: " + errorReason);
                try {
                    Toast.makeText(context, "SMS Send Failed: " + errorReason, Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {
                }
            }

            if (messageId != -1) {
                // 1. Update SQLite DatabaseHelper
                DatabaseHelper.getInstance(context).updateMessageStatus(messageId, status);

                // 2. Update Room AppDatabase
                final String finalStatus = status;
                new Thread(() -> {
                    try {
                        AppDatabase.getInstance(context).messageDao().updateStatus(messageId, finalStatus);
                    } catch (Exception e) {
                        AirLogger.e(TAG, "Failed updating status in Room AppDatabase", e);
                    }
                }).start();

                // 3. Notify active Conversation UI to refresh live bubbles
                notifyUiStatusUpdate(context, messageId, status, recipient);
            }

        } else if ("com.example.ACTION_SMS_DELIVERED".equals(action)) {
            int resultCode = getResultCode();
            AirLogger.i(TAG, "SMS DELIVERED callback to " + recipient + " (msgId=" + messageId + "), resultCode=" + resultCode);
            if (messageId != -1 && resultCode == Activity.RESULT_OK) {
                // 1. Update SQLite DatabaseHelper
                DatabaseHelper.getInstance(context).updateMessageStatus(messageId, "DELIVERED");

                // 2. Update Room AppDatabase
                new Thread(() -> {
                    try {
                        AppDatabase.getInstance(context).messageDao().updateStatus(messageId, "DELIVERED");
                    } catch (Exception e) {
                        AirLogger.e(TAG, "Failed updating status in Room AppDatabase", e);
                    }
                }).start();

                // 3. Notify active Conversation UI to refresh live bubbles
                notifyUiStatusUpdate(context, messageId, "DELIVERED", recipient);
            }
        }
    }

    private void notifyUiStatusUpdate(Context context, long messageId, String status, String recipient) {
        try {
            Intent updateIntent = new Intent(ACTION_MESSAGE_STATUS_UPDATED);
            updateIntent.setPackage(context.getPackageName());
            updateIntent.putExtra("message_id", messageId);
            updateIntent.putExtra("status", status);
            updateIntent.putExtra("recipient", recipient);
            context.sendBroadcast(updateIntent);
        } catch (Exception e) {
            AirLogger.e(TAG, "Failed to broadcast status update intent", e);
        }
    }
}