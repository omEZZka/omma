package com.omma.messenger.ai;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;

/**
 * Process-wide assistant brain. Requests run here, not on the screen: closing the chat does not
 * cancel generation. When the answer arrives it is appended to the persistent conversation, and
 * any open OmmaAiChatActivity picks it up through the listener.
 *
 * Also assembles the effective request payload: system prompt, profanity toggle, learned
 * preferences and chat context.
 */
public final class OmmaAiEngine {

    public interface Listener {
        void onAnswerFinished();
    }

    private static final OmmaAiEngine instance = new OmmaAiEngine();

    public static OmmaAiEngine get() {
        return instance;
    }

    private OmmaAiEngine() {
    }

    private final ArrayList<String> runningConversationIds = new ArrayList<>();
    private OmmaAiEngine.Listener listener;

    public void setListener(Listener value) {
        listener = value;
    }

    public boolean isRunning(String conversationId) {
        return conversationId != null && runningConversationIds.contains(conversationId);
    }

    public void request(OmmaAiHistory.Conversation conversation, String prompt, String chatTitle, String chatContext) {
        if (!OmmaAiConfig.isConfigured() || conversation == null || isRunning(conversation.id)) {
            return;
        }
        runningConversationIds.add(conversation.id);
        String conversationId = conversation.id;

        ArrayList<OmmaAiClient.Message> request = new ArrayList<>();
        String systemPrompt = !TextUtils.isEmpty(OmmaAiConfig.systemPrompt)
            ? OmmaAiConfig.systemPrompt : OmmaAiConfig.DEFAULT_SYSTEM_PROMPT;
        if (OmmaAiConfig.allowProfanity) {
            systemPrompt += "\nРазрешено и уместно использовать мат и грубый просторечный язык, если это соответствует тону беседы.";
        }
        if (!TextUtils.isEmpty(OmmaAiConfig.learnedPreferences)) {
            systemPrompt += "\nПредпочтения пользователя (запомнены из прошлых разговоров):\n" + OmmaAiConfig.learnedPreferences;
        }
        request.add(new OmmaAiClient.Message("system", systemPrompt));
        if (!TextUtils.isEmpty(chatContext)) {
            request.add(new OmmaAiClient.Message("system", "Контекст текущего чата «" + chatTitle + "»:\n" + chatContext));
        }
        // Replay the whole stored conversation except the message we are about to append.
        for (OmmaAiHistory.Entry entry : conversation.entries) {
            if ("user".equals(entry.role) && entry.text.equals(prompt)) {
                continue;
            }
            request.add(new OmmaAiClient.Message(entry.role, entry.text));
        }
        request.add(new OmmaAiClient.Message("user", prompt));

        OmmaAiClient.chat(request, (answer, error) -> {
            runningConversationIds.remove(conversationId);
            OmmaAiHistory.Conversation stored = OmmaAiHistory.load(conversationId);
            if (stored == null) {
                notifyListener();
                return;
            }
            if (error != null) {
                stored.entries.add(new OmmaAiHistory.Entry("assistant", "Ошибка: " + error));
            } else {
                stored.entries.add(new OmmaAiHistory.Entry("assistant", answer));
                learnPreferences(answer);
            }
            stored.updatedAt = System.currentTimeMillis();
            OmmaAiHistory.save(stored);
            notifyListener();
        });
    }

    /** Persists taste hints the model reports as «Запомнила: …». */
    private static void learnPreferences(String answer) {
        if (TextUtils.isEmpty(answer)) {
            return;
        }
        int index = answer.indexOf("Запомнила:");
        if (index < 0) {
            return;
        }
        int end = answer.indexOf('\n', index);
        String line = end < 0 ? answer.substring(index) : answer.substring(index, end);
        line = line.replace("Запомнила:", "").replace("Запомнила", "").trim();
        if (!line.isEmpty()) {
            OmmaAiConfig.addLearnedPreference(line);
        }
    }

    private void notifyListener() {
        if (listener != null) {
            try {
                listener.onAnswerFinished();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }
}
