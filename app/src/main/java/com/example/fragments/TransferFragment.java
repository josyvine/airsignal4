package com.example.fragments;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.adapters.TransferAdapter;
import com.example.audio.AudioEncoder;
import com.example.call.CallManager;
import com.example.database.TransferDatabase;
import com.example.knowledge.PhoneticBase64Dictionary;
import com.example.knowledge.PhoneticImageTransceiver;
import com.example.knowledge.PhoneticTokenManager;
import com.example.knowledge.TemplateCatalog;
import com.example.knowledge.VisualRenderer;
import com.example.models.DataPacket;
import com.example.models.TemplateToken;
import com.example.models.TransferItem;
import com.example.services.AirSignalInCallService;
import com.example.services.AudioTransferService;
import com.example.services.SmsTransferService;
import com.example.utils.AirLogger;
import com.example.utils.DataPacketManager;
import com.example.utils.FileAssembler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TransferFragment extends Fragment {

    private static final String TAG = "TransferFragment";

    private RecyclerView rvTransfers;
    private TransferAdapter adapter;
    private TransferDatabase transferDb;

    private Uri selectedFileUri = null;
    private String selectedFileName = "None";
    private long selectedFileSize = 0;
    private File localCachedFile = null;

    private ActivityResultLauncher<String> filePickerLauncher;

    // Real-time UI progress updater for incoming background transfers
    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (FileAssembler.ACTION_TRANSFER_PROGRESS.equals(intent.getAction())) {
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> loadTransfers());
                }
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Native Android Storage File/Image Picker Launcher
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        selectedFileUri = uri;
                        cacheSelectedFileLocally(uri);
                    }
                }
        );
    }

    @Override
    public void onResume() {
        super.onResume();
        loadTransfers();
        if (getContext() != null) {
            IntentFilter filter = new IntentFilter(FileAssembler.ACTION_TRANSFER_PROGRESS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getContext().registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                getContext().registerReceiver(progressReceiver, filter);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getContext() != null) {
            try {
                getContext().unregisterReceiver(progressReceiver);
            } catch (Exception ignored) {
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_transfer, container, false);

        rvTransfers = view.findViewById(R.id.rvTransfers);
        transferDb = TransferDatabase.getInstance(requireContext());
        rvTransfers.setLayoutManager(new LinearLayoutManager(requireContext()));

        loadTransfers();

        view.findViewById(R.id.btnSelectFile).setOnClickListener(v -> showFileSelectionDialog());

        view.findViewById(R.id.btnSendSmsData).setOnClickListener(v -> showSmsDataDialog());

        view.findViewById(R.id.btnSendAudioData).setOnClickListener(v -> showAudioDataDialog());

        return view;
    }

    private void loadTransfers() {
        List<TransferItem> list = transferDb.getAllTransfers();
        if (adapter == null) {
            adapter = new TransferAdapter(list);
            rvTransfers.setAdapter(adapter);
        } else {
            rvTransfers.setAdapter(new TransferAdapter(list));
        }
    }

    private void showFileSelectionDialog() {
        CharSequence[] options = new CharSequence[]{
                "Choose Real Image / File from Storage",
                "Send Image via Phonetic Base64 Dictionary",
                "Start Local Receiver Mode (Listen for Nearby Sound)",
                "Create Custom Visual Template (Phonetic)",
                "Send Exact Lossless File (2400 Baud Audio)",
                "Preview Receiver Template"
        };

        new AlertDialog.Builder(requireContext())
                .setTitle("Offline Data Transfer Hub")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        filePickerLauncher.launch("*/*");
                    } else if (which == 1) {
                        sendImageViaPhoneticBase64Dictionary();
                    } else if (which == 2) {
                        startLocalReceiverMode();
                    } else if (which == 3) {
                        showCustomTemplateBuilderDialog();
                    } else if (which == 4) {
                        sendExactLosslessBinaryStream();
                    } else if (which == 5) {
                        simulateReceiverPopup();
                    }
                })
                .show();
    }

    private void cacheSelectedFileLocally(Uri uri) {
        try {
            Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex != -1) selectedFileName = cursor.getString(nameIndex);
                if (sizeIndex != -1) selectedFileSize = cursor.getLong(sizeIndex);
                cursor.close();
            }

            InputStream is = requireContext().getContentResolver().openInputStream(uri);
            if (is != null) {
                localCachedFile = new File(requireContext().getCacheDir(), selectedFileName != null ? selectedFileName : "transfer_payload.bin");
                FileOutputStream fos = new FileOutputStream(localCachedFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
                fos.flush();
                fos.close();
                is.close();
                selectedFileSize = localCachedFile.length();

                Toast.makeText(requireContext(), "Selected: " + selectedFileName + " (" + (selectedFileSize / 1024) + " KB)", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            AirLogger.e(TAG, "Error caching selected storage file", e);
            Toast.makeText(requireContext(), "Failed reading file from storage", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Starts the microphone on the receiver device to listen for local ambient acoustic transmissions.
     */
    private void startLocalReceiverMode() {
        Intent serviceIntent = new Intent(requireContext(), AudioTransferService.class);
        serviceIntent.setAction(AudioTransferService.ACTION_START_LOCAL_RECEIVER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(serviceIntent);
        } else {
            requireContext().startService(serviceIntent);
        }

        new AlertDialog.Builder(requireContext())
                .setTitle("Local Receiver Mode Active")
                .setMessage("Microphone is actively listening for nearby acoustic transfers.\n\nPlace the sender phone nearby and produce sound.")
                .setPositiveButton("OK", null)
                .show();
    }

    /**
     * Transmits real selected image using Phonetic Base64 Dictionary Block Substitution.
     * Offers choice between Cellular Phone Call Mode and Local Air-Gap Acoustic Mode.
     */
    private void sendImageViaPhoneticBase64Dictionary() {
        if (localCachedFile == null || !localCachedFile.exists()) {
            Toast.makeText(requireContext(), "Please select an image file from storage first.", Toast.LENGTH_LONG).show();
            filePickerLauncher.launch("image/*");
            return;
        }

        CharSequence[] modes = new CharSequence[]{
                "1. Local Audio (Air-Gap / Nearby Phones / No Call)",
                "2. Data Over Cellular Call (Remote Transmission)"
        };

        new AlertDialog.Builder(requireContext())
                .setTitle("Select Phonetic Transmission Mode")
                .setItems(modes, (dialog, which) -> {
                    if (which == 0) {
                        showLocalPhoneticCalculationAndTransmitDialog();
                    } else if (which == 1) {
                        showCallPhoneticTransmitDialog();
                    }
                })
                .show();
    }

    /**
     * Calculates local phonetic tokens, payload size, and estimated transfer duration, then confirms before sound output.
     */
    private void showLocalPhoneticCalculationAndTransmitDialog() {
        try {
            byte[] fileBytes = readFileBytes(localCachedFile);
            if (fileBytes == null || fileBytes.length == 0) {
                Toast.makeText(requireContext(), "Image file is empty", Toast.LENGTH_SHORT).show();
                return;
            }

            String rawBase64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
            List<String> phoneticTokens = PhoneticBase64Dictionary.encodeBase64ToPhoneticTokens(rawBase64);
            byte[] transmissionPayload = PhoneticImageTransceiver.formatTokensForTransmission(phoneticTokens);

            int tokenCount = phoneticTokens.size();
            int payloadBytes = transmissionPayload.length;
            // Estimated time: 5s initial silence + 1s wake-up + 5s sync window + audio payload streaming @ 1200 baud
            int audioSeconds = (int) Math.ceil((payloadBytes * 8.0) / 1200.0);
            int totalEstimatedSeconds = 5 + 1 + 5 + audioSeconds;

            String details = "File: " + selectedFileName + "\n" +
                    "Size: " + (selectedFileSize / 1024) + " KB (" + rawBase64.length() + " Base64 chars)\n" +
                    "Phonetic Tokens: " + tokenCount + " NATO words\n" +
                    "Payload Size: " + payloadBytes + " bytes\n\n" +
                    "Estimated Transfer Time: ~" + totalEstimatedSeconds + " seconds\n\n" +
                    "Timing Sequence:\n" +
                    "• 5s Silent delay to position phones\n" +
                    "• Wake-up activation signal to receiver\n" +
                    "• 5s Synchronization countdown\n" +
                    "• Acoustic data tone transmission";

            new AlertDialog.Builder(requireContext())
                    .setTitle("Local Acoustic Transfer Details")
                    .setMessage(details)
                    .setPositiveButton("Produce Sound / Transmit", (dialog, which) -> {
                        Intent serviceIntent = new Intent(requireContext(), AudioTransferService.class);
                        serviceIntent.setAction(AudioTransferService.ACTION_SEND_LOCAL_PHONETIC);
                        serviceIntent.putExtra(AudioTransferService.EXTRA_IMAGE_PATH, localCachedFile.getAbsolutePath());
                        serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_NAME, selectedFileName);
                        serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_SIZE, selectedFileSize);

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            requireContext().startForegroundService(serviceIntent);
                        } else {
                            requireContext().startService(serviceIntent);
                        }

                        Toast.makeText(requireContext(), "Local Transmission Started! (5s delay...)", Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();

        } catch (Exception e) {
            AirLogger.e(TAG, "Error calculating local phonetic parameters", e);
            Toast.makeText(requireContext(), "Failed calculating phonetic parameters: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Cellular Call Phonetic Transmission Setup
     */
    private void showCallPhoneticTransmitDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Phonetic Image via Cellular Call")
                .setMessage("File: " + selectedFileName + "\nThis encodes your image into Phonetic Dictionary words and transmits it over an active voice call once answered.")
                .setPositiveButton("Transmit over Call", (dialog, which) -> {
                    if (AirSignalInCallService.getActiveCall() != null) {
                        Intent serviceIntent = new Intent(requireContext(), AudioTransferService.class);
                        serviceIntent.setAction(AudioTransferService.ACTION_SEND_PHONETIC_IMAGE);
                        serviceIntent.putExtra(AudioTransferService.EXTRA_IMAGE_PATH, localCachedFile.getAbsolutePath());
                        serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_NAME, selectedFileName);
                        serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_SIZE, selectedFileSize);
                        requireContext().startService(serviceIntent);
                        Toast.makeText(requireContext(), "Transmitting over Active Call...", Toast.LENGTH_SHORT).show();
                    } else {
                        promptPhoneNumberAndPlaceCall(null, localCachedFile, null);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Interactive Custom Template Builder Dialog
     */
    private void showCustomTemplateBuilderDialog() {
        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(32, 24, 32, 24);

        TextView tvTpl = new TextView(requireContext());
        tvTpl.setText("Select Base Template:");
        tvTpl.setTextColor(Color.WHITE);

        Spinner spTemplates = new Spinner(requireContext());
        List<String> tplNames = new ArrayList<>();
        tplNames.add("Chalakudy River & Bridge Grid (Map #1)");
        tplNames.add("Emergency Medical Triage Form (Form #2)");
        tplNames.add("Roadblock & Infrastructure Assessment (#3)");
        tplNames.add("Logistics & Supply Drop Grid (#4)");
        tplNames.add("Search & Rescue Team Status (#5)");

        ArrayAdapter<String> tplAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, tplNames);
        spTemplates.setAdapter(tplAdapter);

        TextView tvIcon = new TextView(requireContext());
        tvIcon.setText("Select Marker / Hazard Stamp:");
        tvIcon.setTextColor(Color.WHITE);
        tvIcon.setPadding(0, 16, 0, 0);

        Spinner spIcons = new Spinner(requireContext());
        List<String> iconNames = new ArrayList<>();
        iconNames.add("Flood / Submersion Hazard");
        iconNames.add("Fire / Heat Hazard");
        iconNames.add("Roadblock / Structural Damage");
        iconNames.add("Medical Emergency / Casualty");
        iconNames.add("Evacuation Shelter / Safe Zone");
        iconNames.add("Severe Electrical / Gas Hazard");

        ArrayAdapter<String> iconAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, iconNames);
        spIcons.setAdapter(iconAdapter);

        TextView tvSev = new TextView(requireContext());
        tvSev.setText("Priority / Severity Level:");
        tvSev.setTextColor(Color.WHITE);
        tvSev.setPadding(0, 16, 0, 0);

        Spinner spSeverity = new Spinner(requireContext());
        List<String> sevNames = new ArrayList<>();
        sevNames.add("Low / Routine");
        sevNames.add("Medium / Elevated");
        sevNames.add("High Priority");
        sevNames.add("CRITICAL EMERGENCY");

        ArrayAdapter<String> sevAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, sevNames);
        spSeverity.setAdapter(sevAdapter);

        TextView tvVal = new TextView(requireContext());
        tvVal.setText("Metric Value (e.g. Water Depth in cm / Count):");
        tvVal.setTextColor(Color.WHITE);
        tvVal.setPadding(0, 16, 0, 0);

        EditText etMetric = new EditText(requireContext());
        etMetric.setHint("Enter numeric value (e.g., 240)");
        etMetric.setText("240");
        etMetric.setTextColor(Color.WHITE);

        container.addView(tvTpl);
        container.addView(spTemplates);
        container.addView(tvIcon);
        container.addView(spIcons);
        container.addView(tvSev);
        container.addView(spSeverity);
        container.addView(tvVal);
        container.addView(etMetric);

        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.addView(container);

        new AlertDialog.Builder(requireContext())
                .setTitle("Custom Visual Template Builder")
                .setView(scrollView)
                .setPositiveButton("Transmit via Call", (dialog, which) -> {
                    int tplId = spTemplates.getSelectedItemPosition() + 1;
                    int iconId = spIcons.getSelectedItemPosition() + 1;
                    int sevId = spSeverity.getSelectedItemPosition() + 1;
                    int val = 0;
                    try {
                        val = Integer.parseInt(etMetric.getText().toString().trim());
                    } catch (Exception ignored) {}

                    TemplateToken customToken = new TemplateToken(
                            TemplateToken.MODE_PHONETIC_TOKEN,
                            TemplateToken.CATEGORY_TACTICAL_MAP,
                            tplId,
                            18500,
                            35000,
                            iconId,
                            sevId,
                            val,
                            0
                    );

                    if (AirSignalInCallService.getActiveCall() != null) {
                        Intent serviceIntent = new Intent(requireContext(), AudioTransferService.class);
                        serviceIntent.setAction(AudioTransferService.ACTION_SEND_TOKEN);
                        serviceIntent.putExtra(AudioTransferService.EXTRA_TOKEN_PAYLOAD, customToken.toByteArray());
                        requireContext().startService(serviceIntent);
                        Toast.makeText(requireContext(), "Streaming Token into Active Call...", Toast.LENGTH_SHORT).show();
                    } else {
                        promptPhoneNumberAndPlaceCall(customToken.toByteArray(), null, null);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Transmits the real selected file over 2400 Baud FSK continuous audio stream.
     */
    private void sendExactLosslessBinaryStream() {
        if (localCachedFile == null || !localCachedFile.exists()) {
            Toast.makeText(requireContext(), "Please choose a file from storage first.", Toast.LENGTH_LONG).show();
            filePickerLauncher.launch("*/*");
            return;
        }

        byte[] fileBytes = readFileBytes(localCachedFile);
        if (fileBytes == null || fileBytes.length == 0) {
            Toast.makeText(requireContext(), "Selected file is empty.", Toast.LENGTH_SHORT).show();
            return;
        }

        List<byte[]> binaryPackets = DataPacketManager.createBinaryPackets(fileBytes);
        String fileId = UUID.randomUUID().toString().substring(0, 8);
        double estMinutes = (binaryPackets.size() * 263.0) / (300.0 * 60.0);

        new AlertDialog.Builder(requireContext())
                .setTitle("Transmit Exact Lossless File")
                .setMessage("File: " + selectedFileName + "\nSize: " + (selectedFileSize / 1024) + " KB\nTotal Audio Packets: " + binaryPackets.size() +
                        "\nEst. Transfer Time: ~" + String.format("%.1f", estMinutes) + " min @ 2400 Baud")
                .setPositiveButton("Dial Call & Start Stream", (dialog, which) -> {
                    TransferItem item = new TransferItem(fileId, selectedFileName, selectedFileSize, 0, "QUEUED", "RAW_BINARY_2400", binaryPackets.size(), 0);
                    transferDb.insertTransfer(item);

                    if (AirSignalInCallService.getActiveCall() != null) {
                        Intent serviceIntent = new Intent(requireContext(), AudioTransferService.class);
                        serviceIntent.setAction(AudioTransferService.ACTION_SEND_BINARY_FILE);
                        serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_PATH, localCachedFile.getAbsolutePath());
                        serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_NAME, selectedFileName);
                        serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_SIZE, selectedFileSize);
                        serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_ID, fileId);
                        requireContext().startService(serviceIntent);
                        Toast.makeText(requireContext(), "Streaming into Active Call...", Toast.LENGTH_SHORT).show();
                    } else {
                        promptPhoneNumberAndPlaceCall(null, null, localCachedFile);
                    }
                    loadTransfers();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptPhoneNumberAndPlaceCall(final byte[] tokenPayload, final File imageToTransmit, final File rawFileToTransmit) {
        final EditText etPhone = new EditText(requireContext());
        etPhone.setHint("Enter Target Phone Number to Call");
        etPhone.setPadding(32, 32, 32, 32);

        new AlertDialog.Builder(requireContext())
                .setTitle("Dial Audio Data Call")
                .setMessage("Enter the recipient's phone number. AirSignal will place the cellular voice call and stream the data automatically once answered.")
                .setView(etPhone)
                .setPositiveButton("Call & Transmit", (dialog, which) -> {
                    String phone = etPhone.getText().toString().trim();
                    if (!phone.isEmpty()) {
                        Intent serviceIntent = new Intent(requireContext(), AudioTransferService.class);
                        if (tokenPayload != null) {
                            serviceIntent.setAction(AudioTransferService.ACTION_SEND_TOKEN);
                            serviceIntent.putExtra(AudioTransferService.EXTRA_TOKEN_PAYLOAD, tokenPayload);
                        } else if (imageToTransmit != null) {
                            serviceIntent.setAction(AudioTransferService.ACTION_SEND_PHONETIC_IMAGE);
                            serviceIntent.putExtra(AudioTransferService.EXTRA_IMAGE_PATH, imageToTransmit.getAbsolutePath());
                            serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_NAME, selectedFileName);
                            serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_SIZE, selectedFileSize);
                        } else if (rawFileToTransmit != null) {
                            serviceIntent.setAction(AudioTransferService.ACTION_SEND_BINARY_FILE);
                            serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_PATH, rawFileToTransmit.getAbsolutePath());
                            serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_NAME, selectedFileName);
                            serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_SIZE, selectedFileSize);
                        }
                        requireContext().startService(serviceIntent);

                        CallManager.placeCall(requireContext(), phone);
                        Toast.makeText(requireContext(), "Dialing " + phone + ". Transmission will begin when answered.", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(requireContext(), "Please enter a valid phone number", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private byte[] readFileBytes(File file) {
        try (InputStream is = new java.io.FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            AirLogger.e(TAG, "Error reading file bytes", e);
            return new byte[0];
        }
    }

    private void simulateReceiverPopup() {
        TemplateToken token = new TemplateToken(
                TemplateToken.MODE_LOSSLESS_IMAGE_HEADER,
                TemplateToken.CATEGORY_LOSSLESS_IMAGE,
                TemplateCatalog.TEMPLATE_IMAGE_WEBP_LOSSLESS,
                640,
                480,
                TemplateToken.ICON_IMAGE_CONTAINER,
                TemplateToken.SEVERITY_LOW,
                1420,
                0
        );
        VisualRenderer.showVisualResultDialog(requireContext(), token);
    }

    private void showSmsDataDialog() {
        final EditText etPhone = new EditText(requireContext());
        etPhone.setHint("Enter Target Phone Number");
        etPhone.setPadding(32, 32, 32, 32);

        new AlertDialog.Builder(requireContext())
                .setTitle("Send via SMS Data Mode")
                .setMessage("Data will be encoded into Base64 packet chunks and transmitted via Cellular SMS.")
                .setView(etPhone)
                .setPositiveButton("Start SMS Transfer", (dialog, which) -> {
                    String phone = etPhone.getText().toString().trim();
                    if (!phone.isEmpty()) {
                        byte[] payloadBytes = (localCachedFile != null && localCachedFile.exists())
                                ? readFileBytes(localCachedFile)
                                : "AirSignal Offline Data Packet Test Content 2026".getBytes();

                        List<DataPacket> packets = DataPacketManager.createPackets(payloadBytes);
                        String fileId = packets.isEmpty() ? "SYS_01" : packets.get(0).getFileId();

                        TransferItem item = new TransferItem(fileId, selectedFileName.equals("None") ? "telemetry_log_2026.dat" : selectedFileName, payloadBytes.length, 100, "COMPLETED", "SMS_DATA", packets.size(), packets.size());
                        transferDb.insertTransfer(item);

                        Intent serviceIntent = new Intent(requireContext(), SmsTransferService.class);
                        serviceIntent.putExtra(SmsTransferService.EXTRA_TARGET_PHONE, phone);
                        requireContext().startService(serviceIntent);

                        Toast.makeText(requireContext(), "SMS Data Transfer Dispatched!", Toast.LENGTH_SHORT).show();
                        loadTransfers();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAudioDataDialog() {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_audio_transfer, null);
        final EditText etPhone = dialogView.findViewById(R.id.etTargetPhone);

        final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();

        dialogView.findViewById(R.id.btnCancelAudioDialog).setOnClickListener(v -> dialog.dismiss());

        dialogView.findViewById(R.id.btnStartAudioTransfer).setOnClickListener(v -> {
            String phone = etPhone.getText().toString().trim();
            if (!phone.isEmpty()) {
                String fileId = "SYS_AUD_" + System.currentTimeMillis();
                TransferItem item = new TransferItem(fileId, selectedFileName.equals("None") ? "stream_audio_data.bin" : selectedFileName, selectedFileSize > 0 ? selectedFileSize : 8192, 0, "QUEUED", "AUDIO_DATA", 16, 0);
                transferDb.insertTransfer(item);

                Intent serviceIntent = new Intent(requireContext(), AudioTransferService.class);
                serviceIntent.setAction(AudioTransferService.ACTION_SEND_AUDIO_DATA);
                if (localCachedFile != null && localCachedFile.exists()) {
                    serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_PATH, localCachedFile.getAbsolutePath());
                }
                serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_NAME, selectedFileName);
                serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_SIZE, selectedFileSize);
                serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_ID, fileId);
                requireContext().startService(serviceIntent);

                CallManager.placeCall(requireContext(), phone);

                Toast.makeText(requireContext(), "Dialing " + phone + ". Audio stream will start when answered.", Toast.LENGTH_LONG).show();
                dialog.dismiss();
                loadTransfers();
            }
        });

        dialog.show();
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        );
    }
}