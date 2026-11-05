package com.omma.messenger.plugins;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.telegram.messenger.FileLog;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Import boundary for exteraGram plugins.
 *
 * Omma accepts the official Python plugin (.py) and Elyx (.elyx/.eaf) containers. The files
 * are copied into Omma's private directory only; they are never installed as APKs and are
 * never executed by the Java process. The Chaquopy/extera SDK runtime can consume this
 * directory when it is enabled in a future build variant.
 */
public final class OmmaPluginManager {
    private static final String PLUGINS_DIR = "extera_plugins";
    private static final long MAX_PLUGIN_BYTES = 8L * 1024L * 1024L;

    private OmmaPluginManager() {
    }

    /** Handles an external ACTION_VIEW intent that points at a supported plugin container. */
    public static boolean handleImportIntent(Context context, Intent intent) {
        if (context == null || intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) {
            return false;
        }
        Uri uri = intent.getData();
        String value = uri.toString().toLowerCase(Locale.US);
        if (!(value.endsWith(".py") || value.endsWith(".elyx") || value.endsWith(".eaf"))) {
            return false;
        }
        if (importPlugin(context, uri)) {
            Toast.makeText(context, "Плагин Omma импортирован", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context, "Не удалось импортировать плагин", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    public static boolean importPlugin(Context context, Uri uri) {
        if (context == null || uri == null) {
            return false;
        }
        String name = safeName(uri.getLastPathSegment());
        if (name == null || !isSupported(name)) {
            return false;
        }
        File directory = new File(context.getFilesDir(), PLUGINS_DIR);
        if (!directory.exists() && !directory.mkdirs()) {
            return false;
        }
        File destination = new File(directory, name);
        try (InputStream source = new BufferedInputStream(context.getContentResolver().openInputStream(uri));
             FileOutputStream output = new FileOutputStream(destination)) {
            if (source == null) {
                return false;
            }
            byte[] buffer = new byte[8192];
            long copied = 0;
            int count;
            while ((count = source.read(buffer)) != -1) {
                copied += count;
                if (copied > MAX_PLUGIN_BYTES) {
                    destination.delete();
                    return false;
                }
                output.write(buffer, 0, count);
            }
            output.flush();
            if (name.endsWith(".py")) {
                return hasPythonMetadata(destination);
            }
            return hasElyxMetadata(destination);
        } catch (Exception e) {
            FileLog.e(e);
            destination.delete();
            return false;
        }
    }

    private static boolean hasPythonMetadata(File file) throws IOException {
        byte[] bytes = new byte[(int) Math.min(file.length(), 256 * 1024)];
        try (FileInputStream input = new FileInputStream(file)) {
            int length = input.read(bytes);
            if (length <= 0) {
                return false;
            }
            String source = new String(bytes, 0, length, StandardCharsets.UTF_8);
            return source.contains("__id__") && source.contains("__name__")
                && source.contains("BasePlugin");
        }
    }

    private static boolean hasElyxMetadata(File file) throws IOException {
        boolean refmap = false;
        boolean main = false;
        try (ZipInputStream input = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("../")) {
                    return false;
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if ("refmap.yml".equals(name)) {
                    refmap = true;
                }
                if ("main.py".equals(name)) {
                    main = true;
                }
            }
        }
        return refmap && main;
    }

    private static boolean isSupported(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".py") || lower.endsWith(".elyx") || lower.endsWith(".eaf");
    }

    private static String safeName(String value) {
        if (value == null || value.length() == 0 || value.length() > 128) {
            return null;
        }
        String name = value.replace('\\', '_').replace('/', '_');
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-')) {
                return null;
            }
        }
        return name;
    }
}
