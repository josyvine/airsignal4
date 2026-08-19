package com.example.services;

import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.text.TextUtils;

import com.example.database.DatabaseHelper;
import com.example.models.Message;
import com.example.utils.AirLogger;

import java.util.ArrayList;

public class SmsService extends Service {

    private static final String TAG = "SmsService";
    public static final String ACTION_RESPOND_VIA_MESSAGE = "android.intent.action.RESPOND_VIA_MESSAGE";
    public static final String ACTION_SEND_SMS = "com.example.services.ACTION_SEND_SMS";
    public static final String EXTRA_RECIPIENT = "extra_recipient";
    public static final String EXTRA_MESSAGE = "extra_message";
    public static final String EXTRA_SUB_ID = "extra_sub_id";
    public static final String EXTRA_MSG_ID = "extra_msg_id";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            AirLogger.i(TAG, "SmsService onStartCommand action: " + action);

            if (ACTION_RESPOND_VIA_MESSAGE.equals(action)) {
                handleRespondViaMessage(intent);
            } else if (ACTION_SEND_SMS.equals(action)) {
                String recipient = intent.getStringExtra(EXTRA_RECIPIENT);
                String message = intent.getStringExtra(EXTRA_MESSAGE);
                int subId = intent.getIntExtra(EXTRA_SUB_ID, -1);
                long msgId = intent.getLongExtra(EXTRA_MSG_ID, -1);

                if (!TextUtils.isEmpty(recipient) && !TextUtils.isEmpty(message)) {
                    sendSms(this, recipient, message, subId, msgId);
                }
            }
        }
        return START_STICKY;
    }

    private void handleRespondViaMessage(Intent intent) {
        Uri uri = intent.getData();
        if (uri == null) return;

        String recipient = uri.getSchemeSpecificPart();
        String message = intent.getStringExtra(Intent.EXTRA_TEXT);

        if (!TextUtils.isEmpty(recipient) && !TextUtils.isEmpty(message)) {
            AirLogger.i(TAG, "Responding via message to: " + recipient);
            Message msg = new Message(0, "me", recipient, message, System.currentTimeMillis(), "SMS", "PENDING");
            long id = DatabaseHelper.getInstance(this).insertMessage(msg);
            sendSms(this, recipient, message, -1, id);
        }
    }

    /**
     * Sends SMS using specified Subscription ID (SIM slot) to prevent RESULT_ERROR_GENERIC_FAILURE on Multi-SIM devices.
     */
    public static void sendSms(Context context, String recipient, String messageText, int subId, long messageId) {
        if (TextUtils.isEmpty(recipient) || TextUtils.isEmpty(messageText)) {
            AirLogger.e(TAG, "Cannot send SMS: recipient or message text is empty");
            return;
        }

        try {
            SmsManager smsManager;
            if (subId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                smsManager = context.getSystemService(SmsManager.class).createForSubscriptionId(subId);
                AirLogger.i(TAG, "Using SmsManager via createForSubscriptionId, subId=" + subId);
            } else if (subId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
                AirLogger.i(TAG, "Using SmsManager via getSmsManagerForSubscriptionId, subId=" + subId);
            } else {
                smsManager = SmsManager.getDefault();
                AirLogger.i(TAG, "Using default SmsManager");
            }

            Intent sentIntent = new Intent("com.example.ACTION_SMS_SENT");
            sentIntent.setPackage(context.getPackageName());
            sentIntent.putExtra("recipient", recipient);
            sentIntent.putExtra("message_id", messageId);

            Intent deliveredIntent = new Intent("com.example.ACTION_SMS_DELIVERED");
            deliveredIntent.setPackage(context.getPackageName());
            deliveredIntent.putExtra("recipient", recipient);
            deliveredIntent.putExtra("message_id", messageId);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent sentPI = PendingIntent.getBroadcast(
                    context,
                    (int) (System.currentTimeMillis() & 0xfffffff),
                    sentIntent,
                    flags
            );

            PendingIntent deliveredPI = PendingIntent.getBroadcast(
                    context,
                    (int) ((System.currentTimeMillis() + 1) & 0xfffffff),
                    deliveredIntent,
                    flags
            );

            ArrayList<String> parts = smsManager.divideMessage(messageText);

            if (parts.size() > 1) {
                ArrayList<PendingIntent> sentIntents = new ArrayList<>();
                ArrayList<PendingIntent> deliveredIntents = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) {
                    sentIntents.add(sentPI);
                    deliveredIntents.add(deliveredPI);
                }
                smsManager.sendMultipartTextMessage(recipient, null, parts, sentIntents, deliveredIntents);
                AirLogger.i(TAG, "Sent multipart SMS (" + parts.size() + " parts) to " + recipient);
            } else {
                smsManager.sendTextMessage(recipient, null, messageText, sentPI, deliveredPI);
                AirLogger.i(TAG, "Sent single SMS to " + recipient);
            }

            writeToSystemSent(context, recipient, messageText, System.currentTimeMillis());

        } catch (Exception e) {
            AirLogger.e(TAG, "Exception while sending SMS to " + recipient, e);
            if (messageId != -1) {
                DatabaseHelper.getInstance(context).updateMessageStatus(messageId, "FAILED");
            }
        }
    }

    private static void writeToSystemSent(Context context, String recipient, String body, long timestamp) {
        try {
            ContentValues values = new ContentValues();
            values.put(Telephony.Sms.ADDRESS, recipient);
            values.put(Telephony.Sms.BODY, body);
            values.put(Telephony.Sms.DATE, timestamp);
            values.put(Telephony.Sms.READ, 1);
            values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT);
            context.getContentResolver().insert(Telephony.Sms.Sent.CONTENT_URI, values);
            AirLogger.i(TAG, "Successfully wrote outgoing SMS to system provider Sent box");
        } catch (Exception e) {
            AirLogger.e(TAG, "Could not write outgoing SMS to system provider: " + e.getMessage());
        }
    }
}