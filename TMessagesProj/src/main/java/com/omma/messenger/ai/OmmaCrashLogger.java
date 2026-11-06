package com.omma.messenger.ai;

import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.provider.MediaStore;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes uncaught exceptions to the system Downloads folder so the stack trace can be
 * inspected without adb. Without this the exact crash cause is unrecoverable on-device.
 */
public final class OmmaCrashLogger {

    private OmmaCrashLogger() {
    }

    public static void install() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                write(throwable);
            } catch (Throwable ignore) {
                // Never let logging break the crash pipeline.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private static void write(Throwable throwable) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null || throwable == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
        builder.append(android.os.Build.MODEL != null ? android.os.Build.MODEL : "unknown").append(", Android ")
            .append(Build.VERSION.SDK_INT).append('\n');
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        throwable.printStackTrace(new java.io.PrintStream(out));
        builder.append(out.toString(StandardCharsets.UTF_8.name()));
        builder.append("\n----------------\n\n");

        ContentValues values = new ContentValues();
        String name = "omma-crash-" + System.currentTimeMillis() + ".txt";
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download");
        try {
            android.net.Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream stream = context.getContentResolver().openOutputStream(uri)) {
                    if (stream != null) {
                        stream.write(builder.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
