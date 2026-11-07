package com.omma.messenger.plugins;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
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
    private static final String SETTINGS_DIR = "extera_plugin_settings";
    private static final String REGISTRY_KEY = "omma_plugin_registry";
    private static final long MAX_PLUGIN_BYTES = 8L * 1024L * 1024L;

    /** Plugin ids the engine should auto-load at app start. */
    private static final ArrayList<String> enabledPlugins = new ArrayList<>();
    /** Registered menu items: pluginId -> list of [itemId, menuType, text] triples. */
    private static final HashMap<String, ArrayList<String[]>> menuItems = new HashMap<>();
    /** Plugins with an active on_send_message_hook. */
    private static final ArrayList<String> sendMessageHooks = new ArrayList<>();
    /** Plugins with active TL request hooks. */
    private static final HashMap<String, ArrayList<String[]>> requestHooks = new HashMap<>();

    static {
        loadState();
    }

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

    // ---------- engine lifecycle ----------

    /** Auto-loads every enabled plugin; called once from ApplicationLoader.onCreate. */
    public static void startup() {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(OmmaPluginManager::loadAllEnabled, 1200);
    }

    public static void loadAllEnabled() {
        if (!OmmaPythonBridge.isAvailable()) {
            FileLog.d("[plugins] python runtime not started, skipping load");
            return;
        }
        File directory = new File(ApplicationLoader.applicationContext.getFilesDir(), PLUGINS_DIR);
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String id = pluginIdForFile(file);
            if (id == null || !enabledPlugins.contains(id)) {
                continue;
            }
            loadPluginFile(file);
        }
    }

    /** Loads (or reloads) one plugin file; returns the plugin id or null. */
    public static String loadPluginFile(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        try {
            String source = readAll(file);
            String id;
            if (file.getName().endsWith(".py")) {
                id = OmmaPythonBridge.loadPlugin(source, null);
            } else {
                id = OmmaPythonBridge.loadElyx(file.getAbsolutePath());
            }
            if (id != null) {
                registerEnabled(id);
            }
            return id;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public static void unloadPlugin(String pluginId) {
        OmmaPythonBridge.unloadPlugin(pluginId);
        menuItems.remove(pluginId);
        sendMessageHooks.remove(pluginId);
        requestHooks.remove(pluginId);
        saveState();
    }

    public static void setPluginEnabled(String pluginId, boolean enabled) {
        if (enabled) {
            if (!enabledPlugins.contains(pluginId)) {
                enabledPlugins.add(pluginId);
            }
        } else {
            enabledPlugins.remove(pluginId);
            unloadPlugin(pluginId);
        }
        saveState();
    }

    public static ArrayList<String> getEnabledPlugins() {
        return new ArrayList<>(enabledPlugins);
    }

    private static String pluginIdForFile(File file) {
        try {
            String metadata = OmmaPythonBridge.pluginMetadata(readAll(file));
            return new JSONObject(metadata).optString("id", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Public wrapper for the settings screen. */
    public static String pluginIdForFileName(File file) {
        return pluginIdForFile(file);
    }

    private static String readAll(File file) throws IOException {
        byte[] bytes = new byte[(int) Math.min(file.length(), MAX_PLUGIN_BYTES)];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = input.read(bytes);
            return new String(bytes, 0, Math.max(read, 0), StandardCharsets.UTF_8);
        }
    }

    // ---------- settings storage ----------

    private static SharedPreferences settingsPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("omma_plugin_settings", Context.MODE_PRIVATE);
    }

    public static String getSetting(String pluginId, String key) {
        return settingsPrefs().getString(pluginId + "|" + key, null);
    }

    public static void setSetting(String pluginId, String key, String value) {
        settingsPrefs().edit().putString(pluginId + "|" + key, value).apply();
    }

    public static String exportSettings(String pluginId) {
        try {
            JSONObject object = new JSONObject();
            android.content.SharedPreferences preferences = settingsPrefs();
            for (java.util.Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (entry.getKey().startsWith(pluginId + "|")) {
                    object.put(entry.getKey().substring(pluginId.length() + 1), String.valueOf(entry.getValue()));
                }
            }
            return object.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static void importSettings(String pluginId, String json) {
        try {
            JSONObject object = new JSONObject(json);
            SharedPreferences.Editor editor = settingsPrefs().edit();
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                editor.putString(pluginId + "|" + key, object.optString(key));
            }
            editor.apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void notifySettingsChanged(String pluginId) {
        // Settings screen re-renders on open; nothing to push proactively yet.
    }

    public static String getPluginConfig(String pluginId) {
        try {
            return new JSONObject(OmmaPythonBridge.pluginMetadata(readAll(pluginFile(pluginId)))).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static File pluginFile(String pluginId) {
        File directory = new File(ApplicationLoader.applicationContext.getFilesDir(), PLUGINS_DIR);
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (pluginId.equals(pluginIdForFile(file))) {
                    return file;
                }
            }
        }
        return new File(directory, pluginId + ".py");
    }

    // ---------- menu items and hooks ----------

    public static String addMenuItem(String pluginId, String itemJson) {
        try {
            JSONObject item = new JSONObject(itemJson);
            String itemId = item.optString("item_id", null);
            if (itemId == null || itemId.isEmpty()) {
                itemId = "omma_" + Integer.toHexString((pluginId + item.optString("text")).hashCode());
            }
            ArrayList<String[]> items = menuItems.get(pluginId);
            if (items == null) {
                items = new ArrayList<>();
                menuItems.put(pluginId, items);
            }
            items.add(new String[]{itemId, item.optString("menu_type"), item.optString("text")});
            saveState();
            return itemId;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public static void removeMenuItem(String pluginId, String itemId) {
        ArrayList<String[]> items = menuItems.get(pluginId);
        if (items != null) {
            items.removeIf(triple -> triple[0].equals(itemId));
            saveState();
        }
    }

    public static ArrayList<String[]> getMenuItems(String menuType) {
        ArrayList<String[]> result = new ArrayList<>();
        for (java.util.Map.Entry<String, ArrayList<String[]>> entry : menuItems.entrySet()) {
            if (!enabledPlugins.contains(entry.getKey())) {
                continue;
            }
            for (String[] triple : entry.getValue()) {
                if (triple[1].equals(menuType)) {
                    result.add(new String[]{entry.getKey(), triple[0], triple[2]});
                }
            }
        }
        return result;
    }

    public static void dispatchMenuClick(String pluginId, String itemId) {
        OmmaPythonBridge.dispatchMenuClick(pluginId, itemId, null);
    }

    public static void addSendMessageHook(String pluginId, int priority) {
        if (!sendMessageHooks.contains(pluginId)) {
            sendMessageHooks.add(pluginId);
            saveState();
        }
    }

    public static void addRequestHook(String pluginId, String name, boolean matchSubstring, int priority) {
        ArrayList<String[]> hooks = requestHooks.get(pluginId);
        if (hooks == null) {
            hooks = new ArrayList<>();
            requestHooks.put(pluginId, hooks);
        }
        hooks.add(new String[]{name, String.valueOf(matchSubstring), String.valueOf(priority)});
        saveState();
    }

    public static void removeHooks(String pluginId) {
        requestHooks.remove(pluginId);
        sendMessageHooks.remove(pluginId);
        saveState();
    }

    /**
     * Outgoing-message pipeline: called before a message is sent.
     * Returns true when a plugin cancelled the message.
     */
    public static boolean interceptOutgoingMessage(int account, String message, long dialogId) {
        if (sendMessageHooks.isEmpty() || message == null) {
            return false;
        }
        try {
            JSONObject params = new JSONObject();
            params.put("message", message);
            params.put("dialog_id", dialogId);
            for (String pluginId : new ArrayList<>(sendMessageHooks)) {
                if (!enabledPlugins.contains(pluginId)) {
                    continue;
                }
                String result = OmmaPythonBridge.dispatchSendMessageHook(pluginId, account, params.toString());
                JSONObject parsed = new JSONObject(result);
                if ("CANCEL".equals(parsed.optString("strategy"))) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    // ---------- persistence ----------

    private static void registerEnabled(String pluginId) {
        if (pluginId != null && !enabledPlugins.contains(pluginId)) {
            enabledPlugins.add(pluginId);
            saveState();
        }
    }

    private static void loadState() {
        try {
            SharedPreferences preferences = ApplicationLoader.applicationContext
                .getSharedPreferences("omma_plugins_state", Context.MODE_PRIVATE);
            String raw = preferences.getString(REGISTRY_KEY, null);
            if (raw == null) {
                return;
            }
            JSONObject state = new JSONObject(raw);
            JSONArray enabled = state.optJSONArray("enabled");
            if (enabled != null) {
                for (int i = 0; i < enabled.length(); i++) {
                    String id = enabled.optString(i, null);
                    if (id != null && !enabledPlugins.contains(id)) {
                        enabledPlugins.add(id);
                    }
                }
            }
            JSONObject menus = state.optJSONObject("menus");
            if (menus != null) {
                java.util.Iterator<String> keys = menus.keys();
                while (keys.hasNext()) {
                    String pluginId = keys.next();
                    JSONArray items = menus.optJSONArray(pluginId);
                    ArrayList<String[]> list = new ArrayList<>();
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONArray triple = items.optJSONArray(i);
                            if (triple != null && triple.length() == 3) {
                                list.add(new String[]{triple.optString(0), triple.optString(1), triple.optString(2)});
                            }
                        }
                    }
                    menuItems.put(pluginId, list);
                }
            }
            JSONArray sendHooks = state.optJSONArray("sendHooks");
            if (sendHooks != null) {
                for (int i = 0; i < sendHooks.length(); i++) {
                    String id = sendHooks.optString(i, null);
                    if (id != null && !sendMessageHooks.contains(id)) {
                        sendMessageHooks.add(id);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void saveState() {
        try {
            JSONObject state = new JSONObject();
            JSONArray enabled = new JSONArray();
            for (String id : enabledPlugins) {
                enabled.put(id);
            }
            state.put("enabled", enabled);
            JSONObject menus = new JSONObject();
            for (java.util.Map.Entry<String, ArrayList<String[]>> entry : menuItems.entrySet()) {
                JSONArray items = new JSONArray();
                for (String[] triple : entry.getValue()) {
                    JSONArray item = new JSONArray();
                    item.put(triple[0]);
                    item.put(triple[1]);
                    item.put(triple[2]);
                    items.put(item);
                }
                menus.put(entry.getKey(), items);
            }
            state.put("menus", menus);
            JSONArray sendHooks = new JSONArray();
            for (String id : sendMessageHooks) {
                sendHooks.put(id);
            }
            state.put("sendHooks", sendHooks);
            ApplicationLoader.applicationContext.getSharedPreferences("omma_plugins_state", Context.MODE_PRIVATE)
                .edit().putString(REGISTRY_KEY, state.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
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
