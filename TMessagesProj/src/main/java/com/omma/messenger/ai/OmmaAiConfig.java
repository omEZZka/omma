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

/** Persistent settings of the Omma AI assistant. The API key is encrypted with Android Keystore. */
public final class OmmaAiConfig {

    public static final class Provider {
        public final String id;
        public final String title;
        public final String baseUrl;
        public final String keysUrl;
        public final boolean requiresApiKey;

        Provider(String id, String title, String baseUrl, String keysUrl) {
            this(id, title, baseUrl, keysUrl, true);
        }

        Provider(String id, String title, String baseUrl, String keysUrl, boolean requiresApiKey) {
            this.id = id;
            this.title = title;
            this.baseUrl = baseUrl;
            this.keysUrl = keysUrl;
            this.requiresApiKey = requiresApiKey;
        }
    }

    public static final class Template {
        public String name;
        public String prompt;

        public Template(String name, String prompt) {
            this.name = name;
            this.prompt = prompt;
        }
    }

    /** Named rewrite style: built-in or user-created (like re:gram AI Editor styles). */
    public static final class Style {
        public final String id;
        public final String title;
        public final String instruction;
        public final boolean builtIn;

        public Style(String id, String title, String instruction, boolean builtIn) {
            this.id = id;
            this.title = title;
            this.instruction = instruction;
            this.builtIn = builtIn;
        }
    }

    public static final String PROVIDER_CUSTOM = "custom";

    public static final Provider[] PROVIDERS = {
        new Provider("openai", "OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys"),
        new Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys"),
        new Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys"),
        new Provider("groq", "Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys"),
        new Provider("ollama", "Ollama на устройстве/в сети (без ключа)", "http://127.0.0.1:11434/v1", "", false),
        new Provider("lmstudio", "LM Studio на устройстве/в сети (без ключа)", "http://127.0.0.1:1234/v1", "", false),
        new Provider("aihorde", "AI Horde (общественные мощности, без ключа)", "https://aihorde.net/api/v2", "", false),
        new Provider("pollinations", "Pollinations (ключ нужен для генерации)", "https://gen.pollinations.ai/v1", "https://enter.pollinations.ai/keys"),
        new Provider(PROVIDER_CUSTOM, "Свой сервер (OpenAI-совместимый)", "", ""),
    };

    public static final String DEFAULT_SYSTEM_PROMPT =
        "Ты — Omma, встроенный ИИ-ассистент мессенджера. Отвечай кратко и по делу, на языке пользователя. Если дан контекст чата — учитывай его. "
        + "Если пользователь попросил запомнить предпочтение (стиль, тон, обращения) — кратко подтверди это отдельной строкой вида «Запомнила: …».";

    private static final Style[] BUILT_IN_STYLES = {
        new Style("business", "Деловой", "Перепиши текст в строго деловом стиле: нейтрально, вежливо, без сленга и эмодзи.", true),
        new Style("short", "Коротко", "Сожми текст до одной-двух коротких фраз. Убери всё второстепенное.", true),
        new Style("church", "Церковный/библейский", "Перепиши текст в стиле церковнославянской и библейской речи: торжественно, с архаичными оборотами («воистину», «аще», «о, да»), без кощунства.", true),
        new Style("viking", "Скандинавский/викингский", "Перепиши текст речью викинга: суровые образы саг, море, путь, руны, обращение к собеседнику как к воину.", true),
    };

    /** Chat access modes for the assistant's read tools. */
    public static final String ACCESS_ALL = "all";
    public static final String ACCESS_ALLOW = "allow";
    public static final String ACCESS_DENY = "deny";

    public static final String[] ACCESS_TITLES = {
        "Доступ ко всем чатам",
        "Только выбранные чаты",
        "Все чаты, кроме выбранных",
    };

    private static final String PREFS_NAME = "omma_ai";
    private static final float DEFAULT_TEMPERATURE = 0.7f;

    public static String provider;
    public static String baseUrl;
    public static String apiKey;
    public static String model;
    public static String systemPrompt;
    public static float temperature;
    public static boolean useContext;
    public static boolean fabEnabled;
    public static boolean allowProfanity;
    public static boolean showReasoning;
    /** 0 = fully transparent, 100 = fully opaque. Applies to the assistant chat surfaces. */
    public static int chatOpacityPercent;
    /** ACCESS_ALL / ACCESS_ALLOW / ACCESS_DENY. */
    public static String chatAccessMode;
    /** Dialog ids (as strings) for allow/deny modes. */
    public static final ArrayList<String> chatAllowList = new ArrayList<>();
    public static final ArrayList<String> chatDenyList = new ArrayList<>();
    /** Extra instructions the assistant learns from conversations, shown in settings. */
    public static String learnedPreferences;
    /** Saved button position as a fraction of the container, -1 means default. */
    public static float fabX;
    public static float fabY;
    public static final ArrayList<Template> templates = new ArrayList<>();
    /** User-created styles, persisted separately from the built-in ones. */
    public static final ArrayList<Style> customStyles = new ArrayList<>();

    static {
        load();
    }

    private OmmaAiConfig() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void load() {
        SharedPreferences p = prefs();
        provider = p.getString("provider", PROVIDERS[0].id);
        baseUrl = p.getString("baseUrl", PROVIDERS[0].baseUrl);
        String storedKey = p.getString("apiKey", "");
        apiKey = OmmaSecureStore.decrypt(storedKey);
        if (!TextUtils.isEmpty(storedKey) && !OmmaSecureStore.isEncrypted(storedKey)) {
            String encrypted = OmmaSecureStore.encrypt(apiKey);
            if (encrypted != null) {
                p.edit().putString("apiKey", encrypted).apply();
            }
        }
        model = p.getString("model", "");
        systemPrompt = p.getString("systemPrompt", DEFAULT_SYSTEM_PROMPT);
        temperature = p.getFloat("temperature", DEFAULT_TEMPERATURE);
        useContext = p.getBoolean("useContext", true);
        fabEnabled = p.getBoolean("fabEnabled", true);
        allowProfanity = p.getBoolean("allowProfanity", false);
        showReasoning = p.getBoolean("showReasoning", false);
        chatOpacityPercent = p.getInt("chatOpacityPercent", 78);
        chatAccessMode = p.getString("chatAccessMode", ACCESS_ALL);
        learnedPreferences = p.getString("learnedPreferences", "");
        fabX = p.getFloat("fabX", -1f);
        fabY = p.getFloat("fabY", -1f);

        chatAllowList.clear();
        chatDenyList.clear();
        loadList(p, "chatAllowList", chatAllowList);
        loadList(p, "chatDenyList", chatDenyList);

        templates.clear();
        String raw = p.getString("templates", null);
        if (raw != null) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    templates.add(new Template(item.optString("name"), item.optString("prompt")));
                }
            } catch (Exception e) {
                FileLog.e(e);
                templates.clear();
                addDefaultTemplates();
            }
        } else {
            addDefaultTemplates();
        }

        customStyles.clear();
        String stylesRaw = p.getString("customStyles", null);
        if (stylesRaw != null) {
            try {
                JSONArray array = new JSONArray(stylesRaw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    customStyles.add(new Style("user_" + i, item.optString("title"), item.optString("instruction"), false));
                }
            } catch (Exception e) {
                FileLog.e(e);
                customStyles.clear();
            }
        }
    }

    private static void loadList(SharedPreferences p, String key, ArrayList<String> target) {
        String raw = p.getString(key, null);
        if (raw == null) {
            return;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i);
                if (!TextUtils.isEmpty(value)) {
                    target.add(value);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void saveList(SharedPreferences.Editor editor, String key, ArrayList<String> source) {
        JSONArray array = new JSONArray();
        for (String item : source) {
            if (!TextUtils.isEmpty(item)) {
                array.put(item);
            }
        }
        editor.putString(key, array.toString());
    }

    private static void addDefaultTemplates() {
        templates.add(new Template("Кратко о чате", "Кратко перескажи суть последних сообщений в этом чате и выдели главное."));
        templates.add(new Template("Перевести", "Переведи последнее сообщение на русский язык. Если оно уже на русском — переведи на английский."));
        templates.add(new Template("Сменить тон", "Перепиши последнее сообщение в деловом, а затем в дружеском тоне."));
    }

    public static void save() {
        JSONArray array = new JSONArray();
        try {
            for (Template template : templates) {
                JSONObject item = new JSONObject();
                item.put("name", template.name);
                item.put("prompt", template.prompt);
                array.put(item);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        JSONArray stylesArray = new JSONArray();
        try {
            for (Style style : customStyles) {
                JSONObject item = new JSONObject();
                item.put("title", style.title);
                item.put("instruction", style.instruction);
                stylesArray.put(item);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        String encryptedKey = OmmaSecureStore.encrypt(apiKey);
        if (encryptedKey == null) {
            // Keystore unavailable on this device: keep the assistant working with the plain value.
            encryptedKey = apiKey;
        }
        SharedPreferences.Editor editor = prefs().edit();
        editor.putString("provider", provider)
            .putString("baseUrl", baseUrl)
            .putString("apiKey", encryptedKey)
            .putString("model", model)
            .putString("systemPrompt", systemPrompt)
            .putFloat("temperature", temperature)
            .putBoolean("useContext", useContext)
            .putBoolean("fabEnabled", fabEnabled)
            .putBoolean("allowProfanity", allowProfanity)
            .putBoolean("showReasoning", showReasoning)
            .putInt("chatOpacityPercent", chatOpacityPercent)
            .putString("chatAccessMode", chatAccessMode)
            .putString("learnedPreferences", learnedPreferences)
            .putFloat("fabX", fabX)
            .putFloat("fabY", fabY)
            .putString("templates", array.toString())
            .putString("customStyles", stylesArray.toString());
        saveList(editor, "chatAllowList", chatAllowList);
        saveList(editor, "chatDenyList", chatDenyList);
        editor.apply();
    }

    public static void saveFabPosition(float x, float y) {
        fabX = x;
        fabY = y;
        prefs().edit().putFloat("fabX", x).putFloat("fabY", y).apply();
    }

    /** Adds an observation about the user's taste to the learned-preferences note. */
    public static void addLearnedPreference(String observation) {
        if (TextUtils.isEmpty(observation)) {
            return;
        }
        String note = learnedPreferences == null ? "" : learnedPreferences;
        if (note.contains(observation)) {
            return;
        }
        note = note.isEmpty() ? observation : note + "\n" + observation;
        String[] lines = note.split("\n");
        StringBuilder trimmed = new StringBuilder();
        for (int i = Math.max(0, lines.length - 12); i < lines.length; i++) {
            if (trimmed.length() > 0) {
                trimmed.append('\n');
            }
            trimmed.append(lines[i]);
        }
        learnedPreferences = trimmed.toString();
        prefs().edit().putString("learnedPreferences", learnedPreferences).apply();
    }

    public static boolean isConfigured() {
        return !TextUtils.isEmpty(baseUrl) && !TextUtils.isEmpty(model)
            && (!findProvider(provider).requiresApiKey || !TextUtils.isEmpty(apiKey));
    }

    public static boolean requiresApiKey(String providerId) {
        return findProvider(providerId).requiresApiKey;
    }

    public static void saveDiscoveredModels(String providerId, List<String> models) {
        if (TextUtils.isEmpty(providerId) || models == null) {
            return;
        }
        JSONArray array = new JSONArray();
        for (String item : models) {
            if (!TextUtils.isEmpty(item)) {
                array.put(item);
            }
        }
        prefs().edit().putString("models_" + providerId, array.toString()).apply();
    }

    public static List<String> getDiscoveredModels(String providerId) {
        ArrayList<String> result = new ArrayList<>();
        String raw = prefs().getString("models_" + providerId, null);
        if (raw == null) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i);
                if (!TextUtils.isEmpty(value)) {
                    result.add(value);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    /** Built-in + user styles in menu order. */
    public static ArrayList<Style> allStyles() {
        ArrayList<Style> result = new ArrayList<>();
        for (Style style : BUILT_IN_STYLES) {
            result.add(style);
        }
        result.addAll(customStyles);
        return result;
    }

    public static Style findStyle(String id) {
        for (Style style : allStyles()) {
            if (style.id.equals(id)) {
                return style;
            }
        }
        return null;
    }

    public static boolean addCustomStyle(String title, String instruction) {
        if (TextUtils.isEmpty(title) || TextUtils.isEmpty(instruction) || customStyles.size() >= 10) {
            return false;
        }
        customStyles.add(new Style("user_" + customStyles.size(), title, instruction, false));
        save();
        return true;
    }

    public static void removeCustomStyle(Style style) {
        customStyles.remove(style);
        save();
    }

    public static Provider findProvider(String id) {
        for (Provider item : PROVIDERS) {
            if (item.id.equals(id)) {
                return item;
            }
        }
        return PROVIDERS[PROVIDERS.length - 1];
    }
}
