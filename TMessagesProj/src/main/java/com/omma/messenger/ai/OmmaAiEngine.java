package com.omma.messenger.ai;

import android.text.TextUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

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

        // Function calling, step 1: the user prompt may name a chat tool. If it does, run the
        // tool first and prepend its result to the request so the model answers from real data.
        ArrayList<OmmaAiClient.Message> request = new ArrayList<>();
        String systemPrompt = !TextUtils.isEmpty(OmmaAiConfig.systemPrompt)
            ? OmmaAiConfig.systemPrompt : OmmaAiConfig.DEFAULT_SYSTEM_PROMPT;
        systemPrompt += "\nТебе доступны инструменты чатов: list_chats (список чатов с id), read_chat (последние сообщения чата по id), search_in_chat (поиск по чату по id и запросу). "
            + "Если пользователь просит что-то про другой чат — сначала вызови нужный инструмент, работай только с его данными.";
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

        resolveTools(prompt, toolResult -> {
            if (toolResult != null) {
                request.add(new OmmaAiClient.Message("system", "Результат инструмента:\n" + toolResult.text));
            }
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
        });
    }

    /** Recognizes tool requests in the prompt; runs them and returns the raw result. */
    private void resolveTools(String prompt, ToolCallback callback) {
        String lower = prompt.toLowerCase();
        long dialogId = matchDialogId(prompt);
        boolean wantsList = lower.contains("список чат") || lower.contains("какие чаты") || lower.contains("list_chats")
            || lower.contains("перечисли чаты");
        boolean wantsSearch = dialogId != Long.MIN_VALUE && (lower.contains("найди") || lower.contains("поиск") || lower.contains("search_in_chat"));
        boolean wantsRead = dialogId != Long.MIN_VALUE && (lower.contains("прочитай") || lower.contains("читай")
            || lower.contains("что в чате") || lower.contains("последние сообщени") || lower.contains("read_chat"));

        if (wantsList) {
            callback.onResult(new OmmaChatTools.ToolResult(OmmaChatTools.listChatsJson()));
            return;
        }
        if (dialogId != Long.MIN_VALUE && (wantsRead || wantsSearch)) {
            String idString = String.valueOf(dialogId);
            if (wantsSearch) {
                String query = extractSearchQuery(prompt);
                OmmaChatTools.searchInChat(idString, query, 10, result -> AndroidUtilities.runOnUIThread(() -> callback.onResult(result)));
            } else {
                AndroidUtilities.runOnUIThread(() -> callback.onResult(OmmaChatTools.readChat(idString, 40)));
            }
            return;
        }
        // List names of chats when the user references a chat by title, so the model can map it to id.
        if (lower.contains("чат") && (lower.contains("в чате") || lower.contains("из чата"))) {
            callback.onResult(new OmmaChatTools.ToolResult(OmmaChatTools.listChatsJson()));
            return;
        }
        callback.onResult(null);
    }

    /** Finds a dialog by id in the prompt or by title match ("в чате Петя"). */
    private static long matchDialogId(String prompt) {
        java.util.regex.Matcher digits = java.util.regex.Pattern.compile("(?:id|ид|чат)\\s*[:#]?\\s*(-?\\d{6,})").matcher(prompt);
        if (digits.find()) {
            try {
                return Long.parseLong(digits.group(1));
            } catch (NumberFormatException ignore) {
            }
        }
        AccountInstance account = AccountInstance.getInstance(UserConfig.selectedAccount);
        ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>(account.getMessagesController().getDialogs(0));
        String lower = prompt.toLowerCase();
        for (TLRPC.Dialog dialog : dialogs) {
            String title = dialogTitle(account, dialog.id).toLowerCase();
            if (title.length() > 3 && lower.contains("чате " + title) || lower.contains("чат «" + title + "»")
                || lower.contains("читай " + title) || lower.contains("в «" + title + "»")) {
                return dialog.id;
            }
        }
        return Long.MIN_VALUE;
    }

    private static String extractSearchQuery(String prompt) {
        java.util.regex.Matcher quoted = java.util.regex.Pattern.compile("[«\"]([^«»\"]+)[»\"]").matcher(prompt);
        if (quoted.find()) {
            return quoted.group(1);
        }
        java.util.regex.Matcher after = java.util.regex.Pattern.compile("(?:найди|поиск|поищи)\\s+(?:в\\s+\\S+\\s+)?(.{2,60})").matcher(prompt);
        if (after.find()) {
            return after.group(1).trim();
        }
        return "";
    }

    public interface ToolCallback {
        void onResult(OmmaChatTools.ToolResult result);
    }

    private static String dialogTitle(AccountInstance account, long dialogId) {
        try {
            if (dialogId > 0) {
                TLRPC.User user = account.getMessagesController().getUser(dialogId);
                return user != null ? UserObject.getUserName(user) : ("user" + dialogId);
            }
            TLRPC.Chat chat = account.getMessagesController().getChat(-dialogId);
            return chat != null && !TextUtils.isEmpty(chat.title) ? chat.title : ("chat" + dialogId);
        } catch (Exception e) {
            return "чат " + dialogId;
        }
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
