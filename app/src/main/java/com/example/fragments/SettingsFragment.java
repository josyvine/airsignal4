package com.example.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.example.R;
import com.example.database.TransferDatabase;
import com.example.utils.AirLogger;
import com.example.utils.FileAssembler;
import com.example.utils.SmsRoleManager;
import com.google.android.material.slider.Slider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SettingsFragment extends Fragment {

    private static final String TAG = "SettingsFragment";

    private TextView tvDefaultSmsStatus;
    private TextView tvDefaultDialerStatus;
    private TextView tvBaudRateVal;
    private Slider sliderBaudRate;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        tvDefaultSmsStatus = view.findViewById(R.id.tvDefaultSmsStatus);
        tvDefaultDialerStatus = view.findViewById(R.id.tvDefaultDialerStatus);
        tvBaudRateVal = view.findViewById(R.id.tvBaudRateVal);
        sliderBaudRate = view.findViewById(R.id.sliderBaudRate);

        updateRoleStatuses();

        view.findViewById(R.id.btnSetDefaultSms).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SmsRoleManager.requestDefaultSmsRole(requireActivity());
            }
        });

        view.findViewById(R.id.btnSetDefaultDialer).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SmsRoleManager.requestDefaultDialerRole(requireActivity());
            }
        });

        view.findViewById(R.id.btnOpenSystemDefaultApps).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SmsRoleManager.openSystemDefaultAppsSettings(requireContext());
            }
        });

        if (sliderBaudRate != null) {
            sliderBaudRate.addOnChangeListener(new Slider.OnChangeListener() {
                @Override
                public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
                    int baud = (int) value;
                    if (tvBaudRateVal != null) {
                        tvBaudRateVal.setText(baud + " Baud (FSK Tones)");
                    }
                }
            });
        }

        // Action Log Viewer Handlers
        View btnViewLogs = view.findViewById(R.id.btnViewAirLogs);
        if (btnViewLogs != null) {
            btnViewLogs.setOnClickListener(v -> showLogViewerDialog());
        }

        View btnClearLogs = view.findViewById(R.id.btnClearAirLogs);
        if (btnClearLogs != null) {
            btnClearLogs.setOnClickListener(v -> {
                AirLogger.clearLogs();
                Toast.makeText(requireContext(), "AirLog file cleared", Toast.LENGTH_SHORT).show();
            });
        }

        // Safe Dynamic Resolution for Received File Management Handlers
        int viewReceivedId = getResources().getIdentifier("btnViewReceivedFiles", "id", requireContext().getPackageName());
        if (viewReceivedId != 0) {
            View btnViewReceived = view.findViewById(viewReceivedId);
            if (btnViewReceived != null) {
                btnViewReceived.setOnClickListener(v -> showReceivedFilesManagerDialog());
            }
        }

        int clearTransfersId = getResources().getIdentifier("btnClearReceivedCache", "id", requireContext().getPackageName());
        if (clearTransfersId != 0) {
            View btnClearTransfers = view.findViewById(clearTransfersId);
            if (btnClearTransfers != null) {
                btnClearTransfers.setOnClickListener(v -> clearReceivedFilesAndDatabase());
            }
        }

        return view;
    }

    /**
     * Interactive Received Files Manager Dialog
     */
    public void showReceivedFilesManagerDialog() {
        File dir = FileAssembler.getReceivedFilesDir(requireContext());
        File[] files = dir.listFiles();

        if (files == null || files.length == 0) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Received Files & Assembly Hub")
                    .setMessage("No assembled files found in:\n" + dir.getAbsolutePath() + "\n\nFiles transferred over Voice Call or SMS will automatically appear here once assembled.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        List<String> fileNames = new ArrayList<>();
        final List<File> fileList = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault());

        for (File f : files) {
            if (f.isFile()) {
                fileList.add(f);
                long kb = f.length() / 1024;
                String date = sdf.format(new Date(f.lastModified()));
                fileNames.add(f.getName() + " (" + kb + " KB - " + date + ")");
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, fileNames);

        new AlertDialog.Builder(requireContext())
                .setTitle("Received Files (" + fileList.size() + ")")
                .setAdapter(adapter, (dialog, which) -> {
                    File selectedFile = fileList.get(which);
                    showFileActionDialog(selectedFile);
                })
                .setPositiveButton("Close", null)
                .setNeutralButton("Storage Path", (dialog, which) -> {
                    Toast.makeText(requireContext(), "Path: " + dir.getAbsolutePath(), Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void showFileActionDialog(File file) {
        CharSequence[] options = new CharSequence[]{"Open File", "Share File", "Delete File"};

        new AlertDialog.Builder(requireContext())
                .setTitle(file.getName())
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        openReceivedFile(file);
                    } else if (which == 1) {
                        shareReceivedFile(file);
                    } else if (which == 2) {
                        if (file.delete()) {
                            Toast.makeText(requireContext(), "File deleted", Toast.LENGTH_SHORT).show();
                            showReceivedFilesManagerDialog();
                        }
                    }
                })
                .show();
    }

    private void openReceivedFile(File file) {
        try {
            Uri fileUri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(fileUri, getMimeType(file));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            AirLogger.e(TAG, "Error opening received file", e);
            Toast.makeText(requireContext(), "No application found to open this file type", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareReceivedFile(File file) {
        try {
            Uri fileUri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(getMimeType(file));
            intent.putExtra(Intent.EXTRA_STREAM, fileUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share Received File"));
        } catch (Exception e) {
            AirLogger.e(TAG, "Error sharing received file", e);
            Toast.makeText(requireContext(), "Failed to share file", Toast.LENGTH_SHORT).show();
        }
    }

    private String getMimeType(File file) {
        String name = file.getName().toLowerCase(Locale.getDefault());
        if (name.endsWith(".webp") || name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/*";
        } else if (name.endsWith(".txt") || name.endsWith(".log")) {
            return "text/plain";
        } else if (name.endsWith(".3gp") || name.endsWith(".amr") || name.endsWith(".mp3")) {
            return "audio/*";
        }
        return "*/*";
    }

    private void clearReceivedFilesAndDatabase() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Clear Received Storage & Ledger")
                .setMessage("Are you sure you want to delete all downloaded files from Downloads/AirSignal_Transfers/ and clear the transfer ledger?")
                .setPositiveButton("Clear All", (dialog, which) -> {
                    File dir = FileAssembler.getReceivedFilesDir(requireContext());
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            f.delete();
                        }
                    }
                    try {
                        SQLiteDatabase db = TransferDatabase.getInstance(requireContext()).getWritableDatabase();
                        db.delete(TransferDatabase.TABLE_TRANSFERS, null, null);
                        db.delete(TransferDatabase.TABLE_PACKETS, null, null);
                    } catch (Exception e) {
                        AirLogger.e(TAG, "Error clearing transfer tables", e);
                    }
                    Toast.makeText(requireContext(), "Received files and database cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLogViewerDialog() {
        String logContent = AirLogger.readLogContent();
        if (logContent.isEmpty()) {
            logContent = "No log entries found yet in Download/airlog/air_actions.log";
        }

        final String finalLogContent = logContent;

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("AirSignal Action Log")
                .setMessage(finalLogContent)
                .setPositiveButton("OK", null)
                .setNegativeButton("Copy Log", (d, which) -> {
                    try {
                        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("AirSignal Action Log", finalLogContent);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(clip);
                            Toast.makeText(requireContext(), "Action log copied to clipboard!", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), "Failed to copy log: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Clear", (d, which) -> {
                    AirLogger.clearLogs();
                    Toast.makeText(requireContext(), "Logs cleared", Toast.LENGTH_SHORT).show();
                })
                .create();

        dialog.show();

        try {
            Button copyButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (copyButton != null) {
                int copyIconId = getResources().getIdentifier("copy_all_24px", "drawable", requireContext().getPackageName());
                if (copyIconId != 0) {
                    Drawable icon = ContextCompat.getDrawable(requireContext(), copyIconId);
                    if (icon != null) {
                        copyButton.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
                        copyButton.setCompoundDrawablePadding(dpToPx(6));
                    }
                }
            }
        } catch (Exception e) {
            AirLogger.e("SettingsFragment", "Failed to set copy icon on dialog button", e);
        }
    }

    private void updateRoleStatuses() {
        boolean smsDefault = SmsRoleManager.isDefaultSmsApp(requireContext());
        boolean dialerDefault = SmsRoleManager.isDefaultDialerApp(requireContext());

        if (tvDefaultSmsStatus != null) {
            tvDefaultSmsStatus.setText(smsDefault ? "Status: Default SMS Handler Active" : "Status: Not Default SMS App");
        }
        if (tvDefaultDialerStatus != null) {
            tvDefaultDialerStatus.setText(dialerDefault ? "Status: Default Phone Handler Active" : "Status: Not Default Phone App");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateRoleStatuses();
    }

    private int dpToPx(int dp) {
        if (getContext() == null) return dp;
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        );
    }
}