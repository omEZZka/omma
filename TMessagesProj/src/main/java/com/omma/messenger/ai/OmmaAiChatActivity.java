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
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
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
    private static final int MENU_REASONING = 5;
    private static final int MENU_STYLE = 6;
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
    private ActionBarMenuSubItem reasoningItem;

    public OmmaAiChatActivity() {
    }

    public OmmaAiChatActivity(ChatActivity chat) {
        this.chat = chat;
    }

    @Override
    public boolean onFragmentCreate() {
        // Deliberately minimal: the parent ChatActivity may still be building its state here, and
        // touching chat.messages at this point can throw. Everything that needs chat data runs in
        // createView(), and every read below is null-guarded.
        return super.onFragmentCreate();
    }

    private void ensureConversation() {
        if (chat == null) {
            BaseFragment last = org.telegram.ui.LaunchActivity.getSafeLastFragment();
            if (last instanceof ChatActivity) {
                chat = (ChatActivity) last;
            }
        }
        if (chat != null && chatTitle == null) {
            try {
                chatTitle = resolveChatTitle(chat);
            } catch (Throwable ignore) {
                chatTitle = null;
            }
        }
        if (conversation != null) {
            return;
        }
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
        try {
            ensureConversation();
            return buildUi(context);
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e(t);
            return buildErrorView(context, t);
        }
    }

    private View buildErrorView(Context context, Throwable t) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24));

        TextView title = new TextView(context);
        title.setText("Не удалось открыть ассистента");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView detail = new TextView(context);
        String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        detail.setText(message);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        detail.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, AndroidUtilities.dp(8), 0, 0);
        detail.setTextIsSelectable(true);
        root.addView(detail);

        if (actionBar != null) {
            actionBar.setTitle("Omma AI");
        }
        return root;
    }

    private View buildUi(Context context) {
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
                } else if (id == MENU_REASONING) {
                    OmmaAiConfig.showReasoning = !OmmaAiConfig.showReasoning;
                    OmmaAiConfig.save();
                    if (reasoningItem != null) {
                        reasoningItem.setChecked(OmmaAiConfig.showReasoning);
                        reasoningItem.setText(OmmaAiConfig.showReasoning ? "Скрыть рассуждения" : "Показывать рассуждения");
                    }
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem overflow = menu.addItem(0, R.drawable.ic_ab_other);
        if (overflow != null) {
            reasoningItem = overflow.addSubItem(MENU_REASONING, R.drawable.msg_search, "Показывать рассуждения", true);
            reasoningItem.setChecked(OmmaAiConfig.showReasoning);
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
        if (OmmaAiEngine.get().isRunning(conversation.id)) {
            setBusy(true);
            showThinking();
        }
        OmmaAiEngine.get().setListener(this::onEngineAnswer);
        fragmentView = root;
        return root;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        OmmaAiEngine.get().setListener(null);
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
        sendButton.setOnClickListener(v -> showSendMenu());
        row.addView(sendButton, LayoutHelper.createLinear(44, 44, 0f, 8, 0, 0, 0));
        return row;
    }

    /** Long-press the send button for the style dropdown; single tap sends as-is. */
    private void showSendMenu() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            AndroidUtilities.shakeView(sendButton);
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Отправить в стиле");
        CharSequence[] items = new CharSequence[OmmaAiConfig.STYLES.length + 1];
        items[0] = "Как написано";
        for (int i = 0; i < OmmaAiConfig.STYLES.length; i++) {
            items[i + 1] = OmmaAiConfig.STYLES[i].title;
        }
        builder.setItems(items, (dialog, which) -> {
            String textToSend = text;
            String displayed = text;
            if (which > 0) {
                OmmaAiConfig.Style style = OmmaAiConfig.STYLES[which - 1];
                textToSend = "[Стиль: " + style.title + "]\nПерепиши следующее сообщение в этом стиле и верни только результат:\n" + text;
                displayed = text;
            }
            input.setText("");
            if (which > 0) {
                // Style rewrite runs as a normal prompt; the answer lands in the conversation.
                send(displayed, textToSend);
            } else {
                send(displayed, displayed);
            }
        });
        if (notAttached()) {
            return;
        }
        showDialog(builder.create());
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
            if (user) {
                addBubble(entry.text, true);
            } else {
                String[] parts = splitReasoning(entry.text);
                if (!parts[0].isEmpty() && OmmaAiConfig.showReasoning) {
                    addReasoningBlock(parts[0]);
                }
                if (!parts[1].isEmpty()) {
                    TextView bubble = addBubble(parts[1], false);
                    attachAnswerActions(bubble, entry.text);
                } else if (parts[0].isEmpty()) {
                    addBubble(entry.text, false);
                }
            }
        }
        updateEmptyState();
        scrollToBottom();
    }

    /** Splits "…<think>reasoning</think>answer" or "Рассуждения:\n…\n\nОтвет: …". */
    private static String[] splitReasoning(String text) {
        String reasoning = "";
        String answer = text == null ? "" : text;
        int start = answer.indexOf("<think>");
        if (start >= 0) {
            int end = answer.indexOf("</think>");
            if (end > start) {
                reasoning = answer.substring(start + 7, end).trim();
                answer = (answer.substring(0, start) + answer.substring(end + 8)).trim();
            }
        } else if (answer.startsWith("Рассуждения:")) {
            int end = answer.indexOf("Ответ:");
            if (end > 0) {
                reasoning = answer.substring("Рассуждения:".length(), end).trim();
                answer = answer.substring(end + "Ответ:".length()).trim();
            }
        }
        return new String[]{reasoning, answer};
    }

    private static String stripReasoning(String text) {
        return splitReasoning(text)[1];
    }

    /** Collapsible gray block with the model's chain of thought. */
    private void addReasoningBlock(String reasoning) {
        if (getContext() == null || messagesLayout == null) {
            return;
        }
        LinearLayout block = new LinearLayout(getContext());
        block.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = rounded(Theme.getColor(Theme.key_windowBackgroundGray), 12);
        background.setStroke(AndroidUtilities.dp(1), 0x33888888);
        block.setBackground(background);
        block.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8));

        TextView header = new TextView(getContext());
        header.setText("🤔 Рассуждения (нажми, чтобы раскрыть)");
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        block.addView(header);

        TextView body = new TextView(getContext());
        body.setText(reasoning);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        body.setVisibility(View.GONE);
        body.setTextIsSelectable(true);
        block.addView(body);

        header.setOnClickListener(v -> body.setVisibility(body.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));

        messagesLayout.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 0, 48, 6));
    }

    private void updateEmptyState() {
        if (emptyState != null) {
            emptyState.setVisibility(conversation.entries.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private TextView addBubble(String text, boolean user) {
        if (getContext() == null || messagesLayout == null) {
            return null;
        }
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
        if (bubble == null) {
            return;
        }
        bubble.setOnLongClickListener(v -> {
            showAnswerMenu(answer);
            return true;
        });
        if (chat == null || messagesLayout == null) {
            return;
        }
        TextView toChat = createPill(getContext(), "В чат");
        toChat.setOnClickListener(v -> showAnswerMenu(answer));
        messagesLayout.addView(toChat, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 0, 0, 8));
        scrollToBottom();
    }

    /** Copy, insert into the chat field or send the answer from the user's account (confirm first). */
    private void showAnswerMenu(String answer) {
        if (notAttached()) {
            return;
        }
        String visibleText = stripReasoning(answer);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Действия с ответом");
        ArrayList<String> options = new ArrayList<>();
        options.add("Скопировать");
        options.add("Вставить в поле чата");
        options.add("Отправить от моего имени…");
        CharSequence[] items = options.toArray(new CharSequence[0]);
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                AndroidUtilities.addToClipboard(visibleText);
                Toast.makeText(getContext(), "Скопировано", Toast.LENGTH_SHORT).show();
            } else if (which == 1) {
                insertIntoChat(visibleText);
            } else {
                confirmSendFromUser(visibleText);
            }
        });
        showDialog(builder.create());
    }

    private void insertIntoChat(String text) {
        try {
            ChatActivityEnterView enterView = chat.getChatActivityEnterView();
            if (enterView != null) {
                enterView.setFieldText(text);
                finishFragment();
                return;
            }
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e(t);
        }
        Toast.makeText(getContext(), "Чат уже закрыт", Toast.LENGTH_SHORT).show();
    }

    /** Double confirmation: the model never posts anywhere by itself. */
    private void confirmSendFromUser(String text) {
        if (notAttached()) {
            return;
        }
        if (chat == null) {
            Toast.makeText(getContext(), "Открой сначала обычный чат", Toast.LENGTH_SHORT).show();
            return;
        }
        long dialogId = chat.getDialogId();
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Отправить от твоего имени?");
        builder.setMessage("Сообщение будет отправлено в чат «" + (chatTitle != null ? chatTitle : "чат") + "» от твоего аккаунта:\n\n" + text);
        builder.setPositiveButton("Отправить", (dialog, which) -> {
            org.telegram.messenger.SendMessagesHelper.getInstance(chat.getCurrentAccount())
                .sendMessage(org.telegram.messenger.SendMessagesHelper.SendMessageParams.of(text, dialogId));
            Toast.makeText(getContext(), "Отправлено", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
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

    private boolean notAttached() {
        return getParentActivity() == null;
    }

    private void confirmClear() {
        if (notAttached() || busy) {
            return;
        }
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
        if (getParentActivity() != null) {
            showDialog(builder.create());
        }
    }

    /** Template prompts live in the overflow menu now, so the chat itself stays clean. */
    private void showPromptsMenu() {
        if (notAttached()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Действия");
        ArrayList<String> actions = new ArrayList<>();
        actions.add("Предложить ответ…");
        for (OmmaAiConfig.Template template : OmmaAiConfig.templates) {
            if (!TextUtils.isEmpty(template.name) && !TextUtils.isEmpty(template.prompt)) {
                actions.add(template.name);
            }
        }
        CharSequence[] items = actions.toArray(new CharSequence[0]);
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                suggestReplyWithStyle();
            } else {
                OmmaAiConfig.Template template = OmmaAiConfig.templates.get(which - 1);
                send(template.name, template.prompt);
            }
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    /** "Предложить ответ": first pick a style, then ask the model for one reply in that style. */
    private void suggestReplyWithStyle() {
        if (notAttached()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Стиль ответа");
        CharSequence[] items = new CharSequence[OmmaAiConfig.STYLES.length];
        for (int i = 0; i < OmmaAiConfig.STYLES.length; i++) {
            items[i] = OmmaAiConfig.STYLES[i].title;
        }
        builder.setItems(items, (dialog, which) -> {
            OmmaAiConfig.Style style = OmmaAiConfig.STYLES[which];
            String prompt = "Предложи ОДИН вариант ответа на последнее сообщение собеседника в стиле «" + style.title + "». "
                + (style == null ? "" : style.instruction + " ")
                + "Верни только текст ответа, без пояснений.";
            send("Ответ в стиле: " + style.title, prompt);
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private void openHistory() {
        if (notAttached()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
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
        if (busy || conversation == null) {
            return;
        }
        if (getContext() == null || messagesLayout == null) {
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

        // The engine owns the request, so the answer keeps generating after the screen closes.
        setBusy(true);
        showThinking();
        OmmaAiEngine.get().request(conversation, prompt, chatTitle, buildChatContext());
    }

    private void showThinking() {
        if (messagesLayout == null || getContext() == null) {
            return;
        }
        if (thinkingView != null) {
            messagesLayout.removeView(thinkingView);
        }
        thinkingView = new OmmaThinkingView(getContext());
        messagesLayout.addView(thinkingView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 0, 0, 6));
        scrollToBottom();
    }

    /** The engine finished (answer or error): refresh from the persistent history. */
    private void onEngineAnswer() {
        setBusy(false);
        if (thinkingView != null) {
            messagesLayout.removeView(thinkingView);
            thinkingView = null;
        }
        if (getContext() == null || messagesLayout == null || conversation == null) {
            return;
        }
        OmmaAiHistory.Conversation fresh = OmmaAiHistory.load(conversation.id);
        if (fresh != null) {
            conversation = fresh;
        }
        renderConversation();
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
        List<MessageObject> source;
        MessagesController controller;
        try {
            if (chat == null) {
                return "";
            }
            source = chat.messages;
            controller = MessagesController.getInstance(chat.getCurrentAccount());
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e(t);
            return "";
        }
        if (source == null || source.isEmpty()) {
            return "";
        }
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
