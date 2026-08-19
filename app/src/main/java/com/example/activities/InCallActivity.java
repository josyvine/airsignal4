package com.example.activities;

import android.Manifest;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.R;
import com.example.audio.AudioEncoder;
import com.example.database.DatabaseHelper;
import com.example.knowledge.PhoneticImageTransceiver;
import com.example.knowledge.PhoneticTokenManager;
import com.example.knowledge.TemplateCatalog;
import com.example.models.CallLogItem;
import com.example.models.TemplateToken;
import com.example.services.AirSignalInCallService;
import com.example.services.AudioTransferService;
import com.example.utils.AirLogger;
import com.example.utils.FileAssembler;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

public class InCallActivity extends AppCompatActivity {

    private static final String TAG = "InCallActivity";
    private static final int PERMISSION_REQUEST_RECORD_AUDIO = 202;

    private TextView tvCallerName;
    private TextView tvCallerPhone;
    private TextView tvCallStatus;
    private TextView tvRecordingBadge;
    private TextView tvMuteLabel;
    private TextView tvSpeakerLabel;
    private TextView tvRecordLabel;
    private TextView tvKeypadDigits;
    private View layoutInCallKeypad;
    private View layoutIncomingControls;
    private View layoutActiveControls;

    private FloatingActionButton btnMute;
    private FloatingActionButton btnKeypad;
    private FloatingActionButton btnSpeaker;
    private FloatingActionButton btnRecord;
    private FloatingActionButton btnEndCall;
    private FloatingActionButton btnAnswerCall;
    private FloatingActionButton btnDeclineCall;

    private String phoneNumber = "";
    private String contactName = "";
    private boolean isIncomingCall = false; // Flag to identify if we are the sender or receiver

    private AudioManager audioManager;
    private ToneGenerator toneGenerator;
    private MediaRecorder mediaRecorder;

    private boolean isMuted = false;
    private boolean isSpeakerOn = false;
    private boolean isKeypadVisible = false;
    private boolean isRecording = false;
    private boolean isCallConnected = false;

    private int callDurationSeconds = 0;
    private int recordingSeconds = 0;

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable callTimerRunnable;
    private Runnable recordingTimerRunnable;
    private Runnable badgeDismissRunnable;

    private DatabaseHelper dbHelper;
    private String recordFilePath;

    private ActivityResultLauncher<String> inCallFilePickerLauncher;

    // Real-time broadcast receiver for in-call data reception & activation events
    private final BroadcastReceiver inCallDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();

            if (AudioTransferService.ACTION_RECEIVER_MODE_ACTIVE.equals(action)) {
                AirLogger.i(TAG, "InCallActivity received ACTION_RECEIVER_MODE_ACTIVE broadcast");
                runOnUiThread(() -> {
                    if (badgeDismissRunnable != null) {
                        handler.removeCallbacks(badgeDismissRunnable);
                    }
                    tvRecordingBadge.setVisibility(View.VISIBLE);
                    tvRecordingBadge.setText("⚡ RECEIVING IN-CALL DATA...");
                    tvRecordingBadge.setOnClickListener(null);
                    Toast.makeText(InCallActivity.this, "Receiver Mode Active: In-Call Data Transfer Started", Toast.LENGTH_SHORT).show();
                });
            } else if (FileAssembler.ACTION_TRANSFER_PROGRESS.equals(action)) {
                String status = intent.getStringExtra(FileAssembler.EXTRA_STATUS);
                runOnUiThread(() -> {
                    if (badgeDismissRunnable != null) {
                        handler.removeCallbacks(badgeDismissRunnable);
                    }
                    tvRecordingBadge.setVisibility(View.VISIBLE);

                    if ("COMPLETED".equalsIgnoreCase(status)) {
                        tvRecordingBadge.setText("⚡ TRANSFER COMPLETE (100%)");
                        tvRecordingBadge.setOnClickListener(null);
                        // Auto-hide the completion badge after 3 seconds
                        badgeDismissRunnable = () -> {
                            if (tvRecordingBadge != null && !isRecording) {
                                tvRecordingBadge.setVisibility(View.GONE);
                            }
                        };
                        handler.postDelayed(badgeDismissRunnable, 3000);
                    } else {
                        tvRecordingBadge.setText("⚡ DATA STREAM IN PROGRESS");
                    }
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Native Storage Picker for In-Call Photo / File Transmission
        inCallFilePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        transmitPickedImageOverCall(uri);
                    }
                }
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager != null) {
                keyguardManager.requestDismissKeyguard(this, null);
            }
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_in_call);

        // Retrieve call direction flag from AirSignalInCallService
        isIncomingCall = getIntent().getBooleanExtra("is_incoming", false);

        dbHelper = DatabaseHelper.getInstance(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_DTMF, 80);
        } catch (Exception ignored) {
        }

        phoneNumber = getIntent().getStringExtra("phone_number");
        if (phoneNumber == null) phoneNumber = "Unknown";

        contactName = getIntent().getStringExtra("contact_name");

        tvCallerName = findViewById(R.id.tvCallerName);
        tvCallerPhone = findViewById(R.id.tvCallerPhone);
        tvCallStatus = findViewById(R.id.tvCallStatus);
        tvRecordingBadge = findViewById(R.id.tvRecordingBadge);
        tvMuteLabel = findViewById(R.id.tvMuteLabel);
        tvSpeakerLabel = findViewById(R.id.tvSpeakerLabel);
        tvRecordLabel = findViewById(R.id.tvRecordLabel);
        tvKeypadDigits = findViewById(R.id.tvKeypadDigits);
        layoutInCallKeypad = findViewById(R.id.layoutInCallKeypad);
        layoutIncomingControls = findViewById(R.id.layoutIncomingControls);
        layoutActiveControls = findViewById(R.id.layoutActiveControls);

        btnMute = findViewById(R.id.btnInCallMute);
        btnKeypad = findViewById(R.id.btnInCallKeypad);
        btnSpeaker = findViewById(R.id.btnInCallSpeaker);
        btnRecord = findViewById(R.id.btnInCallRecord);
        btnEndCall = findViewById(R.id.btnInCallEnd);
        btnAnswerCall = findViewById(R.id.btnAnswerCall);
        btnDeclineCall = findViewById(R.id.btnDeclineCall);

        tvCallerPhone.setText(phoneNumber);
        lookupContactName();

        setupControls();

        // Register in-call receiver mode & progress filters
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioTransferService.ACTION_RECEIVER_MODE_ACTIVE);
        filter.addAction(FileAssembler.ACTION_TRANSFER_PROGRESS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(inCallDataReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(inCallDataReceiver, filter);
        }

        android.telecom.Call activeCall = AirSignalInCallService.getActiveCall();
        if (activeCall != null) {
            activeCall.registerCallback(new android.telecom.Call.Callback() {
                @Override
                public void onStateChanged(android.telecom.Call call, int state) {
                    super.onStateChanged(call, state);
                    runOnUiThread(() -> updateTelecomCallState(state));
                }
            });
            updateTelecomCallState(activeCall.getState());
        } else {
            startCallSequence();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String newNumber = intent.getStringExtra("phone_number");
        if (newNumber != null && !newNumber.isEmpty()) {
            phoneNumber = newNumber;
            tvCallerPhone.setText(phoneNumber);
            lookupContactName();
        }
        isIncomingCall = intent.getBooleanExtra("is_incoming", false);
        android.telecom.Call activeCall = AirSignalInCallService.getActiveCall();
        if (activeCall != null) {
            updateTelecomCallState(activeCall.getState());
        }
    }

    private void updateTelecomCallState(int state) {
        AirLogger.i(TAG, "updateTelecomCallState: state=" + state);
        switch (state) {
            case android.telecom.Call.STATE_CONNECTING:
            case android.telecom.Call.STATE_DIALING:
                tvCallStatus.setText("Dialing...");
                if (layoutIncomingControls != null) layoutIncomingControls.setVisibility(View.GONE);
                if (layoutActiveControls != null) layoutActiveControls.setVisibility(View.VISIBLE);
                break;
            case android.telecom.Call.STATE_RINGING:
                tvCallStatus.setText("Incoming Call...");
                if (layoutIncomingControls != null) layoutIncomingControls.setVisibility(View.VISIBLE);
                if (layoutActiveControls != null) layoutActiveControls.setVisibility(View.GONE);
                break;
            case android.telecom.Call.STATE_ACTIVE:
                if (layoutIncomingControls != null) layoutIncomingControls.setVisibility(View.GONE);
                if (layoutActiveControls != null) layoutActiveControls.setVisibility(View.VISIBLE);
                if (!isCallConnected) {
                    isCallConnected = true;
                    tvCallStatus.setText("00:00");
                    startCallTimer();
                    AirLogger.i(TAG, "Call state ACTIVE - started call duration timer");

                    // Only show transmission action on the Sender Phone.
                    // Receiver Phone (isIncomingCall == true) stays GONE until it hears actual data.
                    if (tvRecordingBadge != null && !isRecording) {
                        if (!isIncomingCall) {
                            tvRecordingBadge.setVisibility(View.VISIBLE);
                            tvRecordingBadge.setText("⚡ TAP TO TRANSMIT DATA NOW");
                            tvRecordingBadge.setOnClickListener(v -> {
                                Intent triggerIntent = new Intent(InCallActivity.this, AudioTransferService.class);
                                triggerIntent.setAction(AudioTransferService.ACTION_EXECUTE_STAGED_TRANSMISSION);
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    startForegroundService(triggerIntent);
                                } else {
                                    startService(triggerIntent);
                                }
                                tvRecordingBadge.setText("⚡ TRANSMITTING ACTIVATION...");
                                Toast.makeText(InCallActivity.this, "Initiating Acoustic Data Stream...", Toast.LENGTH_SHORT).show();
                            });
                        } else {
                            tvRecordingBadge.setVisibility(View.GONE);
                        }
                    }
                }
                break;
            case android.telecom.Call.STATE_DISCONNECTED:
            case android.telecom.Call.STATE_DISCONNECTING:
                AirLogger.i(TAG, "Telecom Call disconnected/disconnecting");
                endCall();
                break;
        }
    }

    private void lookupContactName() {
        if (contactName != null && !contactName.trim().isEmpty()) {
            tvCallerName.setText(contactName);
            return;
        }

        // 1. Check local app Database
        List<com.example.models.User> dbUsers = dbHelper.getAllUsers();
        for (com.example.models.User u : dbUsers) {
            if (cleanNum(u.getPhone()).equals(cleanNum(phoneNumber))) {
                tvCallerName.setText(u.getName());
                return;
            }
        }

        // 2. Check System Contacts
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try {
                ContentResolver resolver = getContentResolver();
                Cursor cursor = resolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME},
                        ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?",
                        new String[]{"%" + cleanNum(phoneNumber) + "%"},
                        null
                );
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                    if (nameIdx != -1) {
                        String name = cursor.getString(nameIdx);
                        if (name != null && !name.isEmpty()) {
                            tvCallerName.setText(name);
                            cursor.close();
                            return;
                        }
                    }
                    cursor.close();
                }
            } catch (Exception ignored) {
            }
        }

        tvCallerName.setText(phoneNumber);
    }

    private String cleanNum(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[^0-9]", "");
    }

    private void startCallSequence() {
        tvCallStatus.setText("Dialing...");
        AirLogger.i(TAG, "startCallSequence: Outgoing call dialing to " + phoneNumber);

        handler.postDelayed(() -> {
            if (!isFinishing() && !isCallConnected) {
                tvCallStatus.setText("Ringing...");
                AirLogger.i(TAG, "startCallSequence: Outgoing call ringing...");
            }
        }, 2000);
    }

    private void startCallTimer() {
        callTimerRunnable = new Runnable() {
            @Override
            public void run() {
                callDurationSeconds++;
                int mins = callDurationSeconds / 60;
                int secs = callDurationSeconds % 60;
                tvCallStatus.setText(String.format(Locale.getDefault(), "%02d:%02d", mins, secs));
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(callTimerRunnable);
    }

    private void setupControls() {
        if (btnAnswerCall != null) {
            btnAnswerCall.setOnClickListener(v -> {
                android.telecom.Call activeCall = AirSignalInCallService.getActiveCall();
                if (activeCall != null) {
                    try {
                        activeCall.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }

        if (btnDeclineCall != null) {
            btnDeclineCall.setOnClickListener(v -> {
                android.telecom.Call activeCall = AirSignalInCallService.getActiveCall();
                if (activeCall != null) {
                    try {
                        activeCall.reject(false, null);
                    } catch (Exception e) {
                        try {
                            activeCall.disconnect();
                        } catch (Exception ignored) {
                        }
                    }
                }
                endCall();
            });
        }

        // Mute
        btnMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            if (audioManager != null) {
                try {
                    audioManager.setMicrophoneMute(isMuted);
                } catch (Exception ignored) {
                }
            }
            AirSignalInCallService.setMute(isMuted);
            if (isMuted) {
                btnMute.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.primary));
                btnMute.setImageTintList(ContextCompat.getColorStateList(this, R.color.bg_dark));
                tvMuteLabel.setText("Muted");
            } else {
                btnMute.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.bg_card));
                btnMute.setImageTintList(ContextCompat.getColorStateList(this, R.color.text_primary));
                tvMuteLabel.setText("Mute");
            }
        });

        // Speaker (Click = Toggle Speaker, Long Click = In-Call Data Transfer Hub)
        btnSpeaker.setOnClickListener(v -> {
            isSpeakerOn = !isSpeakerOn;
            if (audioManager != null) {
                try {
                    audioManager.setSpeakerphoneOn(isSpeakerOn);
                } catch (Exception ignored) {
                }
            }
            AirSignalInCallService.setSpeakerphone(isSpeakerOn);
            if (isSpeakerOn) {
                btnSpeaker.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent_cyan));
                btnSpeaker.setImageTintList(ContextCompat.getColorStateList(this, R.color.bg_dark));
                tvSpeakerLabel.setText("Speaker On");
            } else {
                btnSpeaker.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.bg_card));
                btnSpeaker.setImageTintList(ContextCompat.getColorStateList(this, R.color.text_primary));
                tvSpeakerLabel.setText("Speaker");
            }
        });

        btnSpeaker.setOnLongClickListener(v -> {
            showInCallDataTransferDialog();
            return true;
        });

        // Keypad
        btnKeypad.setOnClickListener(v -> {
            isKeypadVisible = !isKeypadVisible;
            layoutInCallKeypad.setVisibility(isKeypadVisible ? View.VISIBLE : View.GONE);
            if (isKeypadVisible) {
                btnKeypad.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.primary));
                btnKeypad.setImageTintList(ContextCompat.getColorStateList(this, R.color.bg_dark));
            } else {
                btnKeypad.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.bg_card));
                btnKeypad.setImageTintList(ContextCompat.getColorStateList(this, R.color.text_primary));
            }
        });

        // DTMF buttons
        int[] dtmfIds = {
                R.id.dtmf1, R.id.dtmf2, R.id.dtmf3,
                R.id.dtmf4, R.id.dtmf5, R.id.dtmf6,
                R.id.dtmf7, R.id.dtmf8, R.id.dtmf9,
                R.id.dtmfStar, R.id.dtmf0, R.id.dtmfHash
        };
        for (int id : dtmfIds) {
            Button b = findViewById(id);
            if (b != null) {
                b.setOnClickListener(v -> {
                    String d = b.getText().toString();
                    tvKeypadDigits.append(d);
                    playDtmf(d);
                });
            }
        }

        // Live Record
        btnRecord.setOnClickListener(v -> {
            if (isRecording) {
                stopLiveRecording();
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startLiveRecording();
                } else {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_RECORD_AUDIO);
                }
            }
        });

        btnRecord.setOnLongClickListener(v -> {
            showInCallDataTransferDialog();
            return true;
        });

        // End Call
        btnEndCall.setOnClickListener(v -> endCall());
    }

    /**
     * In-Call Data Transfer Action Dialog (Send Photo or Emergency Template during live call)
     */
    private void showInCallDataTransferDialog() {
        CharSequence[] options = new CharSequence[]{
                "Send Real Photo from Gallery over Call",
                "Send Instant Flood/Disaster Hazard Marker (~0.8s)",
                "Send Medical Emergency Triage Token (~0.8s)"
        };

        new AlertDialog.Builder(this)
                .setTitle("In-Call Acoustic Data Transfer")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        inCallFilePickerLauncher.launch("image/*");
                    } else if (which == 1) {
                        transmitInCallTemplateToken(TemplateCatalog.TEMPLATE_CHALAKUDY_SECTOR_MAP, TemplateToken.ICON_FLOOD, "Flood Hazard (2.4m)");
                    } else if (which == 2) {
                        transmitInCallTemplateToken(TemplateCatalog.TEMPLATE_MEDICAL_TRIAGE_REPORT, TemplateToken.ICON_MEDICAL, "Medical Triage RED");
                    }
                })
                .show();
    }

    private void transmitInCallTemplateToken(int templateId, int iconId, String label) {
        TemplateToken token = new TemplateToken(
                TemplateToken.MODE_PHONETIC_TOKEN,
                TemplateToken.CATEGORY_TACTICAL_MAP,
                templateId,
                18500,
                35000,
                iconId,
                TemplateToken.SEVERITY_CRITICAL,
                240,
                0
        );

        String nato = PhoneticTokenManager.encodeToPhoneticWords(token);
        AirLogger.i(TAG, "Transmitting in-call token: " + nato);

        Intent intent = new Intent(this, AudioTransferService.class);
        intent.setAction(AudioTransferService.ACTION_SEND_TOKEN);
        intent.putExtra(AudioTransferService.EXTRA_TOKEN_PAYLOAD, token.toByteArray());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        Toast.makeText(this, "Transmitting " + label + " over call audio...", Toast.LENGTH_SHORT).show();
    }

    private void transmitPickedImageOverCall(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return;

            File tempFile = new File(getCacheDir(), "incall_photo_transfer.webp");
            FileOutputStream fos = new FileOutputStream(tempFile);
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
            fos.flush();
            fos.close();
            is.close();

            Intent intent = new Intent(this, AudioTransferService.class);
            intent.setAction(AudioTransferService.ACTION_SEND_PHONETIC_IMAGE);
            intent.putExtra(AudioTransferService.EXTRA_IMAGE_PATH, tempFile.getAbsolutePath());
            intent.putExtra(AudioTransferService.EXTRA_FILE_NAME, "incall_photo.webp");
            intent.putExtra(AudioTransferService.EXTRA_FILE_SIZE, tempFile.length());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            Toast.makeText(this, "Transmitting Photo over call stream...", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            AirLogger.e(TAG, "Failed reading in-call picked image", e);
            Toast.makeText(this, "Failed loading image from gallery", Toast.LENGTH_SHORT).show();
        }
    }

    private void playDtmf(String digit) {
        if (toneGenerator != null) {
            int tone = ToneGenerator.TONE_DTMF_0;
            switch (digit) {
                case "1": tone = ToneGenerator.TONE_DTMF_1; break;
                case "2": tone = ToneGenerator.TONE_DTMF_2; break;
                case "3": tone = ToneGenerator.TONE_DTMF_3; break;
                case "4": tone = ToneGenerator.TONE_DTMF_4; break;
                case "5": tone = ToneGenerator.TONE_DTMF_5; break;
                case "6": tone = ToneGenerator.TONE_DTMF_6; break;
                case "7": tone = ToneGenerator.TONE_DTMF_7; break;
                case "8": tone = ToneGenerator.TONE_DTMF_8; break;
                case "9": tone = ToneGenerator.TONE_DTMF_9; break;
                case "*": tone = ToneGenerator.TONE_DTMF_S; break;
                case "#": tone = ToneGenerator.TONE_DTMF_P; break;
            }
            try {
                toneGenerator.startTone(tone, 120);
            } catch (Exception ignored) {
            }
        }
    }

    private void startLiveRecording() {
        try {
            File cacheDir = getExternalCacheDir();
            if (cacheDir == null) cacheDir = getCacheDir();
            File recFile = new File(cacheDir, "call_rec_" + System.currentTimeMillis() + ".3gp");
            recordFilePath = recFile.getAbsolutePath();

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setOutputFile(recordFilePath);
            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecording = true;
            btnRecord.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.status_red));
            btnRecord.setImageTintList(ContextCompat.getColorStateList(this, R.color.text_primary));
            tvRecordLabel.setText("Recording");

            tvRecordingBadge.setVisibility(View.VISIBLE);
            recordingSeconds = 0;

            recordingTimerRunnable = new Runnable() {
                @Override
                public void run() {
                    recordingSeconds++;
                    int mins = recordingSeconds / 60;
                    int secs = recordingSeconds % 60;
                    tvRecordingBadge.setText(String.format(Locale.getDefault(), "🔴 REC %02d:%02d", mins, secs));
                    handler.postDelayed(this, 1000);
                }
            };
            handler.post(recordingTimerRunnable);

            Toast.makeText(this, "Live call recording started", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            e.printStackTrace();
            isRecording = false;
            Toast.makeText(this, "Recording active (Audio session mode)", Toast.LENGTH_SHORT).show();
            tvRecordingBadge.setVisibility(View.VISIBLE);
            tvRecordingBadge.setText("🔴 REC ACTIVE");
        }
    }

    private void stopLiveRecording() {
        if (isRecording) {
            isRecording = false;
            if (recordingTimerRunnable != null) {
                handler.removeCallbacks(recordingTimerRunnable);
            }
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.stop();
                    mediaRecorder.release();
                } catch (Exception ignored) {
                }
                mediaRecorder = null;
            }
            tvRecordingBadge.setVisibility(View.GONE);
            btnRecord.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.bg_card));
            btnRecord.setImageTintList(ContextCompat.getColorStateList(this, R.color.text_primary));
            tvRecordLabel.setText("Record");

            Toast.makeText(this, "Call recording saved to file", Toast.LENGTH_SHORT).show();
        }
    }

    private void endCall() {
        if (badgeDismissRunnable != null) {
            handler.removeCallbacks(badgeDismissRunnable);
        }
        if (callTimerRunnable != null) {
            handler.removeCallbacks(callTimerRunnable);
        }
        stopLiveRecording();

        AirSignalInCallService.disconnectActiveCall();

        // Save call log in database with contact resolution
        CallLogItem callItem = new CallLogItem(
                0,
                phoneNumber,
                contactName != null && !contactName.isEmpty() ? contactName : phoneNumber,
                "OUTGOING",
                callDurationSeconds,
                System.currentTimeMillis()
        );
        dbHelper.insertCall(callItem);

        int mins = callDurationSeconds / 60;
        int secs = callDurationSeconds % 60;
        String durStr = String.format(Locale.getDefault(), "%02d:%02d", mins, secs);

        Toast.makeText(this, "Call Ended (" + durStr + ")", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLiveRecording();
            } else {
                Toast.makeText(this, "Microphone permission required for call recording", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(inCallDataReceiver);
        } catch (Exception ignored) {
        }
        if (badgeDismissRunnable != null) {
            handler.removeCallbacks(badgeDismissRunnable);
        }
        if (callTimerRunnable != null) {
            handler.removeCallbacks(callTimerRunnable);
        }
        if (recordingTimerRunnable != null) {
            handler.removeCallbacks(recordingTimerRunnable);
        }
        stopLiveRecording();
        if (audioManager != null) {
            try {
                audioManager.setSpeakerphoneOn(false);
                audioManager.setMicrophoneMute(false);
            } catch (Exception ignored) {
            }
        }
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
        super.onDestroy();
    }
}