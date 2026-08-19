package com.example.utils;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AirLogger {

    private static final String TAG = "AirLogger";
    private static File logFile = null;

    public static void init(Context context) {
        try {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File airlogFolder = new File(downloadsDir, "airlog");
            if (!airlogFolder.exists()) {
                boolean created = airlogFolder.mkdirs();
                Log.d(TAG, "airlog folder created in Downloads: " + created);
            }

            if (!airlogFolder.exists()) {
                // Fallback to app's external downloads dir
                airlogFolder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (airlogFolder != null && !airlogFolder.exists()) {
                    airlogFolder.mkdirs();
                }
            }

            if (airlogFolder != null) {
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                String dateStr = dateFormat.format(new Date());
                logFile = new File(airlogFolder, "airlog_" + dateStr + ".txt");
                if (!logFile.exists()) {
                    logFile.createNewFile();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing AirLogger: " + e.getMessage(), e);
        }

        // Set uncaught exception handler
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            e("CRASH", "Uncaught exception on thread " + thread.getName(), throwable);
        });

        i("SYSTEM", "AirLogger initialized successfully.");
    }

    public static void i(String tag, String message) {
        Log.i(tag, message);
        writeLog("INFO", tag, message, null);
    }

    public static void d(String tag, String message) {
        Log.d(tag, message);
        writeLog("DEBUG", tag, message, null);
    }

    public static void w(String tag, String message) {
        Log.w(tag, message);
        writeLog("WARN", tag, message, null);
    }

    public static void w(String tag, String message, Throwable t) {
        Log.w(tag, message, t);
        writeLog("WARN", tag, message, t);
    }

    public static void e(String tag, String message) {
        Log.e(tag, message);
        writeLog("ERROR", tag, message, null);
    }

    public static void e(String tag, String message, Throwable t) {
        Log.e(tag, message, t);
        writeLog("ERROR", tag, message, t);
    }

    public static synchronized String readLogContent() {
        if (logFile == null || !logFile.exists()) {
            return "";
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(logFile))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error reading log file: " + e.getMessage();
        }
    }

    public static synchronized void clearLogs() {
        if (logFile != null && logFile.exists()) {
            try (FileWriter writer = new FileWriter(logFile, false)) {
                writer.write("");
                writer.flush();
            } catch (Exception ignored) {
            }
        }
    }

    private static synchronized void writeLog(String level, String tag, String message, Throwable t) {
        if (logFile == null) return;
        try (FileWriter writer = new FileWriter(logFile, true)) {
            SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
            String timeStr = timeFormat.format(new Date());

            StringBuilder sb = new StringBuilder();
            sb.append("[").append(timeStr).append("] [").append(level).append("/").append(tag).append("]: ").append(message).append("\n");
            if (t != null) {
                sb.append("   Exception: ").append(t.toString()).append("\n");
                for (StackTraceElement ste : t.getStackTrace()) {
                    sb.append("      at ").append(ste.toString()).append("\n");
                }
            }
            writer.write(sb.toString());
            writer.flush();
        } catch (Exception ignored) {
        }
    }
}