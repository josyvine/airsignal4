package com.example.utils;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.widget.Toast;

public class SmsRoleManager {

    public static final int REQUEST_CODE_DEFAULT_SMS = 1001;
    public static final int REQUEST_CODE_DEFAULT_DIALER = 1002;

    public static boolean isDefaultSmsApp(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
            if (roleManager != null && roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                return true;
            }
        }
        String defaultSmsPkg = Telephony.Sms.getDefaultSmsPackage(context);
        return context.getPackageName().equals(defaultSmsPkg);
    }

    public static void requestDefaultSmsRole(Activity activity) {
        try {
            if (isDefaultSmsApp(activity)) {
                Toast.makeText(activity, "AirSignal is already the Default SMS app", Toast.LENGTH_SHORT).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager roleManager = (RoleManager) activity.getSystemService(Context.ROLE_SERVICE);
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS);
                    activity.startActivityForResult(intent, REQUEST_CODE_DEFAULT_SMS);
                    return;
                }
            }
            Intent intent = new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
            intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, activity.getPackageName());
            activity.startActivity(intent);
        } catch (Exception e) {
            openSystemDefaultAppsSettings(activity);
        }
    }

    public static boolean isDefaultDialerApp(Context context) {
        TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        if (telecomManager != null) {
            String defaultDialer = telecomManager.getDefaultDialerPackage();
            if (context.getPackageName().equals(defaultDialer)) {
                return true;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
            return roleManager != null && roleManager.isRoleHeld(RoleManager.ROLE_DIALER);
        }
        return false;
    }

    public static void requestDefaultDialerRole(Activity activity) {
        try {
            if (isDefaultDialerApp(activity)) {
                Toast.makeText(activity, "AirSignal is already the Default Phone app", Toast.LENGTH_SHORT).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager roleManager = (RoleManager) activity.getSystemService(Context.ROLE_SERVICE);
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                    activity.startActivityForResult(intent, REQUEST_CODE_DEFAULT_DIALER);
                    return;
                }
            }
            Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
            intent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, activity.getPackageName());
            activity.startActivity(intent);
        } catch (Exception e) {
            openSystemDefaultAppsSettings(activity);
        }
    }

    public static boolean hasOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }

    public static void requestOverlayPermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(activity, "Unable to open Overlay Settings", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public static void openSystemDefaultAppsSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                context.startActivity(intent);
            } catch (Exception ignored) {
            }
        }
    }
}


