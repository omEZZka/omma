package com.omma.messenger.ai;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Full screen assistant conversation.
 *
 * A regular fragment (not a bottom sheet) is used on purpose: the message list must scroll freely
 * without the surrounding container interpreting the drag as "dismiss".
 */
public class OmmaAiChatActivity extends BaseFragment {

    private static final int MENU_HISTORY = 1;
    private static final int MENU_NEW = 2;
    private static final int MENU_CLEAR = 3;
    private static final int MENU_PROMPTS = 4;
    private static final int CONTEXT_MESSAGES = 20;
    private static final int ACCENT = 0xFF2563EB;

    private ChatActivity chat;
    private String chatTitle;

    private ScrollView scrollView;
    private LinearLayout messagesLayout;
    private EditText input;
    private TextView sendButton;
    private FrameLayout emptyState;
    private OmmaThinkingView thinkingView;

    private OmmaAiHistory.Conversation conversation;
    private boolean busy;

    public OmmaAiChatActivity() {
    }

    public OmmaAiChatActivity(ChatActivity chat) {
        this.chat = chat;
    }

    @Override
    public boolean onFragmentCreate() {
        if (chat == null) {
            BaseFragment last = org.telegram.ui.LaunchActivity.getSafeLastFragment();
            if (last instanceof ChatActivity) {
                chat = (ChatActivity) last;
            }
        }
        chatTitle = chat == null ? null : resolveChatTitle(chat);
        restoreConversation();
        return super.onFragmentCreate();
    }

    private void restoreConversation() {
        String activeId = OmmaAiHistory.getActiveId();
        conversation = activeId == null ? null : OmmaAiHistory.load(activeId);
        if (conversation == null) {
            conversation = OmmaAiHistory.create(defaultTitle(), chatTitle);
            OmmaAiHistory.save(conversation);
            OmmaAiHistory.setActiveId(conversation.id);
        }
    }

    private String defaultTitle() {
        if (chatTitle != null) {
            return "Чат: " + chatTitle;
        }
        return "Диалог " + new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(new Date());
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Omma AI");
        actionBar.setSubtitle(chatTitle != null ? "Контекст: " + chatTitle : "Без контекста чата");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_HISTORY) {
                    openHistory();
                } else if (id == MENU_NEW) {
                    startNewConversation();
                } else if (id == MENU_CLEAR) {
                    confirmClear();
                } else if (id == MENU_PROMPTS) {
                    showPromptsMenu();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem overflow = menu.addItem(0, R.drawable.ic_ab_other);
        if (overflow != null) {
            overflow.addSubItem(MENU_PROMPTS, R.drawable.msg_search, "Подсказки");
            overflow.addSubItem(MENU_HISTORY, R.drawable.msg_search, "История");
            overflow.addSubItem(MENU_NEW, R.drawable.msg_clear_input, "Новый диалог");
            overflow.addSubItem(MENU_CLEAR, R.drawable.msg_delete, "Удалить историю");
        }

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        scrollView = new ScrollView(context);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
        messagesLayout = new LinearLayout(context);
        messagesLayout.setOrientation(LinearLayout.VERTICAL);
        messagesLayout.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(4), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        scrollView.addView(messagesLayout, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        column.addView(buildInputRow(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        emptyState = buildEmptyState(context);
        root.addView(emptyState, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 0, 24, 60));

        renderConversation();
        fragmentView = root;
        return root;
    }

    private View buildInputRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);

        input = new EditText(context);
        input.setHint(chatTitle != null ? "Спроси про этот чат…" : "Спроси что-нибудь…");
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(5);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10));
        input.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundGray), 20));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendFromInput();
                return true;
            }
            return false;
        });
        row.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        sendButton = new TextView(context);
        sendButton.setText("↑");
        sendButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        sendButton.setTypeface(AndroidUtilities.bold());
        sendButton.setTextColor(0xFFFFFFFF);
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setBackground(rounded(ACCENT, 22));
        sendButton.setOnClickListener(v -> sendFromInput());
        row.addView(sendButton, LayoutHelper.createLinear(44, 44, 8, 0, 0, 0));
        return row;
    }

    private FrameLayout buildEmptyState(Context context) {
        FrameLayout container = new FrameLayout(context);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(16), AndroidUtilities.dp(18), AndroidUtilities.dp(16));
        GradientDrawable background = rounded(Theme.getColor(Theme.key_windowBackgroundGray), 18);
        background.setStroke(AndroidUtilities.dp(1), 0x222563EB);
        card.setBackground(background);

        TextView title = new TextView(context);
        title.setText(chatTitle != null
            ? "Я вижу чат «" + chatTitle + "». Могу пересказать, перевести или предложить ответ."
            : "Я ассистент Omma. Открой чат, чтобы я видел его контекст, или спроси что угодно.");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        card.addView(title);

        TextView hint = new TextView(context);
        hint.setText("Подсказки — в меню «⋮» сверху справа");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        hint.setPadding(0, AndroidUtilities.dp(8), 0, 0);
        card.addView(hint);

        TextView prompts = createPill(context, "Подсказки ⌄");
        prompts.setOnClickListener(v -> showPromptsMenu());
        card.addView(prompts, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 12, 0, 0));

        container.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        return container;
    }

    // ---------- rendering ----------

    private void renderConversation() {
        if (messagesLayout == null) {
            return;
        }
        messagesLayout.removeAllViews();
        for (OmmaAiHistory.Entry entry : conversation.entries) {
            boolean user = "user".equals(entry.role);
            TextView bubble = addBubble(entry.text, user);
            if (!user) {
                attachAnswerActions(bubble, entry.text);
            }
        }
        updateEmptyState();
        scrollToBottom();
    }

    private void updateEmptyState() {
        if (emptyState != null) {
            emptyState.setVisibility(conversation.entries.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private TextView addBubble(String text, boolean user) {
        TextView bubble = new TextView(getContext());
        bubble.setText(text);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        bubble.setTextIsSelectable(true);
        bubble.setLineSpacing(0, 1.15f);
        bubble.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(9), AndroidUtilities.dp(12), AndroidUtilities.dp(9));
        if (user) {
            bubble.setTextColor(0xFFFFFFFF);
            bubble.setBackground(rounded(ACCENT, 16));
        } else {
            bubble.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            bubble.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundGray), 16));
        }
        LinearLayout.LayoutParams params = LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
            user ? Gravity.RIGHT : Gravity.LEFT,
            user ? 48 : 0, 0, user ? 0 : 48, 6);
        messagesLayout.addView(bubble, params);
        scrollToBottom();
        return bubble;
    }

    private void scrollToBottom() {
        if (scrollView != null) {
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void attachAnswerActions(TextView bubble, String answer) {
        bubble.setOnLongClickListener(v -> {
            AndroidUtilities.addToClipboard(answer);
            Toast.makeText(getContext(), "Скопировано", Toast.LENGTH_SHORT).show();
            return true;
        });
        if (chat == null) {
            return;
        }
        TextView toChat = createPill(getContext(), "В чат");
        toChat.setOnClickListener(v -> {
            ChatActivityEnterView enterView = chat.getChatActivityEnterView();
            if (enterView != null) {
                enterView.setFieldText(answer);
                finishFragment();
            }
        });
        messagesLayout.addView(toChat, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 0, 0, 8));
        scrollToBottom();
    }

    // ---------- conversation actions ----------

    private void startNewConversation() {
        if (busy) {
            return;
        }
        conversation = OmmaAiHistory.create(defaultTitle(), chatTitle);
        OmmaAiHistory.save(conversation);
        OmmaAiHistory.setActiveId(conversation.id);
        renderConversation();
    }

    private void confirmClear() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Удалить историю?");
        builder.setMessage("Все диалоги ассистента будут удалены безвозвратно.");
        builder.setPositiveButton("Удалить", (dialog, which) -> {
            OmmaAiHistory.deleteAll();
            conversation = OmmaAiHistory.create(defaultTitle(), chatTitle);
            OmmaAiHistory.save(conversation);
            OmmaAiHistory.setActiveId(conversation.id);
            renderConversation();
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    /** Template prompts live in the overflow menu now, so the chat itself stays clean. */
    private void showPromptsMenu() {
        ArrayList<OmmaAiConfig.Template> usable = new ArrayList<>();
        for (OmmaAiConfig.Template template : OmmaAiConfig.templates) {
            if (!TextUtils.isEmpty(template.name) && !TextUtils.isEmpty(template.prompt)) {
                usable.add(template);
            }
        }
        if (usable.isEmpty()) {
            Toast.makeText(getContext(), "В настройках Omma AI нет шаблонов", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] items = new CharSequence[usable.size()];
        for (int i = 0; i < usable.size(); i++) {
            items[i] = usable.get(i).name;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Подсказки");
        builder.setItems(items, (dialog, which) -> {
            OmmaAiConfig.Template template = usable.get(which);
            send(template.name, template.prompt);
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private void openHistory() {        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("История диалогов");
        final ArrayList<OmmaAiHistory.Conversation> all = OmmaAiHistory.loadAll();
        if (all.isEmpty()) {
            builder.setMessage("Пока нет сохранённых диалогов.");
            builder.setNegativeButton("Закрыть", null);
            showDialog(builder.create());
            return;
        }
        CharSequence[] items = new CharSequence[all.size()];
        SimpleDateFormat format = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault());
        for (int i = 0; i < all.size(); i++) {
            OmmaAiHistory.Conversation item = all.get(i);
            items[i] = item.preview() + "\n" + format.format(new Date(item.updatedAt))
                + " · " + item.entries.size() + " сообщ.";
        }
        builder.setItems(items, (dialog, which) -> {
            conversation = all.get(which);
            OmmaAiHistory.setActiveId(conversation.id);
            conversation.updatedAt = System.currentTimeMillis();
            OmmaAiHistory.save(conversation);
            if (actionBar != null) {
                actionBar.setSubtitle(chatTitle != null ? "Контекст: " + chatTitle : "Без контекста чата");
            }
            renderConversation();
        });
        builder.setNegativeButton("Закрыть", null);
        showDialog(builder.create());
    }

    // ---------- sending ----------

    private void sendFromInput() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        input.setText("");
        send(text, text);
    }

    private void send(String shown, String prompt) {
        if (busy) {
            return;
        }
        if (!OmmaAiConfig.isConfigured()) {
            addBubble(shown, true);
            addBubble("Сначала настрой модель: Настройки → Omma AI.", false);
            return;
        }
        conversation.entries.add(new OmmaAiHistory.Entry("user", shown));
        conversation.updatedAt = System.currentTimeMillis();
        if (conversation.entries.size() == 1 && (conversation.title == null || conversation.title.startsWith("Диалог ") || conversation.title.startsWith("Чат: "))) {
            String flat = shown.replace('\n', ' ').trim();
            conversation.title = flat.length() > 42 ? flat.substring(0, 42) + "…" : flat;
        }
        OmmaAiHistory.save(conversation);

        addBubble(shown, true);
        updateEmptyState();

        ArrayList<OmmaAiClient.Message> request = new ArrayList<>();
        if (!TextUtils.isEmpty(OmmaAiConfig.systemPrompt)) {
            request.add(new OmmaAiClient.Message("system", OmmaAiConfig.systemPrompt));
        }
        if (OmmaAiConfig.useContext && chat != null) {
            String context = buildChatContext();
            if (!context.isEmpty()) {
                request.add(new OmmaAiClient.Message("system", "Контекст текущего чата «" + chatTitle + "»:\n" + context));
            }
        }
        // Memory across sessions: the whole stored conversation is replayed to the model.
        for (OmmaAiHistory.Entry entry : conversation.entries) {
            if ("user".equals(entry.role) && entry.text.equals(shown)) {
                continue;
            }
            request.add(new OmmaAiClient.Message(entry.role, entry.text));
        }
        request.add(new OmmaAiClient.Message("user", prompt));

        setBusy(true);
        thinkingView = new OmmaThinkingView(getContext());
        messagesLayout.addView(thinkingView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 0, 0, 6));
        scrollToBottom();

        OmmaAiClient.chat(request, (answer, error) -> {
            setBusy(false);
            if (thinkingView != null) {
                messagesLayout.removeView(thinkingView);
                thinkingView = null;
            }
            if (error != null) {
                addBubble("Ошибка: " + error, false);
                return;
            }
            conversation.entries.add(new OmmaAiHistory.Entry("assistant", answer));
            conversation.updatedAt = System.currentTimeMillis();
            OmmaAiHistory.save(conversation);
            TextView bubble = addBubble(answer, false);
            attachAnswerActions(bubble, answer);
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        if (sendButton != null) {
            sendButton.setAlpha(value ? 0.5f : 1f);
            sendButton.setEnabled(!value);
        }
    }

    // ---------- view helpers ----------

    private TextView createPill(Context context, String text) {
        TextView pill = new TextView(context);
        pill.setText(text);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        pill.setTextColor(ACCENT);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(7), AndroidUtilities.dp(12), AndroidUtilities.dp(7));
        GradientDrawable background = rounded(0x1A2563EB, 16);
        background.setStroke(AndroidUtilities.dp(1), 0x552563EB);
        pill.setBackground(background);
        return pill;
    }

    private static GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(AndroidUtilities.dp(radiusDp));
        return drawable;
    }

    private static String resolveChatTitle(ChatActivity chat) {
        TLRPC.User user = chat.getCurrentUser();
        if (user != null) {
            return UserObject.getUserName(user);
        }
        TLRPC.Chat tlChat = chat.getCurrentChat();
        return tlChat != null ? tlChat.title : "чат";
    }

    /** Last messages in chronological order. ChatActivity keeps the newest message at index 0. */
    private String buildChatContext() {
        List<MessageObject> source = chat.messages;
        if (source == null || source.isEmpty()) {
            return "";
        }
        MessagesController controller = MessagesController.getInstance(chat.getCurrentAccount());
        ArrayList<String> lines = new ArrayList<>();
        for (int i = 0; i < source.size() && lines.size() < CONTEXT_MESSAGES; i++) {
            MessageObject message = source.get(i);
            if (message == null || message.isDateObject || message.type == MessageObject.TYPE_DATE || TextUtils.isEmpty(message.messageText)) {
                continue;
            }
            lines.add(0, senderName(controller, message) + ": " + message.messageText);
        }
        return TextUtils.join("\n", lines);
    }

    private String senderName(MessagesController controller, MessageObject message) {
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
            TLRPC.Chat sender = controller.getChat(-senderId);
            if (sender != null) {
                return sender.title;
            }
        }
        return chatTitle != null ? chatTitle : "Собеседник";
    }
}
