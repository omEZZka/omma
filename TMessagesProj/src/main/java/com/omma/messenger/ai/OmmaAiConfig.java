package com.omma.messenger.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;

/** Persistent settings of the Omma AI assistant. The API key is encrypted with Android Keystore. */
public final class OmmaAiConfig {

    public static final class Provider {
        public final String id;
        public final String title;
        public final String baseUrl;
        public final String keysUrl;

        Provider(String id, String title, String baseUrl, String keysUrl) {
            this.id = id;
            this.title = title;
            this.baseUrl = baseUrl;
            this.keysUrl = keysUrl;
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

    public static final String PROVIDER_CUSTOM = "custom";

    public static final Provider[] PROVIDERS = {
        new Provider("openai", "OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys"),
        new Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys"),
        new Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys"),
        new Provider("groq", "Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys"),
        new Provider(PROVIDER_CUSTOM, "Свой сервер (OpenAI-совместимый)", "", ""),
    };

    public static final String DEFAULT_SYSTEM_PROMPT =
        "Ты — Omma, встроенный ИИ-ассистент мессенджера. Отвечай кратко и по делу, на языке пользователя. Если дан контекст чата — учитывай его.";

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
    /** Saved button position as a fraction of the container, -1 means default. */
    public static float fabX;
    public static float fabY;
    public static final ArrayList<Template> templates = new ArrayList<>();

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
        fabX = p.getFloat("fabX", -1f);
        fabY = p.getFloat("fabY", -1f);

        templates.clear();
        String raw = p.getString("templates", null);
        if (raw != null) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    templates.add(new Template(item.optString("name"), item.optString("prompt")));
                }
                return;
            } catch (Exception e) {
                FileLog.e(e);
                templates.clear();
            }
        }
        addDefaultTemplates();
    }

    private static void addDefaultTemplates() {
        templates.add(new Template("Кратко о чате", "Кратко перескажи суть последних сообщений в этом чате и выдели главное."));
        templates.add(new Template("Предложить ответ", "Предложи 3 варианта ответа на последнее сообщение: короткий, дружеский и деловой."));
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
        String encryptedKey = OmmaSecureStore.encrypt(apiKey);
        if (encryptedKey == null) {
            // Keystore unavailable on this device: keep the assistant working with the plain value.
            encryptedKey = apiKey;
        }
        prefs().edit()
            .putString("provider", provider)
            .putString("baseUrl", baseUrl)
            .putString("apiKey", encryptedKey)
            .putString("model", model)
            .putString("systemPrompt", systemPrompt)
            .putFloat("temperature", temperature)
            .putBoolean("useContext", useContext)
            .putBoolean("fabEnabled", fabEnabled)
            .putFloat("fabX", fabX)
            .putFloat("fabY", fabY)
            .putString("templates", array.toString())
            .apply();
    }

    public static void saveFabPosition(float x, float y) {
        fabX = x;
        fabY = y;
        prefs().edit().putFloat("fabX", x).putFloat("fabY", y).apply();
    }

    public static boolean isConfigured() {
        return !TextUtils.isEmpty(baseUrl) && !TextUtils.isEmpty(apiKey) && !TextUtils.isEmpty(model);
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
