package com.omma.messenger.ai;

import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat tools the assistant can call mid-conversation (function calling).
 *
 * Access is limited by the chatAccessMode + chatAllowList/chatDenyList in OmmaAiConfig:
 *  - "all": read every dialog
 *  - "allow": only dialogs whose id is in chatAllowList
 *  - "deny": every dialog except chatDenyList
 *
 * Reads are cache-only where possible; search hits the server with the standard TL_messages_search.
 */
public final class OmmaChatTools {

    public static final String MODE_ALL = "all";
    public static final String MODE_ALLOW = "allow";
    public static final String MODE_DENY = "deny";

    private OmmaChatTools() {
    }

    public static class ToolResult {
        public final String text;

        public ToolResult(String text) {
            this.text = text;
        }
    }

    /** Titles of dialogs the assistant may read. Empty list with MODE_ALLOW means "nothing". */
    public static boolean canAccess(long dialogId) {
        String mode = OmmaAiConfig.chatAccessMode;
        ArrayList<String> allow = OmmaAiConfig.chatAllowList;
        ArrayList<String> deny = OmmaAiConfig.chatDenyList;
        if (MODE_ALLOW.equals(mode)) {
            return allow != null && allow.contains(String.valueOf(dialogId));
        }
        if (MODE_DENY.equals(mode)) {
            return deny == null || !deny.contains(String.valueOf(dialogId));
        }
        return true;
    }

    /** JSON array of accessible dialogs: [{id, title, type, lastMessage}]. */
    public static String listChatsJson() {
        AccountInstance account = AccountInstance.getInstance(UserConfig.selectedAccount);
        MessagesController controller = account.getMessagesController();
        JSONArrayCompat array = new JSONArrayCompat();
        int limit = 60;
        for (TLRPC.Dialog dialog : new ArrayList<>(controller.allDialogs)) {
            if (limit-- <= 0) {
                break;
            }
            if (dialog == null || !canAccess(dialog.id)) {
                continue;
            }
            String title = dialogTitle(account, dialog.id);
            String last = dialog.last_message != null ? messageText(dialog.last_message, account) : "";
            array.put(new JSONObjectCompat()
                .put("id", String.valueOf(dialog.id))
                .put("title", title)
                .put("type", dialog.id > 0 ? "private" : "group")
                .put("lastMessage", last));
        }
        return array.toString();
    }

    /** Loads up to count recent messages of a dialog from the local cache (synchronous). */
    public static ToolResult readChat(String dialogIdString, int count) {
        AccountInstance account = AccountInstance.getInstance(UserConfig.selectedAccount);
        long dialogId;
        try {
            dialogId = Long.parseLong(dialogIdString.trim());
        } catch (NumberFormatException e) {
            return new ToolResult("Ошибка: id чата должен быть числом из list_chats");
        }
        if (!canAccess(dialogId)) {
            return new ToolResult("Ошибка: доступ к этому чату запрещён настройками приватности");
        }
        final int MAX = 50;
        int finalCount = Math.max(1, Math.min(count, MAX));
        ArrayList<MessageObject> out = new ArrayList<>();
        final Object lock = new Object();
        int classGuid = account.getConnectionsManager().generateClassGuid();
        NotificationCenter.NotificationCenterDelegate listener = (id, account1, args) -> {
            if (id == NotificationCenter.messagesDidLoad && args.length > 2 && args[2] instanceof ArrayList) {
                synchronized (lock) {
                    ArrayList<MessageObject> objects = (ArrayList<MessageObject>) args[2];
                    out.addAll(objects);
                    lock.notifyAll();
                }
            }
        };
        NotificationCenter center = account.getNotificationCenter();
        center.addObserver(listener, NotificationCenter.messagesDidLoad);
        try {
            account.getMessagesController().loadMessages(dialogId, 0, false, finalCount, 0, 0, true, 0, classGuid, 0, 0, 0, 0, 0, false);
            synchronized (lock) {
                lock.wait(4000);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            center.removeObserver(listener, NotificationCenter.messagesDidLoad);
            account.getConnectionsManager().cancelRequestsForGuid(classGuid);
        }
        if (out.isEmpty()) {
            return new ToolResult("Ошибка: чат пуст или ещё не загружен в кеш. Скажи пользователю открыть этот чат один раз.");
        }
        MessagesController controller = account.getMessagesController();
        StringBuilder builder = new StringBuilder("Сообщения чата «").append(dialogTitle(account, dialogId)).append("» (старые → новые):\n");
        for (int i = out.size() - 1; i >= 0; i--) {
            MessageObject message = out.get(i);
            if (message == null || message.messageText == null || message.isDateObject) {
                continue;
            }
            String text = message.messageText.toString();
            if (text.isEmpty()) {
                text = "[вложение]";
            }
            if (text.length() > 600) {
                text = text.substring(0, 600) + "…";
            }
            builder.append(senderName(account, controller, message)).append(": ").append(text).append('\n');
            if (i < out.size() - MAX) {
                break;
            }
        }
        return new ToolResult(builder.toString());
    }

    /** Server-side search inside one accessible dialog. */
    public static void searchInChat(String dialogIdString, String query, int limit, SearchCallback callback) {
        AccountInstance account = AccountInstance.getInstance(UserConfig.selectedAccount);
        long dialogId;
        try {
            dialogId = Long.parseLong(dialogIdString.trim());
        } catch (NumberFormatException e) {
            AndroidUtilities.runOnUIThread(() -> callback.onResult(new ToolResult("Ошибка: id чата должен быть числом из list_chats")));
            return;
        }
        if (!canAccess(dialogId)) {
            AndroidUtilities.runOnUIThread(() -> callback.onResult(new ToolResult("Ошибка: доступ к этому чату запрещён настройками приватности")));
            return;
        }
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = account.getMessagesController().getInputPeer(dialogId);
        req.q = query == null ? "" : query;
        req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
        req.limit = Math.max(1, Math.min(limit, 20));
        req.offset_id = 0;
        req.max_id = 0;
        req.min_date = 0;
        req.max_date = 0;
        req.add_offset = 0;
        req.hash = 0;
        account.getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                callback.onResult(new ToolResult("Ошибка поиска: " + error.text));
                return;
            }
            if (!(response instanceof TLRPC.messages_Messages)) {
                callback.onResult(new ToolResult("Ошибка поиска: неожиданный ответ сервера"));
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            StringBuilder builder = new StringBuilder("Найдено ").append(res.messages.size()).append(" в чате «")
                .append(dialogTitle(account, dialogId)).append("»:\n");
            for (int i = res.messages.size() - 1; i >= 0; i--) {
                TLRPC.Message message = res.messages.get(i);
                if (message == null || message.message == null) {
                    continue;
                }
                builder.append(senderName(account, account.getMessagesController(), new MessageObject(account.getCurrentAccount(), message, false, true)))
                    .append(": ").append(message.message).append('\n');
            }
            callback.onResult(new ToolResult(builder.toString()));
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public interface SearchCallback {
        void onResult(ToolResult result);
    }

    // ---------- helpers ----------

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

    private static String senderName(AccountInstance account, MessagesController controller, MessageObject message) {
        if (message.isOut()) {
            return "Я";
        }
        long senderId = message.getSenderId();
        if (senderId > 0) {
            TLRPC.User user = controller.getUser(senderId);
            if (user != null) {
                return UserObject.getUserName(user);
            }
        } else if (senderId < 0) {
            TLRPC.Chat chat = controller.getChat(-senderId);
            if (chat != null) {
                return chat.title;
            }
        }
        return "Собеседник";
    }

    private static String messageText(TLRPC.Message message, AccountInstance account) {
        if (message == null) {
            return "";
        }
        if (message.message != null && !message.message.isEmpty()) {
            return message.message;
        }
        return "[вложение]";
    }

    // Tiny JSON helpers so the file has no org.json clutter at call sites.

    private static class JSONObjectCompat {
        private final org.json.JSONObject object = new org.json.JSONObject();

        JSONObjectCompat put(String key, Object value) {
            try {
                object.put(key, value);
            } catch (Exception ignored) {
            }
            return this;
        }

        @Override
        public String toString() {
            return object.toString();
        }
    }

    private static class JSONArrayCompat {
        private final org.json.JSONArray array = new org.json.JSONArray();

        void put(JSONObjectCompat item) {
            array.put(item.object);
        }

        @Override
        public String toString() {
            return array.toString();
        }
    }
}
