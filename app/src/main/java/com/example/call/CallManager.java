package com.example.call;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.activities.InCallActivity;
import com.example.utils.AirLogger;

public class CallManager {

    private static final String TAG = "CallManager";

    public static void placeCall(Context context, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            Toast.makeText(context, "Please enter a valid phone number", Toast.LENGTH_SHORT).show();
            AirLogger.e(TAG, "placeCall failed: empty phone number");
            return;
        }

        String cleanNum = phoneNumber.trim();
        AirLogger.i(TAG, "Initiating call to " + cleanNum);

        boolean callPlaced = false;

        // Try direct cellular call if permission is granted
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                Intent callIntent = new Intent(Intent.ACTION_CALL);
                callIntent.setData(Uri.parse("tel:" + Uri.encode(cleanNum)));
                if (!(context instanceof Activity)) {
                    callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(callIntent);
                callPlaced = true;
                AirLogger.i(TAG, "ACTION_CALL intent started successfully for " + cleanNum);
            } catch (Exception e) {
                AirLogger.e(TAG, "Failed to execute ACTION_CALL", e);
            }
        } else {
            AirLogger.e(TAG, "CALL_PHONE permission not granted, requesting...");
            if (context instanceof Activity) {
                ActivityCompat.requestPermissions((Activity) context, new String[]{Manifest.permission.CALL_PHONE}, 101);
            }
        }

        if (!callPlaced) {
            // Fallback to in-app calling activity or ACTION_DIAL
            try {
                placeInAppCall(context, cleanNum);
                AirLogger.i(TAG, "Fallback placeInAppCall started for " + cleanNum);
            } catch (Exception e) {
                AirLogger.e(TAG, "Failed to execute fallback placeInAppCall", e);
            }
        }
    }

    public static void placeInAppCall(Context context, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return;
        }
        try {
            AirLogger.i(TAG, "placeInAppCall: Opening InCallActivity for " + phoneNumber);
            Intent inCallIntent = new Intent(context, InCallActivity.class);
            inCallIntent.putExtra("phone_number", phoneNumber);
            inCallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            context.startActivity(inCallIntent);
        } catch (Exception e) {
            AirLogger.e(TAG, "placeInAppCall error", e);
        }
    }
}
