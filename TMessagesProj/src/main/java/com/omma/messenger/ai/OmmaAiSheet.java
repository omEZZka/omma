package com.omma.messenger.ai;

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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.List;

/** Bottom sheet with the AI assistant conversation. */
public final class OmmaAiSheet extends BottomSheet {

    private static final int CONTEXT_MESSAGES = 20;
    private static final int ACCENT = 0xFF2563EB;

    private static OmmaAiSheet current;

    private final ChatActivity chat;
    private final String chatTitle;
    private final ArrayList<OmmaAiClient.Message> history = new ArrayList<>();

    private LinearLayout messagesLayout;
    private ScrollView scrollView;
    private EditText input;
    private TextView sendButton;
    private TextView pendingView;
    private boolean busy;

    public static void open(Context context) {
        if (current != null && current.isShowing()) {
            return;
        }
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        ChatActivity chat = fragment instanceof ChatActivity ? (ChatActivity) fragment : null;
        current = new OmmaAiSheet(context, chat);
        current.show();
    }

    private OmmaAiSheet(Context context, ChatActivity chat) {
        super(context, true);
        this.chat = chat;
        this.chatTitle = chat == null ? null : resolveChatTitle(chat);
        setApplyBottomPadding(false);
        setCustomView(buildContent(context));
        addBubble(chatTitle != null
            ? "Я вижу чат «" + chatTitle + "». Могу пересказать, перевести или предложить ответ."
            : "Я ассистент Omma. Открой чат, чтобы я видел его контекст, или спроси что угодно.", false);
        if (!OmmaAiConfig.isConfigured()) {
            addBubble("Модель не настроена. Открой Настройки → Omma AI и укажи провайдера, ключ и модель.", false);
        }
    }

    @Override
    public void dismiss() {
        AndroidUtilities.hideKeyboard(input);
        super.dismiss();
        if (current == this) {
            current = null;
        }
    }

    private View buildContent(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText("ИИ-ассистент");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titles.addView(title);
        TextView subtitle = new TextView(context);
        subtitle.setText(chatTitle != null ? "Контекст: " + chatTitle : "Режим: без чата");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        titles.addView(subtitle);
        header.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView settings = createPill(context, "Настройки");
        settings.setOnClickListener(v -> {
            dismiss();
            BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            if (fragment != null) {
                fragment.presentFragment(new OmmaAiSettingsActivity());
            }
        });
        header.addView(settings, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 8));

        HorizontalScrollView chipsScroll = new HorizontalScrollView(context);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        for (OmmaAiConfig.Template template : OmmaAiConfig.templates) {
            if (TextUtils.isEmpty(template.name)) {
                continue;
            }
            TextView chip = createPill(context, template.name);
            chip.setOnClickListener(v -> send(template.name, template.prompt));
            chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 0));
        }
        chipsScroll.addView(chips);
        root.addView(chipsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        scrollView = new ScrollView(context);
        messagesLayout = new LinearLayout(context);
        messagesLayout.setOrientation(LinearLayout.VERTICAL);
        messagesLayout.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        scrollView.addView(messagesLayout);
        int height = (int) (AndroidUtilities.displaySize.y * 0.45f);
        root.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));

        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        input = new EditText(context);
        input.setHint(chatTitle != null ? "Спроси про этот чат…" : "Спроси что-нибудь…");
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(4);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10));
        input.setBackground(rounded(Theme.getColor(Theme.key_dialogBackgroundGray), 20));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendFromInput();
                return true;
            }
            return false;
        });
        inputRow.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        sendButton = new TextView(context);
        sendButton.setText("↑");
        sendButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        sendButton.setTypeface(AndroidUtilities.bold());
        sendButton.setTextColor(0xFFFFFFFF);
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setBackground(rounded(ACCENT, 22));
        sendButton.setOnClickListener(v -> sendFromInput());
        inputRow.addView(sendButton, LayoutHelper.createLinear(44, 44, 8, 0, 0, 0));

        root.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 4, 12, 4));
        return root;
    }

    private void sendFromInput() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        input.setText("");
        send(text, text);
    }

    /** @param shown text shown in the user bubble, @param prompt text sent to the model. */
    private void send(String shown, String prompt) {
        if (busy) {
            return;
        }
        if (!OmmaAiConfig.isConfigured()) {
            addBubble(shown, true);
            addBubble("Сначала настрой модель: Настройки → Omma AI.", false);
            return;
        }
        addBubble(shown, true);

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
        request.addAll(history);
        OmmaAiClient.Message userMessage = new OmmaAiClient.Message("user", prompt);
        request.add(userMessage);

        setBusy(true);
        pendingView = addBubble("…", false);
        OmmaAiClient.chat(request, (answer, error) -> {
            setBusy(false);
            if (pendingView != null) {
                messagesLayout.removeView(pendingView);
                pendingView = null;
            }
            if (!isShowing()) {
                return;
            }
            if (error != null) {
                addBubble("Ошибка: " + error, false);
                return;
            }
            history.add(userMessage);
            history.add(new OmmaAiClient.Message("assistant", answer));
            TextView bubble = addBubble(answer, false);
            attachAnswerActions(bubble, answer);
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        sendButton.setAlpha(value ? 0.5f : 1f);
        sendButton.setEnabled(!value);
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
                dismiss();
            }
        });
        messagesLayout.addView(toChat, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 0, 0, 8));
        scrollToBottom();
    }

    private TextView addBubble(String text, boolean user) {
        TextView bubble = new TextView(getContext());
        bubble.setText(text);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        bubble.setTextIsSelectable(false);
        bubble.setLineSpacing(0, 1.15f);
        bubble.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(9), AndroidUtilities.dp(12), AndroidUtilities.dp(9));
        if (user) {
            bubble.setTextColor(0xFFFFFFFF);
            bubble.setBackground(rounded(ACCENT, 16));
        } else {
            bubble.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            bubble.setBackground(rounded(Theme.getColor(Theme.key_dialogBackgroundGray), 16));
        }
        LinearLayout.LayoutParams params = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, user ? Gravity.RIGHT : Gravity.LEFT, user ? 48 : 0, 0, user ? 0 : 48, 6);
        messagesLayout.addView(bubble, params);
        scrollToBottom();
        return bubble;
    }

    private void scrollToBottom() {
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

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
