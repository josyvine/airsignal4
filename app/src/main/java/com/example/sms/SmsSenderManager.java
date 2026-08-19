package com.example.sms;

import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsManager;

import com.example.utils.AirLogger;

import java.util.ArrayList;

public class SmsSenderManager {

    private static final String TAG = "SmsSenderManager";
    public static final String ACTION_SMS_SENT = "com.example.ACTION_SMS_SENT";
    public static final String ACTION_SMS_DELIVERED = "com.example.ACTION_SMS_DELIVERED";

    public static void sendSms(Context context, String destinationNumber, String messageText) {
        sendSms(context, destinationNumber, messageText, -1);
    }

    public static void sendSms(Context context, String destinationNumber, String messageText, long messageId) {
        if (destinationNumber == null || destinationNumber.trim().isEmpty()) {
            AirLogger.e(TAG, "sendSms failed: empty destination number");
            return;
        }

        String cleanDestination = destinationNumber.replaceAll("[^0-9+]", "");
        if (cleanDestination.isEmpty()) {
            cleanDestination = destinationNumber.trim();
        }

        try {
            AirLogger.i(TAG, "Attempting to send SMS to " + cleanDestination + " (raw=" + destinationNumber + ", msgId=" + messageId + ", len=" + messageText.length() + ")");

            SmsManager smsManager = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    smsManager = context.getSystemService(SmsManager.class);
                }
            } catch (Exception e) {
                AirLogger.e(TAG, "getSystemService(SmsManager.class) exception: " + e.getMessage());
            }

            if (smsManager == null) {
                try {
                    int subId = SmsManager.getDefaultSmsSubscriptionId();
                    if (subId >= 0) {
                        smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
                        AirLogger.i(TAG, "Using SmsManager for default subscriptionId: " + subId);
                    }
                } catch (Exception e) {
                    AirLogger.e(TAG, "getSmsManagerForSubscriptionId exception: " + e.getMessage());
                }
            }

            if (smsManager == null) {
                smsManager = SmsManager.getDefault();
                AirLogger.i(TAG, "Using default SmsManager.getDefault()");
            }

            writeToSystemSent(context, cleanDestination, messageText, System.currentTimeMillis());

            int flag = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
            int baseReqCode = (int) (System.currentTimeMillis() & 0xfffffff);

            Intent sentIntent = new Intent(ACTION_SMS_SENT);
            sentIntent.putExtra("recipient", cleanDestination);
            sentIntent.putExtra("message_id", messageId);
            sentIntent.setPackage(context.getPackageName());

            Intent deliveredIntent = new Intent(ACTION_SMS_DELIVERED);
            deliveredIntent.putExtra("recipient", cleanDestination);
            deliveredIntent.putExtra("message_id", messageId);
            deliveredIntent.setPackage(context.getPackageName());

            if (messageText.length() > 160) {
                ArrayList<String> parts = smsManager.divideMessage(messageText);
                ArrayList<PendingIntent> sentPIs = new ArrayList<>();
                ArrayList<PendingIntent> deliveredPIs = new ArrayList<>();

                for (int i = 0; i < parts.size(); i++) {
                    PendingIntent partSentPI = PendingIntent.getBroadcast(context, baseReqCode + i * 2, sentIntent, flag);
                    PendingIntent partDeliveredPI = PendingIntent.getBroadcast(context, baseReqCode + i * 2 + 1, deliveredIntent, flag);
                    sentPIs.add(partSentPI);
                    deliveredPIs.add(partDeliveredPI);
                }

                smsManager.sendMultipartTextMessage(cleanDestination, null, parts, sentPIs, deliveredPIs);
                AirLogger.i(TAG, "Multipart SMS (" + parts.size() + " parts) handed to SmsManager for " + cleanDestination);
            } else {
                PendingIntent sentPI = PendingIntent.getBroadcast(context, baseReqCode, sentIntent, flag);
                PendingIntent deliveredPI = PendingIntent.getBroadcast(context, baseReqCode + 1, deliveredIntent, flag);
                smsManager.sendTextMessage(cleanDestination, null, messageText, sentPI, deliveredPI);
                AirLogger.i(TAG, "Single SMS handed to SmsManager for " + cleanDestination);
            }
        } catch (Exception e) {
            AirLogger.e(TAG, "Exception in sendSms to " + cleanDestination, e);
        }
    }

    private static void writeToSystemSent(Context context, String address, String body, long timestamp) {
        try {
            ContentValues values = new ContentValues();
            values.put(Telephony.Sms.ADDRESS, address);
            values.put(Telephony.Sms.BODY, body);
            values.put(Telephony.Sms.DATE, timestamp);
            values.put(Telephony.Sms.READ, 1);
            values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT);
            context.getContentResolver().insert(Telephony.Sms.Sent.CONTENT_URI, values);
            AirLogger.i(TAG, "Successfully wrote sent SMS to system provider Sent box");
        } catch (Exception e) {
            AirLogger.e(TAG, "Could not write sent SMS to system provider: " + e.getMessage());
        }
    }
}
