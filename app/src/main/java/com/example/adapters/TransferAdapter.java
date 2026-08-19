package com.example.adapters;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.StrictMode;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.models.TransferItem;
import com.example.utils.AirLogger;
import com.example.utils.FileAssembler;

import java.io.File;
import java.util.List;
import java.util.Locale;

public class TransferAdapter extends RecyclerView.Adapter<TransferAdapter.ViewHolder> {

    private static final String TAG = "TransferAdapter";
    private List<TransferItem> transferList;

    public interface OnItemClickListener {
        void onItemClick(TransferItem item);
    }

    private OnItemClickListener listener;

    public TransferAdapter(List<TransferItem> transferList) {
        this.transferList = transferList;
    }

    public TransferAdapter(List<TransferItem> transferList, OnItemClickListener listener) {
        this.transferList = transferList;
        this.listener = listener;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transfer, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TransferItem item = transferList.get(position);
        Context context = holder.itemView.getContext();

        holder.tvFilename.setText(item.getFilename());
        holder.tvMode.setText(item.getMode() + " • " + item.getReceivedPackets() + "/" + item.getTotalPackets() + " pkts");
        holder.tvProgressText.setText(item.getProgress() + "% (" + item.getStatus() + ")");
        holder.progressBar.setProgress(item.getProgress());

        // Dynamic status coloring
        String status = item.getStatus();
        if ("COMPLETED".equalsIgnoreCase(status)) {
            holder.tvProgressText.setTextColor(Color.parseColor("#00E676")); // Vivid Green
        } else if ("TRANSFERRING".equalsIgnoreCase(status) || "RECEIVING".equalsIgnoreCase(status) || "ASSEMBLING".equalsIgnoreCase(status)) {
            holder.tvProgressText.setTextColor(Color.parseColor("#00E5FF")); // Vivid Cyan
        } else if ("FAILED".equalsIgnoreCase(status)) {
            holder.tvProgressText.setTextColor(Color.parseColor("#FF5252")); // Soft Red
        } else {
            holder.tvProgressText.setTextColor(Color.parseColor("#B0BEC5")); // Grey
        }

        // Tap listener to open or manage file
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(item);
            } else {
                handleItemClick(context, item);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            showItemDetailsDialog(context, item);
            return true;
        });
    }

    private void handleItemClick(Context context, TransferItem item) {
        if ("COMPLETED".equalsIgnoreCase(item.getStatus())) {
            File receivedDir = FileAssembler.getReceivedFilesDir(context);
            File targetFile = new File(receivedDir, item.getFilename());

            if (!targetFile.exists()) {
                // Fallback check in cache directory
                targetFile = new File(context.getCacheDir(), item.getFilename());
            }

            if (targetFile.exists()) {
                showFileActionsDialog(context, targetFile, item);
            } else {
                Toast.makeText(context, "Completed file (" + item.getFilename() + ") in " + item.getMode(), Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(context, "Transfer " + item.getStatus() + " (" + item.getProgress() + "%)", Toast.LENGTH_SHORT).show();
        }
    }

    private void showFileActionsDialog(Context context, File file, TransferItem item) {
        CharSequence[] options = new CharSequence[]{"Open File", "Share File", "Transfer Info"};

        new AlertDialog.Builder(context)
                .setTitle(file.getName())
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        openFile(context, file);
                    } else if (which == 1) {
                        shareFile(context, file);
                    } else if (which == 2) {
                        showItemDetailsDialog(context, item);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openFile(Context context, File file) {
        try {
            Uri fileUri = getSafeUriForFile(context, file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(fileUri, getMimeType(file));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            AirLogger.e(TAG, "Error launching file viewer", e);
            Toast.makeText(context, "No app available to open: " + file.getName(), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareFile(Context context, File file) {
        try {
            Uri fileUri = getSafeUriForFile(context, file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(getMimeType(file));
            intent.putExtra(Intent.EXTRA_STREAM, fileUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(intent, "Share Transferred File"));
        } catch (Exception e) {
            AirLogger.e(TAG, "Error sharing file", e);
            Toast.makeText(context, "Unable to share file", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Resilient URI generator that handles dynamic package FileProvider authorities and falls back safely.
     */
    private Uri getSafeUriForFile(Context context, File file) {
        try {
            return FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
        } catch (Exception e1) {
            try {
                return FileProvider.getUriForFile(context, "com.example.provider", file);
            } catch (Exception e2) {
                try {
                    return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
                } catch (Exception e3) {
                    try {
                        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
                    } catch (Exception ignored) {}
                    return Uri.fromFile(file);
                }
            }
        }
    }

    private void showItemDetailsDialog(Context context, TransferItem item) {
        String details = "File ID: " + item.getFileId() +
                "\nFile Name: " + item.getFilename() +
                "\nSize: " + (item.getSize() > 0 ? (item.getSize() / 1024) + " KB" : "Variable") +
                "\nMode: " + item.getMode() +
                "\nStatus: " + item.getStatus() +
                "\nProgress: " + item.getProgress() + "% (" + item.getReceivedPackets() + "/" + item.getTotalPackets() + " packets)";

        new AlertDialog.Builder(context)
                .setTitle("Transfer Details")
                .setMessage(details)
                .setPositiveButton("OK", null)
                .show();
    }

    private String getMimeType(File file) {
        String name = file.getName().toLowerCase(Locale.getDefault());
        if (name.endsWith(".webp") || name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/*";
        } else if (name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".dat")) {
            return "text/plain";
        } else if (name.endsWith(".3gp") || name.endsWith(".amr") || name.endsWith(".mp3")) {
            return "audio/*";
        }
        return "*/*";
    }

    @Override
    public int getItemCount() {
        return transferList != null ? transferList.size() : 0;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFilename, tvMode, tvProgressText;
        ProgressBar progressBar;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvFilename = itemView.findViewById(R.id.tvFilename);
            tvMode = itemView.findViewById(R.id.tvTransferMode);
            tvProgressText = itemView.findViewById(R.id.tvTransferProgressText);
            progressBar = itemView.findViewById(R.id.progressBarTransfer);
        }
    }
}