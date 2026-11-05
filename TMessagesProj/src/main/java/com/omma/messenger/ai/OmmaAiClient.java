package com.omma.messenger.ai;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal client for OpenAI-compatible APIs. Callbacks always run on the UI thread. */
public final class OmmaAiClient {

    public interface Callback<T> {
        void onResult(T result, String error);
    }

    public static final class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    private static final ExecutorService executor = Executors.newFixedThreadPool(2);

    private OmmaAiClient() {
    }

    public static void fetchModels(String baseUrl, String apiKey, Callback<List<String>> callback) {
        executor.execute(() -> {
            try {
                boolean horde = normalize(baseUrl).contains("aihorde.net/api/v2");
                String body = request(normalize(baseUrl) + (horde ? "/status/models?type=text" : "/models"), apiKey, null);
                JSONArray array;
                if (horde) {
                    array = new JSONArray(body);
                } else {
                    JSONObject json = new JSONObject(body);
                    array = json.optJSONArray("data");
                    if (array == null) {
                        array = json.optJSONArray("models");
                    }
                }
                ArrayList<String> ids = new ArrayList<>();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        Object item = array.get(i);
                        String id;
                        if (item instanceof JSONObject) {
                            JSONObject model = (JSONObject) item;
                            id = model.optString("id", model.optString("name"));
                        } else {
                            id = String.valueOf(item);
                        }
                        if (!TextUtils.isEmpty(id)) {
                            ids.add(id);
                        }
                    }
                }
                Collections.sort(ids);
                AndroidUtilities.runOnUIThread(() -> callback.onResult(ids, null));
            } catch (Exception e) {
                String error = describe(e);
                AndroidUtilities.runOnUIThread(() -> callback.onResult(null, error));
            }
        });
    }

    /** Fetches a public catalog without an API key, for providers that expose one. */
    public static void fetchPublicModels(String baseUrl, Callback<List<String>> callback) {
        fetchModels(baseUrl, "", callback);
    }

    public static void chat(List<Message> messages, Callback<String> callback) {
        final String baseUrl = OmmaAiConfig.baseUrl;
        final String apiKey = OmmaAiConfig.apiKey;
        final String model = OmmaAiConfig.model;
        final float temperature = OmmaAiConfig.temperature;

        executor.execute(() -> {
            try {
                if ("aihorde".equals(OmmaAiConfig.provider)) {
                    chatWithAiHorde(messages, callback);
                    return;
                }
                JSONArray array = new JSONArray();
                for (Message message : messages) {
                    JSONObject item = new JSONObject();
                    item.put("role", message.role);
                    item.put("content", message.content);
                    array.put(item);
                }
                JSONObject payload = new JSONObject();
                payload.put("model", model);
                payload.put("messages", array);
                payload.put("temperature", (double) temperature);

                String body = request(normalize(baseUrl) + "/chat/completions", apiKey, payload.toString());
                JSONArray choices = new JSONObject(body).optJSONArray("choices");
                String answer = "";
                if (choices != null && choices.length() > 0) {
                    JSONObject message = choices.getJSONObject(0).optJSONObject("message");
                    if (message != null) {
                        answer = message.optString("content");
                    }
                }
                if (TextUtils.isEmpty(answer)) {
                    AndroidUtilities.runOnUIThread(() -> callback.onResult(null, "Пустой ответ модели"));
                } else {
                    final String result = answer;
                    AndroidUtilities.runOnUIThread(() -> callback.onResult(result, null));
                }
            } catch (Exception e) {
                String error = describe(e);
                AndroidUtilities.runOnUIThread(() -> callback.onResult(null, error));
            }
        });
    }

    private static void chatWithAiHorde(List<Message> messages, Callback<String> callback) throws Exception {
        StringBuilder prompt = new StringBuilder();
        for (Message message : messages) {
            prompt.append(message.role).append(": ").append(message.content).append("\n");
        }
        JSONObject payload = new JSONObject();
        payload.put("prompt", prompt.toString());
        payload.put("models", new JSONArray().put(OmmaAiConfig.model));
        JSONObject params = new JSONObject();
        params.put("max_context_length", 4096);
        params.put("max_length", 512);
        params.put("temperature", (double) OmmaAiConfig.temperature);
        payload.put("params", params);

        String requestBody = request("https://aihorde.net/api/v2/generate/text/async", "0000000000", payload.toString(), "apikey");
        String id = new JSONObject(requestBody).optString("id");
        if (TextUtils.isEmpty(id)) {
            throw new IOException("AI Horde не вернул id запроса");
        }
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(2500L);
            String statusBody = request("https://aihorde.net/api/v2/generate/text/status/" + id, "", null);
            JSONObject status = new JSONObject(statusBody);
            if (status.optBoolean("done")) {
                JSONArray generations = status.optJSONArray("generations");
                if (generations != null && generations.length() > 0) {
                    String result = generations.getJSONObject(0).optString("text");
                    if (!TextUtils.isEmpty(result)) {
                        AndroidUtilities.runOnUIThread(() -> callback.onResult(result.trim(), null));
                        return;
                    }
                }
                throw new IOException("AI Horde вернул пустой ответ");
            }
        }
        throw new IOException("AI Horde отвечает слишком долго");
    }

    private static String normalize(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String describe(Exception e) {
        return TextUtils.isEmpty(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String request(String url, String apiKey, String jsonBody) throws IOException {
        return request(url, apiKey, jsonBody, "Authorization");
    }

    private static String request(String url, String apiKey, String jsonBody, String keyHeader) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            if (!TextUtils.isEmpty(apiKey)) {
                connection.setRequestProperty(keyHeader, "Authorization".equals(keyHeader) ? "Bearer " + apiKey : apiKey);
            }
            if (jsonBody != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }
            }

            int code = connection.getResponseCode();
            boolean success = code >= 200 && code < 300;
            String body = readAll(success ? connection.getInputStream() : connection.getErrorStream());
            if (!success) {
                throw new IOException("HTTP " + code + errorDetail(body));
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private static String errorDetail(String body) {
        try {
            JSONObject error = new JSONObject(body).optJSONObject("error");
            String message = error == null ? "" : error.optString("message");
            return TextUtils.isEmpty(message) ? "" : ": " + message;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }
}
