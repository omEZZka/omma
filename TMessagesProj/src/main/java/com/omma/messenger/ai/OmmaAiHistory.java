package com.omma.messenger.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent store for assistant conversations.
 *
 * Each conversation is a small JSON document: id, title, chatTitle, createdAt, updatedAt and the
 * message list. Everything lives in one SharedPreferences file, which is enough for text chats and
 * keeps the implementation free of SQLite migrations.
 */
public final class OmmaAiHistory {

    public static final class Entry {
        public final String role;
        public final String text;

        public Entry(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    public static final class Conversation {
        public String id;
        public String title;
        public String chatTitle;
        public long createdAt;
        public long updatedAt;
        public final ArrayList<Entry> entries = new ArrayList<>();

        public Conversation() {
        }

        public String preview() {
            for (Entry entry : entries) {
                if ("user".equals(entry.role) && !TextUtils.isEmpty(entry.text)) {
                    String flat = entry.text.replace('\n', ' ').trim();
                    return flat.length() > 80 ? flat.substring(0, 80) + "…" : flat;
                }
            }
            return "Пустой диалог";
        }
    }

    private static final String PREFS_NAME = "omma_ai_history";
    private static final String KEY_CONVERSATIONS = "conversations";
    private static final String KEY_LAST_ID = "last_id";
    private static final int MAX_CONVERSATIONS = 60;

    private OmmaAiHistory() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static ArrayList<Conversation> loadAll() {
        ArrayList<Conversation> result = new ArrayList<>();
        String raw = prefs().getString(KEY_CONVERSATIONS, null);
        if (TextUtils.isEmpty(raw)) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                Conversation conversation = parse(array.optJSONObject(i));
                if (conversation != null) {
                    result.add(conversation);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    public static Conversation load(String id) {
        if (TextUtils.isEmpty(id)) {
            return null;
        }
        for (Conversation conversation : loadAll()) {
            if (id.equals(conversation.id)) {
                return conversation;
            }
        }
        return null;
    }

    public static void save(Conversation conversation) {
        if (conversation == null) {
            return;
        }
        ArrayList<Conversation> all = loadAll();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(conversation.id)) {
                all.set(i, conversation);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            all.add(0, conversation);
        }
        all.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        while (all.size() > MAX_CONVERSATIONS) {
            all.remove(all.size() - 1);
        }
        JSONArray array = new JSONArray();
        for (Conversation item : all) {
            JSONObject object = serialize(item);
            if (object != null) {
                array.put(object);
            }
        }
        prefs().edit().putString(KEY_CONVERSATIONS, array.toString()).apply();
    }

    public static void delete(String id) {
        ArrayList<Conversation> all = loadAll();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                all.remove(i);
                break;
            }
        }
        JSONArray array = new JSONArray();
        for (Conversation item : all) {
            JSONObject object = serialize(item);
            if (object != null) {
                array.put(object);
            }
        }
        prefs().edit().putString(KEY_CONVERSATIONS, array.toString()).apply();
    }

    public static void deleteAll() {
        prefs().edit().remove(KEY_CONVERSATIONS).remove(KEY_LAST_ID).apply();
    }

    /** The conversation that the assistant opened last, so the context survives restarts. */
    public static String getActiveId() {
        return prefs().getString(KEY_LAST_ID, null);
    }

    public static void setActiveId(String id) {
        prefs().edit().putString(KEY_LAST_ID, id).apply();
    }

    public static Conversation create(String title, String chatTitle) {
        Conversation conversation = new Conversation();
        conversation.id = "c" + System.currentTimeMillis();
        conversation.title = TextUtils.isEmpty(title) ? "Новый диалог" : title;
        conversation.chatTitle = chatTitle;
        conversation.createdAt = System.currentTimeMillis();
        conversation.updatedAt = conversation.createdAt;
        return conversation;
    }

    public static List<OmmaAiClient.Message> toMessages(Conversation conversation) {
        ArrayList<OmmaAiClient.Message> result = new ArrayList<>();
        if (conversation == null) {
            return result;
        }
        for (Entry entry : conversation.entries) {
            result.add(new OmmaAiClient.Message(entry.role, entry.text));
        }
        return result;
    }

    private static JSONObject serialize(Conversation conversation) {
        try {
            JSONObject object = new JSONObject();
            object.put("id", conversation.id);
            object.put("title", conversation.title);
            object.put("chatTitle", conversation.chatTitle == null ? "" : conversation.chatTitle);
            object.put("createdAt", conversation.createdAt);
            object.put("updatedAt", conversation.updatedAt);
            JSONArray entries = new JSONArray();
            for (Entry entry : conversation.entries) {
                JSONObject item = new JSONObject();
                item.put("role", entry.role);
                item.put("text", entry.text);
                entries.put(item);
            }
            object.put("entries", entries);
            return object;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static Conversation parse(JSONObject object) {
        if (object == null) {
            return null;
        }
        Conversation conversation = new Conversation();
        conversation.id = object.optString("id");
        if (TextUtils.isEmpty(conversation.id)) {
            return null;
        }
        conversation.title = object.optString("title", "Диалог");
        conversation.chatTitle = object.optString("chatTitle", "");
        conversation.createdAt = object.optLong("createdAt");
        conversation.updatedAt = object.optLong("updatedAt", conversation.createdAt);
        JSONArray entries = object.optJSONArray("entries");
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String role = item.optString("role", "assistant");
                String text = item.optString("text", "");
                if (!TextUtils.isEmpty(text)) {
                    conversation.entries.add(new Entry(role, text));
                }
            }
        }
        return conversation;
    }
}
