package com.example.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

import androidx.core.app.NotificationCompat;

import com.example.R;
import com.example.activities.InCallActivity;
import com.example.utils.AirLogger;

public class AirSignalInCallService extends InCallService {

    private static final String TAG = "InCallService";
    private static final String CHANNEL_ID = "airsignal_incall_channel";
    private static final int NOTIFICATION_ID = 2001;

    private static Call activeCall;
    private static AirSignalInCallService instance;

    public static Call getActiveCall() {
        return activeCall;
    }

    public static void disconnectActiveCall() {
        if (activeCall != null) {
            try {
                AirLogger.i(TAG, "User requested disconnect of active call");
                activeCall.disconnect();
            } catch (Exception e) {
                AirLogger.e(TAG, "Error disconnecting active call", e);
            }
            activeCall = null;
        }
    }

    public static void setSpeakerphone(boolean on) {
        if (instance != null) {
            try {
                AirLogger.i(TAG, "Setting speakerphone=" + on);
                instance.setAudioRoute(on ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_EARPIECE);
            } catch (Exception e) {
                AirLogger.e(TAG, "Error setting speakerphone", e);
            }
        }
    }

    public static void setMute(boolean muted) {
        if (instance != null) {
            try {
                AirLogger.i(TAG, "Setting mute=" + muted);
                instance.setMuted(muted);
            } catch (Exception e) {
                AirLogger.e(TAG, "Error setting mute", e);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        AirLogger.i(TAG, "AirSignalInCallService created");
        createNotificationChannel();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AirLogger.i(TAG, "AirSignalInCallService destroyed");
        if (instance == this) {
            instance = null;
        }
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState audioState) {
        super.onCallAudioStateChanged(audioState);
        if (audioState != null) {
            AirLogger.i(TAG, "CallAudioState updated: Route=" + audioRouteToString(audioState.getRoute())
                    + ", SupportedRoutes=" + audioState.getSupportedRouteMask()
                    + ", isMuted=" + audioState.isMuted());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AirSignal Calls",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Incoming and active call notifications");
            channel.setSound(null, null);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        activeCall = call;
        String number = extractNumber(call);
        AirLogger.i(TAG, "onCallAdded: state=" + stateToString(call.getState()) + ", number=" + number);

        // Detect if the call is an incoming call (ringing)
        final boolean isIncoming = (call.getState() == Call.STATE_RINGING);

        call.registerCallback(new Call.Callback() {
            @Override
            public void onStateChanged(Call c, int state) {
                super.onStateChanged(c, state);
                AirLogger.i(TAG, "Call State Changed: " + stateToString(state) + " for number=" + extractNumber(c));

                // Automatically engage loud speakerphone and audio modem when call connects
                if (state == Call.STATE_ACTIVE) {
                    AirLogger.i(TAG, "Call became ACTIVE. Forcing loud speakerphone route and notifying AudioTransferService (ACTION_CALL_ACTIVE).");
                    setSpeakerphone(true);

                    Intent modemIntent = new Intent(AirSignalInCallService.this, AudioTransferService.class);
                    modemIntent.setAction(AudioTransferService.ACTION_CALL_ACTIVE);
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(modemIntent);
                        } else {
                            startService(modemIntent);
                        }
                    } catch (Exception e) {
                        AirLogger.e(TAG, "Failed to auto-start AudioTransferService with ACTION_CALL_ACTIVE", e);
                    }
                }

                // Cleanly shutdown modem and release hardware when call drops
                if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                    AirLogger.i(TAG, "Call DISCONNECTED. Stopping AudioTransferService.");
                    Intent stopModemIntent = new Intent(AirSignalInCallService.this, AudioTransferService.class);
                    stopModemIntent.setAction(AudioTransferService.ACTION_STOP_SERVICE);
                    try {
                        startService(stopModemIntent);
                    } catch (Exception e) {
                        AirLogger.e(TAG, "Failed to auto-stop AudioTransferService", e);
                    }
                }
            }
        });

        // If the call is already active immediately when added, force speaker and notify service
        if (call.getState() == Call.STATE_ACTIVE) {
            AirLogger.i(TAG, "Call added in STATE_ACTIVE. Forcing speaker and notifying AudioTransferService immediately.");
            setSpeakerphone(true);

            Intent modemIntent = new Intent(AirSignalInCallService.this, AudioTransferService.class);
            modemIntent.setAction(AudioTransferService.ACTION_CALL_ACTIVE);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(modemIntent);
                } else {
                    startService(modemIntent);
                }
            } catch (Exception e) {
                AirLogger.e(TAG, "Failed to send ACTION_CALL_ACTIVE onCallAdded", e);
            }
        }

        try {
            Intent intent = new Intent(this, InCallActivity.class);
            intent.putExtra("phone_number", number);
            intent.putExtra("is_incoming", isIncoming); // Pass the checked call direction to UI
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_calls)
                    .setContentTitle("AirSignal Incoming/Active Call")
                    .setContentText("Call from/to: " + number)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setFullScreenIntent(pendingIntent, true)
                    .setOngoing(true)
                    .setAutoCancel(false);

            Notification notification = builder.build();

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
            } catch (Exception e) {
                AirLogger.e(TAG, "Failed startForeground for Call notification", e);
                NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) {
                    manager.notify(NOTIFICATION_ID, notification);
                }
            }

            AirLogger.i(TAG, "Starting InCallActivity for call with number: " + number);
            startActivity(intent);
        } catch (Exception e) {
            AirLogger.e(TAG, "Error in onCallAdded handling", e);
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        AirLogger.i(TAG, "onCallRemoved: number=" + extractNumber(call));
        if (activeCall == call) {
            activeCall = null;
        }

        try {
            // Failsafe teardown of background modem if onStateChanged missed the transition
            Intent stopModemIntent = new Intent(AirSignalInCallService.this, AudioTransferService.class);
            stopModemIntent.setAction(AudioTransferService.ACTION_STOP_SERVICE);
            startService(stopModemIntent);
        } catch (Exception e) {
            AirLogger.e(TAG, "Failed failsafe stop of AudioTransferService", e);
        }

        try {
            stopForeground(true);
        } catch (Exception e) {
            AirLogger.e(TAG, "Error stopping foreground service", e);
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }

    private String extractNumber(Call call) {
        if (call != null && call.getDetails() != null && call.getDetails().getHandle() != null) {
            Uri handle = call.getDetails().getHandle();
            if (handle != null) {
                return handle.getSchemeSpecificPart();
            }
        }
        return "Unknown";
    }

    private String stateToString(int state) {
        switch (state) {
            case Call.STATE_NEW: return "STATE_NEW";
            case Call.STATE_RINGING: return "STATE_RINGING";
            case Call.STATE_DIALING: return "STATE_DIALING";
            case Call.STATE_CONNECTING: return "STATE_CONNECTING";
            case Call.STATE_ACTIVE: return "STATE_ACTIVE";
            case Call.STATE_HOLDING: return "STATE_HOLDING";
            case Call.STATE_DISCONNECTED: return "STATE_DISCONNECTED";
            case Call.STATE_DISCONNECTING: return "STATE_DISCONNECTING";
            default: return "STATE_UNKNOWN (" + state + ")";
        }
    }

    private String audioRouteToString(int route) {
        switch (route) {
            case CallAudioState.ROUTE_EARPIECE: return "ROUTE_EARPIECE";
            case CallAudioState.ROUTE_SPEAKER: return "ROUTE_SPEAKER";
            case CallAudioState.ROUTE_BLUETOOTH: return "ROUTE_BLUETOOTH";
            case CallAudioState.ROUTE_WIRED_HEADSET: return "ROUTE_WIRED_HEADSET";
            default: return "ROUTE_UNKNOWN (" + route + ")";
        }
    }
}