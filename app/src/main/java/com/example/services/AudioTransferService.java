package com.example.services;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.telecom.Call;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.audio.AudioEncoder;
import com.example.audio.AudioReceiver;
import com.example.database.TransferDatabase;
import com.example.knowledge.PhoneticImageTransceiver;
import com.example.knowledge.VisualRenderer;
import com.example.models.TemplateToken;
import com.example.models.TransferItem;
import com.example.utils.AirLogger;
import com.example.utils.DataPacketManager;
import com.example.utils.FileAssembler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class AudioTransferService extends Service implements AudioReceiver.AudioReceiverListener {

    private static final String TAG = "AudioTransferService";
    public static final String CHANNEL_ID = "audio_transfer_channel";
    private static final int NOTIFICATION_ID = 202;

    public static final String ACTION_SEND_TOKEN = "com.example.ACTION_SEND_TOKEN";
    public static final String ACTION_SEND_PHONETIC_IMAGE = "com.example.ACTION_SEND_PHONETIC_IMAGE";
    public static final String ACTION_SEND_RAW_BINARY = "com.example.ACTION_SEND_RAW_BINARY";
    public static final String ACTION_SEND_BINARY_FILE = "com.example.ACTION_SEND_BINARY_FILE";
    public static final String ACTION_SEND_AUDIO_DATA = "com.example.ACTION_SEND_AUDIO_DATA";
    public static final String ACTION_CALL_ACTIVE = "com.example.ACTION_CALL_ACTIVE";
    public static final String ACTION_EXECUTE_STAGED_TRANSMISSION = "com.example.ACTION_EXECUTE_STAGED_TRANSMISSION";
    public static final String ACTION_RECEIVER_MODE_ACTIVE = "com.example.ACTION_RECEIVER_MODE_ACTIVE";
    public static final String ACTION_STOP_SERVICE = "com.example.ACTION_STOP_SERVICE";

    // New Local Air-Gap Acoustic Actions (No Voice Call Required)
    public static final String ACTION_START_LOCAL_RECEIVER = "com.example.ACTION_START_LOCAL_RECEIVER";
    public static final String ACTION_SEND_LOCAL_PHONETIC = "com.example.ACTION_SEND_LOCAL_PHONETIC";

    public static final String EXTRA_TOKEN_PAYLOAD = "extra_token_payload";
    public static final String EXTRA_IMAGE_PATH = "extra_image_path";
    public static final String EXTRA_BINARY_FILE_PATH = "extra_binary_file_path";
    public static final String EXTRA_FILE_PATH = "extra_file_path";
    public static final String EXTRA_FILE_NAME = "extra_file_name";
    public static final String EXTRA_FILE_SIZE = "extra_file_size";
    public static final String EXTRA_FILE_ID = "extra_file_id";

    // Internal container for holding queued transmission payloads while call is dialing
    private static class StagedPayload {
        String action;
        byte[] tokenBytes;
        String filePath;
        String fileName;
        long fileSize;
        String fileId;
    }

    private static StagedPayload stagedPayload = null;

    private AudioReceiver audioReceiver;
    private AudioEncoder audioEncoder;
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    private PowerManager.WakeLock wakeLock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isListening = false;

    // Session buffer for accumulating multi-chunk phonetic image transmissions
    private final ByteArrayOutputStream imageStreamBuffer = new ByteArrayOutputStream();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AirLogger.i(TAG, "Initializing AudioTransferService");

        createNotificationChannel();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirSignal::AudioTransferWakeLock");
        }

        // Standard Bell 202 FSK standard (1200 Baud) for acoustic stability
        audioEncoder = new AudioEncoder(1200);
        audioReceiver = new AudioReceiver(this);
        audioReceiver.setBaudRate(1200);

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AirSignal Audio Data Mode Active")
                .setContentText("Awaiting transmission...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true);

        startForeground(NOTIFICATION_ID, notificationBuilder.build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(60 * 60 * 1000L /* 1 hour max */);
        }

        if (intent == null) {
            return START_STICKY;
        }

        String action = intent.getAction();
        AirLogger.i(TAG, "onStartCommand received action: " + action);

        if (ACTION_STOP_SERVICE.equals(action)) {
            stagedPayload = null;
            imageStreamBuffer.reset();
            stopSelf();
            return START_NOT_STICKY;
        }

        // 1. Standalone Local Receiver Mode (No Phone Call Required)
        if (ACTION_START_LOCAL_RECEIVER.equals(action)) {
            AirLogger.i(TAG, "Starting Standalone Local Receiver Mode");
            ensureAudioRoutingAndListening();
            updateNotification("Local Receiver Active: Listening for nearby sound...", 0);
            return START_STICKY;
        }

        // 2. Standalone Local Phonetic Transmission (5s delay -> Wake-up -> 5s countdown -> Data)
        if (ACTION_SEND_LOCAL_PHONETIC.equals(action)) {
            String imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH);
            String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
            long fileSize = intent.getLongExtra(EXTRA_FILE_SIZE, 0);

            executeLocalPhoneticTransmission(imagePath, fileName, fileSize);
            return START_STICKY;
        }

        // Call transitioned to ACTIVE: Engage receiver listening and prepare audio routing
        if (ACTION_CALL_ACTIVE.equals(action)) {
            ensureAudioRoutingAndListening();
            if (stagedPayload != null) {
                updateNotification("Call connected. Awaiting receiver handshake or tap transmit...", 0);
            } else {
                // Receiver side: Automatically transmit the AIR_ACK:RECEIVER_READY handshake tone
                mainHandler.postDelayed(() -> {
                    ensureAudioRoutingAndListening();
                    audioEncoder.transmitReceiverReadyAck(null);
                }, 600);
            }
            return START_STICKY;
        }

        // Explicit user trigger from InCallActivity screen
        if (ACTION_EXECUTE_STAGED_TRANSMISSION.equals(action)) {
            AirLogger.i(TAG, "User triggered ACTION_EXECUTE_STAGED_TRANSMISSION from in-call screen.");
            ensureAudioRoutingAndListening();
            executeStagedPayloadIfPresent();
            return START_STICKY;
        }

        // Check if an outbound transfer action was received
        if (ACTION_SEND_TOKEN.equals(action)
                || ACTION_SEND_PHONETIC_IMAGE.equals(action)
                || ACTION_SEND_BINARY_FILE.equals(action)
                || ACTION_SEND_AUDIO_DATA.equals(action)
                || ACTION_SEND_RAW_BINARY.equals(action)) {

            StagedPayload payload = new StagedPayload();
            payload.action = action;
            payload.tokenBytes = intent.getByteArrayExtra(EXTRA_TOKEN_PAYLOAD);
            payload.filePath = intent.getStringExtra(EXTRA_IMAGE_PATH);
            if (payload.filePath == null) {
                payload.filePath = intent.getStringExtra(EXTRA_FILE_PATH);
            }
            if (payload.filePath == null) {
                payload.filePath = intent.getStringExtra(EXTRA_BINARY_FILE_PATH);
            }
            payload.fileName = intent.getStringExtra(EXTRA_FILE_NAME);
            payload.fileSize = intent.getLongExtra(EXTRA_FILE_SIZE, 0);
            payload.fileId = intent.getStringExtra(EXTRA_FILE_ID);

            stagedPayload = payload;

            // Check if call is already connected in ACTIVE state
            Call activeCall = AirSignalInCallService.getActiveCall();
            if (activeCall != null && activeCall.getState() == Call.STATE_ACTIVE) {
                AirLogger.i(TAG, "Call is active. Payload staged for in-call execution.");
                ensureAudioRoutingAndListening();
                updateNotification("Payload staged. Tap 'Transmit Data' or await handshake...", 0);
            } else {
                AirLogger.i(TAG, "Call is not yet active (Dialing/Ringing). Payload staged safely.");
                updateNotification("Payload staged. Waiting for recipient to answer...", 0);
            }
            return START_STICKY;
        }

        ensureAudioRoutingAndListening();
        return START_STICKY;
    }

    /**
     * Executes the local standalone transmission sequence without a phone call:
     * 5s silence -> Wake-up tone -> 5s sync countdown -> Full acoustic FSK stream.
     */
    private void executeLocalPhoneticTransmission(final String imagePath, final String fileName, final long fileSize) {
        new Thread(() -> {
            AirLogger.i(TAG, "Starting Local Acoustic Transmission sequence...");

            // 1. Initial 5-second silent delay
            for (int sec = 5; sec > 0; sec--) {
                final int s = sec;
                mainHandler.post(() -> updateNotification("Position phones nearby. Starting in " + s + "s...", (5 - s) * 20));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
            }

            // 2. Play wake-up activation acoustic signal
            mainHandler.post(() -> {
                ensureAudioRoutingAndListening();
                updateNotification("Transmitting Wake-Up signal to receiver...", 0);

                audioEncoder.transmitActivationCommand(new AudioEncoder.OnTransmissionProgressListener() {
                    @Override
                    public void onProgress(int currentPacket, int totalPackets, int percent) {}

                    @Override
                    public void onComplete() {
                        AirLogger.i(TAG, "Local Wake-Up signal transmitted. Starting 5-second sync countdown...");
                        startLocalFiveSecondSyncAndTransmit(imagePath, fileName, fileSize);
                    }

                    @Override
                    public void onError(Exception e) {
                        AirLogger.e(TAG, "Error transmitting local wake-up signal, proceeding with fallback countdown", e);
                        startLocalFiveSecondSyncAndTransmit(imagePath, fileName, fileSize);
                    }
                });
            });
        }).start();
    }

    private void startLocalFiveSecondSyncAndTransmit(final String imagePath, final String fileName, final long fileSize) {
        new Thread(() -> {
            // 3. Second 5-second synchronization countdown
            for (int sec = 5; sec > 0; sec--) {
                final int s = sec;
                mainHandler.post(() -> updateNotification("Receiver awakened. Synchronizing channel (" + s + "s)...", (5 - s) * 20));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
            }

            // 4. Transmit full phonetic image audio stream
            mainHandler.post(() -> {
                ensureAudioRoutingAndListening();
                transmitPhoneticImageInternal(imagePath, fileName, fileSize);
            });
        }).start();
    }

    private void ensureAudioRoutingAndListening() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            try {
                if (!audioManager.isSpeakerphoneOn()) {
                    audioManager.setSpeakerphoneOn(true);
                    AirLogger.i(TAG, "Speakerphone set to TRUE for acoustic coupling");
                }

                int maxCallVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxCallVol, 0);

                int maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVol, 0);

                AirLogger.i(TAG, "Audio routing configured: Mode=" + audioManager.getMode() +
                        ", Speaker=" + audioManager.isSpeakerphoneOn() +
                        ", MusicVol=" + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + "/" + maxMusicVol);
            } catch (Exception e) {
                AirLogger.e(TAG, "Error configuring AudioManager routing parameters", e);
            }
        }

        if (!isListening && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioReceiver.startListening();
            isListening = true;
            updateNotification("Active. Listening for audio data...", 0);
        }
    }

    private void executeStagedPayloadIfPresent() {
        if (stagedPayload == null) {
            return;
        }

        final StagedPayload payload = stagedPayload;
        stagedPayload = null;

        mainHandler.postDelayed(() -> {
            ensureAudioRoutingAndListening();
            updateNotification("Sending activation command to receiver...", 0);

            Intent startBroadcast = new Intent(FileAssembler.ACTION_TRANSFER_PROGRESS);
            startBroadcast.putExtra(FileAssembler.EXTRA_STATUS, "TRANSFERRING");
            sendBroadcast(startBroadcast);

            audioEncoder.transmitActivationCommand(new AudioEncoder.OnTransmissionProgressListener() {
                @Override
                public void onProgress(int currentPacket, int totalPackets, int percent) {}

                @Override
                public void onComplete() {
                    AirLogger.i(TAG, "Activation command transmitted. Entering 7-second synchronization countdown...");
                    startSevenSecondCountdownThenTransmit(payload);
                }

                @Override
                public void onError(Exception e) {
                    AirLogger.e(TAG, "Error transmitting activation command, proceeding with fallback countdown", e);
                    startSevenSecondCountdownThenTransmit(payload);
                }
            });
        }, 800);
    }

    private void startSevenSecondCountdownThenTransmit(final StagedPayload payload) {
        new Thread(() -> {
            for (int sec = 7; sec > 0; sec--) {
                final int s = sec;
                mainHandler.post(() -> updateNotification("Receiver activated. Synchronizing channel (" + s + "s)...", (7 - s) * 14));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
            }

            mainHandler.post(() -> {
                try {
                    ensureAudioRoutingAndListening();
                    if (ACTION_SEND_TOKEN.equals(payload.action)) {
                        transmitTokenInternal(payload.tokenBytes);
                    } else if (ACTION_SEND_PHONETIC_IMAGE.equals(payload.action)) {
                        transmitPhoneticImageInternal(payload.filePath, payload.fileName, payload.fileSize);
                    } else if (ACTION_SEND_BINARY_FILE.equals(payload.action) || ACTION_SEND_AUDIO_DATA.equals(payload.action) || ACTION_SEND_RAW_BINARY.equals(payload.action)) {
                        transmitBinaryFileInternal(payload.filePath, payload.fileName, payload.fileSize, payload.fileId);
                    }
                } catch (Exception e) {
                    AirLogger.e(TAG, "Error executing staged transmission", e);
                    updateNotification("Transmission Error: " + e.getMessage(), 0);
                }
            });
        }).start();
    }

    private void transmitTokenInternal(byte[] tokenBytes) {
        if (tokenBytes == null || tokenBytes.length == 0) {
            AirLogger.e(TAG, "Cannot transmit token: payload is null or empty");
            return;
        }

        TemplateToken token = TemplateToken.fromByteArray(tokenBytes);
        if (token == null) {
            AirLogger.e(TAG, "Cannot parse template token bytes");
            return;
        }

        updateNotification("Transmitting Semantic Token...", 10);
        audioEncoder.transmitPhoneticToken(token, new AudioEncoder.OnTransmissionProgressListener() {
            @Override
            public void onProgress(int currentPacket, int totalPackets, int percent) {
                updateNotification("Transmitting Semantic Token...", percent);
            }

            @Override
            public void onComplete() {
                AirLogger.i(TAG, "Semantic Token transmission completed successfully.");
                updateNotification("Token Transmitted! Listening for data...", 100);

                Intent completeBroadcast = new Intent(FileAssembler.ACTION_TRANSFER_PROGRESS);
                completeBroadcast.putExtra(FileAssembler.EXTRA_STATUS, "COMPLETED");
                sendBroadcast(completeBroadcast);

                mainHandler.postDelayed(() -> updateNotification("Listening for incoming data...", 0), 3000);
            }

            @Override
            public void onError(Exception e) {
                AirLogger.e(TAG, "Token transmission failed", e);
                updateNotification("Transmission Error: " + e.getMessage(), 0);
            }
        });
    }

    private void transmitPhoneticImageInternal(String imagePath, String fileName, long fileSize) {
        if (imagePath == null) {
            AirLogger.e(TAG, "Cannot transmit phonetic image: imagePath is null");
            return;
        }

        File imgFile = new File(imagePath);
        if (!imgFile.exists()) {
            AirLogger.e(TAG, "Phonetic image file does not exist: " + imagePath);
            return;
        }

        updateNotification("Encoding and transmitting Phonetic Image...", 10);
        PhoneticImageTransceiver.sendImageViaPhoneticDictionary(
                getApplicationContext(),
                imgFile,
                audioEncoder,
                new PhoneticImageTransceiver.OnPhoneticTransferListener() {
                    @Override
                    public void onProgress(int step, int totalSteps, String statusMessage) {
                        int percent = (int) (((double) step / (double) Math.max(1, totalSteps)) * 100);
                        updateNotification("Phonetic Image: " + statusMessage, percent);

                        Intent progressIntent = new Intent(FileAssembler.ACTION_TRANSFER_PROGRESS);
                        progressIntent.putExtra(FileAssembler.EXTRA_STATUS, "TRANSFERRING");
                        sendBroadcast(progressIntent);
                    }

                    @Override
                    public void onSuccess(int totalTokensSent, int originalBase64Length) {
                        AirLogger.i(TAG, "Phonetic Image Transmitted successfully! (" + totalTokensSent + " tokens)");
                        updateNotification("Phonetic Image Sent! (" + totalTokensSent + " tokens)", 100);

                        TransferDatabase db = TransferDatabase.getInstance(getApplicationContext());
                        TransferItem item = new TransferItem(
                                "PHON_" + System.currentTimeMillis(),
                                fileName != null ? fileName : imgFile.getName(),
                                fileSize > 0 ? fileSize : imgFile.length(),
                                100,
                                TransferItem.STATUS_COMPLETED,
                                TransferItem.MODE_PHONETIC_TOKEN,
                                totalTokensSent,
                                totalTokensSent
                        );
                        db.insertTransfer(item);

                        Intent broadcast = new Intent(FileAssembler.ACTION_TRANSFER_PROGRESS);
                        broadcast.putExtra(FileAssembler.EXTRA_STATUS, "COMPLETED");
                        sendBroadcast(broadcast);

                        mainHandler.postDelayed(() -> updateNotification("Listening for incoming data...", 0), 3000);
                    }

                    @Override
                    public void onError(Exception e) {
                        AirLogger.e(TAG, "Phonetic Image transfer error", e);
                        updateNotification("Image Send Failed: " + e.getMessage(), 0);
                    }
                }
        );
    }

    private void transmitBinaryFileInternal(String filePath, String fileName, long fileSize, String fileId) {
        if (filePath == null) {
            AirLogger.w(TAG, "Binary file path is null, generating dummy telemetry data stream");
            byte[] dummyData = "AirSignal Telemetry Stream Test Data 2026".getBytes(StandardCharsets.UTF_8);
            List<byte[]> packets = DataPacketManager.createBinaryPackets(dummyData);
            audioEncoder.transmitRawStream(packets, null);
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            AirLogger.e(TAG, "Binary file does not exist: " + filePath);
            return;
        }

        byte[] fileBytes = readFileBytes(file);
        if (fileBytes == null || fileBytes.length == 0) {
            AirLogger.e(TAG, "Binary file read 0 bytes");
            return;
        }

        List<byte[]> binaryPackets = DataPacketManager.createBinaryPackets(fileBytes);
        final String effectiveFileId = (fileId != null) ? fileId : ("FILE_" + System.currentTimeMillis());
        final int totalPackets = binaryPackets.size();

        updateNotification("Streaming " + totalPackets + " Binary Packets @ 1200 Baud...", 0);

        audioEncoder.transmitRawStream(binaryPackets, new AudioEncoder.OnTransmissionProgressListener() {
            @Override
            public void onProgress(int currentPacket, int total, int percent) {
                updateNotification("Sending File: " + percent + "% (" + currentPacket + "/" + total + ")", percent);

                TransferDatabase db = TransferDatabase.getInstance(getApplicationContext());
                TransferItem item = new TransferItem(
                        effectiveFileId,
                        fileName != null ? fileName : file.getName(),
                        fileSize > 0 ? fileSize : file.length(),
                        percent,
                        "TRANSFERRING",
                        "RAW_BINARY_1200",
                        total,
                        currentPacket
                );
                db.insertTransfer(item);

                Intent broadcast = new Intent(FileAssembler.ACTION_TRANSFER_PROGRESS);
                broadcast.putExtra(FileAssembler.EXTRA_STATUS, "TRANSFERRING");
                sendBroadcast(broadcast);
            }

            @Override
            public void onComplete() {
                AirLogger.i(TAG, "Lossless binary stream completed successfully.");
                updateNotification("Binary File Sent Successfully!", 100);

                TransferDatabase db = TransferDatabase.getInstance(getApplicationContext());
                TransferItem item = new TransferItem(
                        effectiveFileId,
                        fileName != null ? fileName : file.getName(),
                        fileSize > 0 ? fileSize : file.length(),
                        100,
                        TransferItem.STATUS_COMPLETED,
                        "RAW_BINARY_1200",
                        totalPackets,
                        totalPackets
                );
                db.insertTransfer(item);

                Intent broadcast = new Intent(FileAssembler.ACTION_TRANSFER_PROGRESS);
                broadcast.putExtra(FileAssembler.EXTRA_STATUS, "COMPLETED");
                sendBroadcast(broadcast);

                mainHandler.postDelayed(() -> updateNotification("Listening for incoming data...", 0), 3000);
            }

            @Override
            public void onError(Exception e) {
                AirLogger.e(TAG, "Binary stream error", e);
                updateNotification("Stream Error: " + e.getMessage(), 0);
            }
        });
    }

    private byte[] readFileBytes(File file) {
        try (InputStream is = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            AirLogger.e(TAG, "Error reading file into byte array", e);
            return null;
        }
    }

    private void updateNotification(String text, int progress) {
        if (notificationBuilder != null && notificationManager != null) {
            notificationBuilder.setContentText(text);
            if (progress > 0 && progress <= 100) {
                notificationBuilder.setProgress(100, progress, false);
            } else {
                notificationBuilder.setProgress(0, 0, false);
            }
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }
    }

    @Override
    public void onDestroy() {
        AirLogger.i(TAG, "Destroying AudioTransferService");

        if (audioReceiver != null) {
            audioReceiver.stopListening();
            isListening = false;
        }

        if (audioEncoder != null) {
            audioEncoder.cancelTransmission();
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        stagedPayload = null;
        imageStreamBuffer.reset();
        super.onDestroy();
    }

    // =========================================================================
    // AudioReceiver Callback Handlers (Zero-Touch Automation)
    // =========================================================================

    @Override
    public void onByteDecoded(byte b) {
        // Individual raw byte decoded from acoustic FSK tone
    }

    @Override
    public void onReceiverReadyAckReceived() {
        AirLogger.i(TAG, "Received AIR_ACK:RECEIVER_READY from remote receiver! Automatically releasing staged data stream.");
        if (stagedPayload != null) {
            ensureAudioRoutingAndListening();
            executeStagedPayloadIfPresent();
        }
    }

    @Override
    public void onReceiverActivationCommand() {
        AirLogger.i(TAG, "Remote ACTIVATE_RECEIVER signal received! Engaging Receiver Mode.");
        updateNotification("Receiver Mode Active: Incoming Audio Transfer...", 10);

        Intent broadcast = new Intent(ACTION_RECEIVER_MODE_ACTIVE);
        sendBroadcast(broadcast);
    }

    @Override
    public void onFrameDecoded(byte[] frameData) {
        if (frameData == null || frameData.length == 0) return;

        String previewStr = new String(frameData, StandardCharsets.UTF_8);

        // 1. Phonetic Base64 Image Session Accumulator
        if (previewStr.contains(PhoneticImageTransceiver.PHONETIC_IMG_PREAMBLE) || imageStreamBuffer.size() > 0) {
            imageStreamBuffer.write(frameData, 0, frameData.length);
            byte[] currentFullStream = imageStreamBuffer.toByteArray();
            String fullStreamStr = new String(currentFullStream, StandardCharsets.UTF_8);

            AirLogger.i(TAG, "Accumulating Phonetic Image stream. Buffer size: " + currentFullStream.length + " bytes.");

            // Update Receiver UI live progress
            Intent liveProgressIntent = new Intent(FileAssembler.ACTION_TRANSFER_PROGRESS);
            liveProgressIntent.putExtra(FileAssembler.EXTRA_STATUS, "RECEIVING");
            sendBroadcast(liveProgressIntent);
            updateNotification("Receiving Image Data (" + currentFullStream.length + " bytes)...", 50);

            // Check if full stream reached completion closure '#' delimiters
            int firstHash = fullStreamStr.indexOf('#');
            int lastHash = fullStreamStr.lastIndexOf('#');
            if (firstHash != -1 && lastHash > firstHash && (fullStreamStr.endsWith("#") || countOccurrences(fullStreamStr, '#') >= 2 || currentFullStream.length > 7000)) {
                AirLogger.i(TAG, "Complete Phonetic Base64 Image received (" + currentFullStream.length + " bytes)! Reconstructing image.");
                List<String> tokens = PhoneticImageTransceiver.parseTransmissionToTokens(currentFullStream);
                PhoneticImageTransceiver.receiveAndReconstructImage(getApplicationContext(), tokens, "received_phonetic_photo.webp");

                imageStreamBuffer.reset();

                updateNotification("Received Phonetic Image!", 100);

                Intent completeBroadcast = new Intent(FileAssembler.ACTION_TRANSFER_PROGRESS);
                completeBroadcast.putExtra(FileAssembler.EXTRA_STATUS, "COMPLETED");
                sendBroadcast(completeBroadcast);

                mainHandler.postDelayed(() -> updateNotification("Listening for incoming data...", 0), 3000);
                return;
            }
            return;
        }

        // 2. Mode 2/3: Pass exact lossless binary frames to FileAssembler for GZIP decompression
        AirLogger.i(TAG, "Received raw binary frame (" + frameData.length + " bytes). Passing to Assembler.");
        FileAssembler.processIncomingBinaryFrame(getApplicationContext(), frameData);
    }

    private int countOccurrences(String str, char ch) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == ch) count++;
        }
        return count;
    }

    @Override
    public void onTokenDecoded(TemplateToken token) {
        if (token == null) return;

        AirLogger.i(TAG, "Received valid Mode 4 Template Token! Category ID: " + token.getCategoryId());

        // Mode 4: Automatic zero-touch visual layout reconstruction popup
        VisualRenderer.showVisualResultDialog(getApplicationContext(), token);

        updateNotification("Received Emergency Visual Token!", 100);
        mainHandler.postDelayed(() -> updateNotification("Listening for audio data stream...", 0), 3000);
    }

    @Override
    public void onError(Exception e) {
        AirLogger.e(TAG, "AudioReceiver encountered error", e);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AirSignal Audio Data Channel",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Maintains CPU wake locks and streams FSK modem data.");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}