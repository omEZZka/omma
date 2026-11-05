package com.omma.messenger.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.Collections;
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

    /** A stable built-in model entry. It remains available even when a provider is offline. */
    public static final class ModelOption {
        public final String provider;
        public final String id;
        public final String title;
        public final String description;
        public final boolean free;
        public final boolean requiresApiKey;

        ModelOption(String provider, String id, String title, String description, boolean free, boolean requiresApiKey) {
            this.provider = provider;
            this.id = id;
            this.title = title;
            this.description = description;
            this.free = free;
            this.requiresApiKey = requiresApiKey;
        }

        @Override
        public String toString() {
            return title + " — " + id;
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
        new Provider("ollama", "Ollama на устройстве/в сети (без ключа)", "http://127.0.0.1:11434/v1", "", false),
        new Provider("lmstudio", "LM Studio на устройстве/в сети (без ключа)", "http://127.0.0.1:1234/v1", "", false),
        new Provider("aihorde", "AI Horde (общественные мощности, без ключа)", "https://aihorde.net/api/v2", "", false),
        new Provider("pollinations", "Pollinations (ключ нужен для генерации)", "https://gen.pollinations.ai/v1", "https://enter.pollinations.ai/keys"),
        new Provider(PROVIDER_CUSTOM, "Свой сервер (OpenAI-совместимый)", "", ""),
    };

    /**
     * Built-in catalog. Entries are intentionally not replaced by /models: this is the
     * always-visible list for offline providers and providers whose /models endpoint is limited.
     * "Free" means a provider's free tier, not an anonymous cloud API.
     */
    private static final ModelOption[] BUILT_IN_MODELS = {
        new ModelOption("openai", "gpt-4o-mini", "GPT-4o mini", "Быстрая модель OpenAI", false, true),
        new ModelOption("openai", "gpt-4.1-mini", "GPT-4.1 mini", "Сильнее для текста и кода", false, true),
        new ModelOption("deepseek", "deepseek-chat", "DeepSeek V3", "Недорогая универсальная модель", false, true),
        new ModelOption("deepseek", "deepseek-reasoner", "DeepSeek Reasoner", "Рассуждения и сложные задачи", false, true),
        new ModelOption("groq", "llama-3.3-70b-versatile", "Llama 3.3 70B (Groq)", "Бесплатный тариф Groq, нужен ключ", true, true),
        new ModelOption("groq", "llama-3.1-8b-instant", "Llama 3.1 8B (Groq)", "Очень быстрая, бесплатный тариф Groq", true, true),
        new ModelOption("groq", "qwen/qwen3-32b", "Qwen3 32B (Groq)", "Бесплатный тариф Groq, нужен ключ", true, true),
        new ModelOption("openrouter", "meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B (free)", "Бесплатный маршрут OpenRouter, нужен ключ", true, true),
        new ModelOption("openrouter", "deepseek/deepseek-r1:free", "DeepSeek R1 (free)", "Бесплатный маршрут OpenRouter, нужен ключ", true, true),
        new ModelOption("openrouter", "google/gemini-2.0-flash-exp:free", "Gemini Flash (free)", "Бесплатный маршрут OpenRouter, нужен ключ", true, true),
        new ModelOption("openrouter", "qwen/qwen-2.5-72b-instruct:free", "Qwen 2.5 72B (free)", "Бесплатный маршрут OpenRouter, нужен ключ", true, true),
        new ModelOption("ollama", "llama3.2", "Llama 3.2 (локально)", "Без API-ключа; нужен запущенный Ollama", true, false),
        new ModelOption("ollama", "qwen2.5:7b", "Qwen 2.5 7B (локально)", "Без API-ключа; модель скачивается в Ollama", true, false),
        new ModelOption("ollama", "gemma3:4b", "Gemma 3 4B (локально)", "Без API-ключа; нужен запущенный Ollama", true, false),
        new ModelOption("lmstudio", "local-model", "Текущая модель LM Studio", "Без API-ключа; имя модели задаётся в LM Studio", true, false),
        new ModelOption("aihorde", "koboldcpp/gemma-4-31B-it-heretic", "Gemma 4 31B (AI Horde)", "Бесплатно, без ключа; очередь зависит от волонтёров", true, false),
        new ModelOption("aihorde", "koboldcpp/Qwen3-4B", "Qwen 3 4B (AI Horde)", "Бесплатно, без ключа; очередь зависит от волонтёров", true, false),
        new ModelOption("pollinations", "openai/gpt-oss-20b", "GPT OSS 20B (Pollinations)", "У Pollinations есть бесплатные лимиты/тарифы, ключ нужен", true, true),
        new ModelOption("pollinations", "qwen/qwen3-coder-30b-a3b-instruct", "Qwen Coder (Pollinations)", "Для кода; ключ Pollinations нужен", true, true),
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
        return !TextUtils.isEmpty(baseUrl) && !TextUtils.isEmpty(model)
            && (!findProvider(provider).requiresApiKey || !TextUtils.isEmpty(apiKey));
    }

    public static boolean requiresApiKey(String providerId) {
        return findProvider(providerId).requiresApiKey;
    }

    public static List<ModelOption> getModelCatalog() {
        ArrayList<ModelOption> result = new ArrayList<>();
        Collections.addAll(result, BUILT_IN_MODELS);
        return result;
    }

    public static List<ModelOption> getModelCatalog(String providerId) {
        ArrayList<ModelOption> result = new ArrayList<>();
        for (ModelOption option : BUILT_IN_MODELS) {
            if (option.provider.equals(providerId)) {
                result.add(option);
            }
        }
        return result;
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

    public static ModelOption findModel(String providerId, String modelId) {
        for (ModelOption option : BUILT_IN_MODELS) {
            if (option.provider.equals(providerId) && option.id.equals(modelId)) {
                return option;
            }
        }
        return null;
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
