package com.omma.messenger.plugins;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.lang.reflect.Field;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

/**
 * Java side of the plugin engine. Calls into omma_plugins.loader (Python) through Chaquopy
 * and exposes the reverse bridge the Python SDK shim uses (base_plugin._Internal.call).
 *
 * All Python callbacks are dispatched on the Python thread pool; anything touching the UI
 * is re-posted through AndroidUtilities.runOnUIThread by the caller.
 */
public final class OmmaPythonBridge {

    private static volatile PyObject loaderModule;
    private static final Object lock = new Object();

    private OmmaPythonBridge() {
    }

    public static boolean isAvailable() {
        try {
            return Python.isStarted();
        } catch (Throwable t) {
            return false;
        }
    }

    private static PyObject loader() {
        PyObject module = loaderModule;
        if (module != null) {
            return module;
        }
        synchronized (lock) {
            if (loaderModule == null) {
                try {
                    Python python = Python.getInstance();
                    loaderModule = python.getModule("omma_plugins.loader");
                } catch (Throwable t) {
                    FileLog.e(t);
                    return null;
                }
            }
            return loaderModule;
        }
    }

    // ---------- calls into Python ----------

    public static String loadPlugin(String source, String pluginId) {
        PyObject loader = loader();
        if (loader == null) {
            return null;
        }
        try {
            PyObject result = loader.callAttr("load_plugin", source, pluginId);
            return result == null ? null : result.toString();
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    public static String loadElyx(String path) {
        PyObject loader = loader();
        if (loader == null) {
            return null;
        }
        try {
            PyObject result = loader.callAttr("load_elyx", path);
            return result == null ? null : result.toString();
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    public static void unloadPlugin(String pluginId) {
        PyObject loader = loader();
        if (loader == null) {
            return;
        }
        try {
            loader.callAttr("unload_plugin", pluginId);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static boolean pluginLoaded(String pluginId) {
        PyObject loader = loader();
        if (loader == null) {
            return false;
        }
        try {
            PyObject result = loader.callAttr("plugin_loaded", pluginId);
            return result != null && result.toBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String pluginMetadata(String source) {
        PyObject loader = loader();
        if (loader == null) {
            return "{}";
        }
        try {
            PyObject result = loader.callAttr("get_plugin_metadata", source);
            return result == null ? "{}" : result.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    public static String settingsSchema(String pluginId) {
        PyObject loader = loader();
        if (loader == null) {
            return "[]";
        }
        try {
            PyObject result = loader.callAttr("get_settings_schema", pluginId);
            return result == null ? "[]" : result.toString();
        } catch (Throwable t) {
            return "[]";
        }
    }

    public static String dispatchMenuClick(String pluginId, String itemId, String payloadJson) {
        PyObject loader = loader();
        if (loader == null) {
            return "false";
        }
        try {
            PyObject result = loader.callAttr("dispatch_menu_click", pluginId, itemId, payloadJson);
            return result == null ? "false" : result.toString();
        } catch (Throwable t) {
            FileLog.e(t);
            return "false";
        }
    }

    public static String dispatchSendMessageHook(String pluginId, int account, String paramsJson) {
        PyObject loader = loader();
        if (loader == null) {
            return "{\"strategy\":\"DEFAULT\"}";
        }
        try {
            PyObject result = loader.callAttr("dispatch_send_message_hook", pluginId, account, paramsJson);
            return result == null ? "{\"strategy\":\"DEFAULT\"}" : result.toString();
        } catch (Throwable t) {
            FileLog.e(t);
            return "{\"strategy\":\"DEFAULT\"}";
        }
    }

    public static void dispatchAppEvent(String pluginId, String event) {
        PyObject loader = loader();
        if (loader == null) {
            return;
        }
        try {
            loader.callAttr("dispatch_app_event", pluginId, event);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    // ---------- reverse bridge: called from Python ----------

    public static void pluginLog(String pluginId, String level, String message) {
        FileLog.d("[plugin:" + pluginId + "] " + level + ": " + message);
    }

    public static void runOnUi(Runnable runnable) {
        AndroidUtilities.runOnUIThread(runnable);
    }

    public static void runOnUiDelayed(Runnable runnable, long delayMs) {
        AndroidUtilities.runOnUIThread(runnable, (int) Math.max(0, delayMs));
    }

    public static void runOnQueue(Runnable runnable, long delayMs) {
        if (delayMs > 0) {
            AndroidUtilities.runOnUIThread(runnable, (int) delayMs);
        } else {
            new Thread(runnable, "omma-plugin-task").start();
        }
    }

    public static float dp(float value) {
        return AndroidUtilities.dp(value);
    }

    public static String getAppString(String name) {
        try {
            int id = ApplicationLoader.applicationContext.getResources()
                .getIdentifier(name, "string", ApplicationLoader.applicationContext.getPackageName());
            return id != 0 ? ApplicationLoader.applicationContext.getString(id) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static Object getAppContext() {
        return ApplicationLoader.applicationContext;
    }

    public static Object getCurrentActivity() {
        try {
            return AndroidUtilities.getActivity();
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object getLastFragment() {
        try {
            return org.telegram.ui.LaunchActivity.getSafeLastFragment();
        } catch (Throwable t) {
            return null;
        }
    }

    public static void copyToClipboard(String text) {
        AndroidUtilities.addToClipboard(text);
    }

    public static void showBulletin(String kind, String text) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                org.telegram.ui.ActionBar.BaseFragment fragment = org.telegram.ui.LaunchActivity.getSafeLastFragment();
                if (fragment != null) {
                    int iconRes = kind != null && kind.equals("error")
                        ? org.telegram.messenger.R.raw.error
                        : org.telegram.messenger.R.raw.info;
                    org.telegram.ui.Components.BulletinFactory.of(fragment)
                        .createSimpleBulletin(iconRes, text)
                        .show();
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
    }

    public static int getSelectedAccount() {
        return UserConfig.selectedAccount;
    }

    public static int getHookAccount() {
        return UserConfig.selectedAccount;
    }

    public static Object getAccountInstance(Integer account) {
        int index = account == null ? UserConfig.selectedAccount : account;
        return org.telegram.messenger.AccountInstance.getInstance(index);
    }

    public static long getUserId(Integer account) {
        int index = account == null ? UserConfig.selectedAccount : account;
        return UserConfig.getInstance(index).clientUserId;
    }

    public static Object getController(String name, Integer account) {
        int index = account == null ? UserConfig.selectedAccount : account;
        org.telegram.messenger.AccountInstance instance = org.telegram.messenger.AccountInstance.getInstance(index);
        switch (name == null ? "" : name) {
            case "messages":
                return instance.getMessagesController();
            case "contacts":
                return instance.getContactsController();
            case "mediaData":
                return instance.getMediaDataController();
            case "connections":
                return instance.getConnectionsManager();
            case "location":
                return instance.getLocationController();
            case "notifications":
                return instance.getNotificationsController();
            case "messagesStorage":
                return instance.getMessagesStorage();
            case "sendMessages":
                return instance.getSendMessagesHelper();
            case "fileLoader":
                return instance.getFileLoader();
            case "secretChats":
                return instance.getSecretChatHelper();
            case "download":
                return instance.getDownloadController();
            case "notificationsSettings":
                return instance.getNotificationsSettings();
            case "notificationCenter":
                return instance.getNotificationCenter();
            case "media":
                return org.telegram.messenger.MediaController.getInstance();
            case "userConfig":
                return instance.getUserConfig();
            case "fileRef":
                return instance.getFileRefController();
            case "stats":
                return instance.getStatsController();
            default:
                return null;
        }
    }

    public static Object findClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object getPrivateField(Object target, String name) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean setPrivateField(Object target, String name, Object value) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            field.set(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static Object getStaticPrivateField(Object clazz, String name) {
        try {
            Class<?> type = clazz instanceof Class ? (Class<?>) clazz : clazz.getClass();
            Field field = findField(type, name);
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean setStaticPrivateField(Object clazz, String name, Object value) {
        try {
            Class<?> type = clazz instanceof Class ? (Class<?>) clazz : clazz.getClass();
            Field field = findField(type, name);
            field.setAccessible(true);
            field.set(null, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    public static void sendText(long dialogId, String text, Integer account) {
        int index = account == null ? UserConfig.selectedAccount : account;
        AndroidUtilities.runOnUIThread(() -> SendMessagesHelper.getInstance(index)
            .sendMessage(SendMessagesHelper.SendMessageParams.of(text, dialogId)));
    }

    public static void sendPhoto(long dialogId, String path, String caption, Integer account) {
        // Photo sending from plugins is deferred: it needs a properly filled TL_document
        // (thumbs, size, mime detection). Text sending covers the common plugin cases.
        FileLog.d("[plugins] sendPhoto deferred for " + dialogId);
    }

    public static void sendVideo(long dialogId, String path, String caption, Integer account) {
        FileLog.d("[plugins] sendVideo deferred for " + dialogId);
    }

    public static void sendDocument(long dialogId, String path, String caption, Integer account) {
        FileLog.d("[plugins] sendDocument deferred for " + dialogId);
    }

    public static void sendAudio(long dialogId, String path, String caption, Integer account) {
        FileLog.d("[plugins] sendAudio deferred for " + dialogId);
    }

    public static void editMessage(long dialogId, int messageId, String text, Integer account) {
        // Editing needs the MessageObject; plugins rarely need it, so this is a no-op stub.
    }

    // ---------- settings storage for plugins ----------

    public static String getPluginSetting(String pluginId, String key) {
        return OmmaPluginManager.getSetting(pluginId, key);
    }

    public static void setPluginSetting(String pluginId, String key, String value) {
        OmmaPluginManager.setSetting(pluginId, key, value);
    }

    public static String exportPluginSettings(String pluginId) {
        return OmmaPluginManager.exportSettings(pluginId);
    }

    public static void importPluginSettings(String pluginId, String json) {
        OmmaPluginManager.importSettings(pluginId, json);
    }

    public static void reloadPluginSettings(String pluginId) {
        OmmaPluginManager.notifySettingsChanged(pluginId);
    }

    public static String getPluginConfig(String pluginId) {
        return OmmaPluginManager.getPluginConfig(pluginId);
    }

    public static String addMenuItem(String pluginId, String itemJson) {
        return OmmaPluginManager.addMenuItem(pluginId, itemJson);
    }

    public static void removeMenuItem(String pluginId, String itemId) {
        OmmaPluginManager.removeMenuItem(pluginId, itemId);
    }

    public static void addRequestHook(String pluginId, String name, boolean matchSubstring, int priority) {
        OmmaPluginManager.addRequestHook(pluginId, name, matchSubstring, priority);
    }

    public static void addSendMessageHook(String pluginId, int priority) {
        OmmaPluginManager.addSendMessageHook(pluginId, priority);
    }

    public static void removeHooks(String pluginId) {
        OmmaPluginManager.removeHooks(pluginId);
    }

    public static void sendTlRequest(Object request, Object callback, Integer account) {
        int index = account == null ? UserConfig.selectedAccount : account;
        if (!(request instanceof org.telegram.tgnet.TLObject)) {
            FileLog.e(new IllegalArgumentException("sendTlRequest: not a TLObject"));
            return;
        }
        ConnectionsManager.getInstance(index).sendRequest((org.telegram.tgnet.TLObject) request, (response, error) -> {
            if (callback != null) {
                try {
                    callback.getClass().getMethod("__call__", Object.class, Object.class).invoke(callback, response, error);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    // ---------- JSON helpers ----------

    public static String jsonGet(String json, String key) {
        try {
            return new JSONObject(json).optString(key, null);
        } catch (Exception e) {
            return null;
        }
    }

    public static String jsonArrayJoin(String json, String separator) {
        try {
            JSONArray array = new JSONArray(json);
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                if (builder.length() > 0) {
                    builder.append(separator);
                }
                builder.append(array.optString(i));
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
