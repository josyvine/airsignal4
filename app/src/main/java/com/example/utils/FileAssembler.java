package com.example.utils;

import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.Base64;

import com.example.database.TransferDatabase;
import com.example.models.DataPacket;
import com.example.models.TransferItem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

public class FileAssembler {

    private static final String TAG = "FileAssembler";
    public static final String ACTION_TRANSFER_PROGRESS = "com.example.ACTION_TRANSFER_PROGRESS";
    public static final String EXTRA_FILE_ID = "fileId";
    public static final String EXTRA_FILE_PATH = "filePath";
    public static final String EXTRA_STATUS = "status";

    /**
     * Returns the standardized folder for all completed AirSignal transfers.
     * Implements explicit diagnostic tracking and fallback for Scoped Storage compatibility.
     */
    public static File getReceivedFilesDir(Context context) {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File transfersDir = new File(downloadsDir, "AirSignal_Transfers");

        if (!transfersDir.exists()) {
            boolean created = transfersDir.mkdirs();
            AirLogger.i(TAG, "Attempted to create public storage directory: " + transfersDir.getAbsolutePath() + ", success=" + created);
            if (!created) {
                // Fallback to app-specific external files directory if public storage is restricted by Scoped Storage
                if (context != null) {
                    File fallbackDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AirSignal_Transfers");
                    if (!fallbackDir.exists()) {
                        boolean fallbackCreated = fallbackDir.mkdirs();
                        AirLogger.i(TAG, "Created fallback app-specific directory: " + fallbackDir.getAbsolutePath() + ", success=" + fallbackCreated);
                    }
                    return fallbackDir;
                }
            }
        }
        return transfersDir;
    }

    /**
     * Entry point for incoming raw binary audio frames from AudioTransferService.
     * Performs boundary alignment, parses packet headers, commits to TransferDatabase, and triggers assembly upon completion.
     */
    public static void processIncomingBinaryFrame(Context context, byte[] rawFrame) {
        if (context == null || rawFrame == null || rawFrame.length == 0) return;

        // 1. Scan for Magic Byte boundary (0x53) to align framed packets
        DataPacket packet = null;
        for (int offset = 0; offset <= rawFrame.length - 7; offset++) {
            if (rawFrame[offset] == 0x53) { // Magic byte 'S'
                byte[] candidateSlice = Arrays.copyOfRange(rawFrame, offset, rawFrame.length);
                packet = DataPacketManager.parseBinaryPacket(candidateSlice);
                if (packet != null) {
                    break;
                }
            }
        }

        if (packet == null) {
            AirLogger.w(TAG, "Failed to parse incoming acoustic binary frame (" + rawFrame.length + " bytes).");
            return;
        }

        AirLogger.i(TAG, "Acoustic packet header decoded: ID=" + packet.getFileId() 
                + ", Index=" + packet.getPacketIndex() + "/" + packet.getTotalPackets());

        TransferDatabase db = TransferDatabase.getInstance(context);

        // 2. Ensure transfer metadata exists in tracking table
        TransferItem transfer = db.getTransfer(packet.getFileId());
        if (transfer == null) {
            String filename = "rx_file_" + System.currentTimeMillis() + ".dat";
            transfer = new TransferItem(
                    packet.getFileId(),
                    filename,
                    0,
                    0,
                    "RECEIVING",
                    "RAW_BINARY_1200",
                    packet.getTotalPackets(),
                    0
            );
            db.insertTransfer(transfer);
        }

        // 3. Insert packet and verify if entire transfer is complete
        boolean isComplete = db.insertPacketAndUpdateProgress(packet);

        // 4. Broadcast progress to UI (TransferFragment / InCallActivity)
        Intent progressIntent = new Intent(ACTION_TRANSFER_PROGRESS);
        progressIntent.putExtra(EXTRA_FILE_ID, packet.getFileId());
        progressIntent.putExtra(EXTRA_STATUS, isComplete ? "COMPLETED" : "RECEIVING");
        context.sendBroadcast(progressIntent);

        if (isComplete) {
            AirLogger.i(TAG, "All packets received for fileId=" + packet.getFileId() + ". Starting file assembly.");
            db.updateTransferStatus(packet.getFileId(), "ASSEMBLING");

            List<DataPacket> allPackets = db.getAllPackets(packet.getFileId());
            File assembledFile = assembleFile(context, transfer.getFilename(), allPackets);

            if (assembledFile != null && assembledFile.exists()) {
                db.updateTransferStatus(packet.getFileId(), "COMPLETED");
                AirLogger.i(TAG, "File successfully assembled: " + assembledFile.getAbsolutePath() + " (" + assembledFile.length() + " bytes)");

                // Index with Android MediaScanner so file appears instantly in downloads/gallery
                MediaScannerConnection.scanFile(
                        context.getApplicationContext(),
                        new String[]{assembledFile.getAbsolutePath()},
                        new String[]{getMimeType(assembledFile)},
                        (path, uri) -> AirLogger.i(TAG, "MediaScanner indexed assembled file: " + path)
                );

                Intent completeBroadcast = new Intent(ACTION_TRANSFER_PROGRESS);
                completeBroadcast.putExtra(EXTRA_FILE_ID, packet.getFileId());
                completeBroadcast.putExtra(EXTRA_FILE_PATH, assembledFile.getAbsolutePath());
                completeBroadcast.putExtra(EXTRA_STATUS, "COMPLETED");
                context.sendBroadcast(completeBroadcast);
            } else {
                db.updateTransferStatus(packet.getFileId(), "FAILED");
                AirLogger.e(TAG, "File assembly failed for fileId=" + packet.getFileId(), null);
            }
        }
    }

    /**
     * Assembles a list of database packets back into a cohesive file.
     * Supports both Mode 1 (SMS Base64 Text) and Mode 2/3 (Audio Raw Binary).
     */
    public static File assembleFile(Context context, String filename, List<DataPacket> packets) {
        try {
            if (packets == null || packets.isEmpty()) {
                AirLogger.e(TAG, "assembleFile called with empty packet list", null);
                return null;
            }

            // 1. Sort packets sequentially by index
            Collections.sort(packets, new Comparator<DataPacket>() {
                @Override
                public int compare(DataPacket p1, DataPacket p2) {
                    return Integer.compare(p1.getPacketIndex(), p2.getPacketIndex());
                }
            });

            // 2. Concatenate payloads
            StringBuilder sb = new StringBuilder();
            for (DataPacket packet : packets) {
                sb.append(packet.getPayload());
            }

            // 3. Decode Base64 (Used as internal storage transport for both SMS and Audio)
            byte[] decodedBytes = Base64.decode(sb.toString(), Base64.NO_WRAP);

            // 4. Automatically decompress if GZIP was applied by the sender
            byte[] decompressedBytes = decompressGzipIfNeeded(decodedBytes);

            // 5. Determine public output directory (Downloads/AirSignal_Transfers/)
            File outputDir = getReceivedFilesDir(context);

            // 6. Write final file to storage
            File outFile = new File(outputDir, filename);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(decompressedBytes);
                fos.flush();
            }

            AirLogger.i(TAG, "assembleFile written successfully to destination: " + outFile.getAbsolutePath());
            return outFile;
        } catch (Exception e) {
            AirLogger.e(TAG, "Error assembling file", e);
            return null;
        }
    }

    /**
     * Checks the magic number for GZIP (0x1F 0x8B). If detected, inflates the binary stream.
     * This allows 500 KB files to transfer in 8 minutes instead of 28 minutes.
     */
    private static byte[] decompressGzipIfNeeded(byte[] data) {
        if (data == null || data.length < 2) return data;

        // GZIP Magic Number: 0x1F8B
        if (data[0] == (byte) 0x1F && data[1] == (byte) 0x8B) {
            try {
                ByteArrayInputStream bais = new ByteArrayInputStream(data);
                GZIPInputStream gzis = new GZIPInputStream(bais);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                byte[] buffer = new byte[1024];
                int len;
                while ((len = gzis.read(buffer)) > 0) {
                    baos.write(buffer, 0, len);
                }

                gzis.close();
                bais.close();
                AirLogger.i(TAG, "GZIP stream decompressed successfully. Compressed size: " + data.length + ", Restored size: " + baos.size());
                return baos.toByteArray();
            } catch (Exception e) {
                AirLogger.w(TAG, "GZIP magic number detected, but decompression failed. Returning raw bytes.");
            }
        }
        return data; // Return original if not GZIP
    }

    /**
     * Validates if the local SQLite ledger has received all required chunks.
     */
    public static boolean isComplete(List<DataPacket> packets, int expectedTotal) {
        if (packets == null || packets.size() < expectedTotal) {
            return false;
        }
        return packets.size() == expectedTotal;
    }

    private static String getMimeType(File file) {
        String name = file.getName().toLowerCase(Locale.getDefault());
        if (name.endsWith(".webp")) {
            return "image/webp";
        } else if (name.endsWith(".png")) {
            return "image/png";
        } else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (name.endsWith(".txt") || name.endsWith(".log")) {
            return "text/plain";
        } else if (name.endsWith(".3gp")) {
            return "video/3gpp";
        } else if (name.endsWith(".amr")) {
            return "audio/amr";
        } else if (name.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        return "*/*";
    }
}