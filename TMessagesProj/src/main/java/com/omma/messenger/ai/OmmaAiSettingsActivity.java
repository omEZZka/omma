package com.omma.messenger.ai;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

/** Settings screen of the Omma AI assistant. Changes are saved when the screen is closed. */
public class OmmaAiSettingsActivity extends BaseFragment {

    private static final int ACCENT = 0xFF2563EB;

    private TextView providerValue;
    private TextView modelValue;
    private TextView keyLink;
    private EditText baseUrlField;
    private EditText apiKeyField;
    private EditText systemPromptField;
    private TextView temperatureLabel;
    private SeekBar temperatureBar;
    private Switch contextSwitch;
    private Switch fabSwitch;
    private Switch profanitySwitch;
    private Switch reasoningSwitch;
    private LinearLayout templatesLayout;
    private LinearLayout stylesLayout;
    private String selectedProvider;
    private TextView modelsButton;
    private boolean modelsLoading;
    private SeekBar opacityBar;
    private TextView opacityLabel;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Omma AI");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        selectedProvider = OmmaAiConfig.provider;

        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(24));
        scrollView.addView(root);

        root.addView(buildProviderCard(context), cardParams());
        root.addView(buildAccessCard(context), cardParams());
        root.addView(buildConversationsCard(context), cardParams());
        root.addView(buildAssistantCard(context), cardParams());
        root.addView(buildTemplatesCard(context), cardParams());
        root.addView(buildStylesCard(context), cardParams());

        fragmentView = scrollView;
        return fragmentView;
    }

    // ---------- provider ----------

    private View buildProviderCard(Context context) {
        LinearLayout card = card(context, "Провайдер");

        providerValue = valueRow(context, card, "Сервис");
        providerValue.setOnClickListener(v -> pickProvider());

        baseUrlField = field(context, card, "Ссылка провайдера (base URL)", "https://api.example.com/v1", false);
        baseUrlField.setText(OmmaAiConfig.baseUrl);

        keyLink = new TextView(context);
        keyLink.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        keyLink.setTextColor(ACCENT);
        keyLink.setText("Получить API-ключ →");
        keyLink.setPadding(0, AndroidUtilities.dp(2), 0, AndroidUtilities.dp(8));
        keyLink.setOnClickListener(v -> {
            OmmaAiConfig.Provider provider = OmmaAiConfig.findProvider(selectedProvider);
            if (!TextUtils.isEmpty(provider.keysUrl)) {
                Browser.openUrl(getParentActivity(), provider.keysUrl);
            }
        });
        card.addView(keyLink);

        apiKeyField = field(context, card, "API-ключ", "sk-...", true);
        apiKeyField.setText(OmmaAiConfig.apiKey);

        modelValue = valueRow(context, card, "Модель (список с сервера)");
        modelValue.setOnClickListener(v -> showModelPicker());
        updateModelValue();

        TextView loadModels = iconButton(context, "Обновить список моделей", R.drawable.msg_download);
        loadModels.setOnClickListener(v -> {
            if (modelsLoading) {
                return;
            }
            modelsLoading = true;
            setButtonLoading(loadModels, true);
            loadModels();
        });
        modelsButton = loadModels;
        card.addView(loadModels, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 8));

        hint(context, card, "Список моделей подтягивается с сервера провайдера и кешируется. Ключ хранится только на устройстве, зашифрованный Android Keystore.");

        updateProviderViews();
        return card;
    }

    private void pickProvider() {
        CharSequence[] titles = new CharSequence[OmmaAiConfig.PROVIDERS.length];
        for (int i = 0; i < titles.length; i++) {
            titles[i] = OmmaAiConfig.PROVIDERS[i].title;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Сервис");
        builder.setItems(titles, (dialog, which) -> {
            OmmaAiConfig.Provider provider = OmmaAiConfig.PROVIDERS[which];
            selectedProvider = provider.id;
            if (!TextUtils.isEmpty(provider.baseUrl)) {
                baseUrlField.setText(provider.baseUrl);
            }
            OmmaAiConfig.model = "";
            updateProviderViews();
            updateModelValue();
        });
        showDialog(builder.create());
    }

    private void updateProviderViews() {
        OmmaAiConfig.Provider provider = OmmaAiConfig.findProvider(selectedProvider);
        providerValue.setText(provider.title);
        keyLink.setVisibility(TextUtils.isEmpty(provider.keysUrl) ? View.GONE : View.VISIBLE);
    }

    private void loadModels() {
        String baseUrl = baseUrlField.getText().toString().trim();
        String apiKey = apiKeyField.getText().toString().trim();
        if (baseUrl.isEmpty() || (OmmaAiConfig.requiresApiKey(selectedProvider) && apiKey.isEmpty())) {
            setButtonLoading(modelsButton, false);
            modelsLoading = false;
            toast(OmmaAiConfig.requiresApiKey(selectedProvider) ? "Укажи base URL и API-ключ" : "Укажи base URL");
            return;
        }
        OmmaAiClient.fetchModels(baseUrl, apiKey, (models, error) -> {
            modelsLoading = false;
            setButtonLoading(modelsButton, false);
            if (getParentActivity() == null) {
                return;
            }
            if (error != null) {
                toast("Не вышло: " + error);
                return;
            }
            if (models == null || models.isEmpty()) {
                toast("Провайдер вернул пустой список");
                return;
            }
            OmmaAiConfig.saveDiscoveredModels(selectedProvider, models);
            showModelPicker();
        });
    }

    private void setButtonLoading(TextView buttonView, boolean loading) {
        if (buttonView == null) {
            return;
        }
        buttonView.setCompoundDrawablesWithIntrinsicBounds(
            loading ? 0 : R.drawable.msg_download, 0, 0, 0);
        buttonView.setCompoundDrawablePadding(AndroidUtilities.dp(6));
        buttonView.setText(loading ? "Загружаю…" : "Обновить список моделей");
        buttonView.setAlpha(loading ? 0.65f : 1f);
    }

    private TextView iconButton(Context context, String text, int icon) {
        TextView view = button(context, "  " + text);
        view.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        view.setCompoundDrawablePadding(AndroidUtilities.dp(6));
        return view;
    }

    /** Only live models: cached list from /models of the connected provider. */
    private void showModelPicker() {
        List<String> models = OmmaAiConfig.getDiscoveredModels(selectedProvider);
        if (models.isEmpty()) {
            toast("Сначала обнови список моделей по своему ключу");
            return;
        }
        CharSequence[] items = models.toArray(new CharSequence[0]);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Моделей: " + models.size());
        builder.setItems(items, (dialog, which) -> {
            OmmaAiConfig.model = models.get(which);
            updateModelValue();
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private void updateModelValue() {
        if (modelValue == null) {
            return;
        }
        List<String> models = OmmaAiConfig.getDiscoveredModels(selectedProvider);
        if (TextUtils.isEmpty(OmmaAiConfig.model)) {
            modelValue.setText(models.isEmpty() ? "Нет списка — обнови по ключу" : "Выбрать модель (" + models.size() + ")");
        } else {
            modelValue.setText(OmmaAiConfig.model);
        }
    }

    // ---------- chat access ----------

    private View buildAccessCard(Context context) {
        LinearLayout card = card(context, "Доступ к чатам");

        TextView modeValue = valueRow(context, card, "Какие чаты видит ассистент");
        int currentIndex = accessIndex(OmmaAiConfig.chatAccessMode);
        modeValue.setText(OmmaAiConfig.ACCESS_TITLES[Math.max(0, currentIndex)]);
        modeValue.setOnClickListener(v -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle("Какие чаты видит ассистент");
            builder.setItems(OmmaAiConfig.ACCESS_TITLES, (dialog, which) -> {
                OmmaAiConfig.chatAccessMode = which == 0 ? OmmaAiConfig.ACCESS_ALL
                    : which == 1 ? OmmaAiConfig.ACCESS_ALLOW : OmmaAiConfig.ACCESS_DENY;
                modeValue.setText(OmmaAiConfig.ACCESS_TITLES[which]);
                OmmaAiConfig.save();
            });
            showDialog(builder.create());
        });

        TextView allowButton = button(context, "Выбранные чаты (" + OmmaAiConfig.chatAllowList.size() + ")");
        allowButton.setOnClickListener(v -> pickDialogs("Разрешить чаты", OmmaAiConfig.chatAllowList, allowButton));
        card.addView(allowButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 6));

        TextView denyButton = button(context, "Исключённые чаты (" + OmmaAiConfig.chatDenyList.size() + ")");
        denyButton.setOnClickListener(v -> pickDialogs("Исключить чаты", OmmaAiConfig.chatDenyList, denyButton));
        card.addView(denyButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        hint(context, card, "Ассистент может читать и искать по чатам только в рамках этого режима. По умолчанию — все чаты аккаунта.");
        return card;
    }

    private int accessIndex(String mode) {
        if (OmmaAiConfig.ACCESS_ALLOW.equals(mode)) {
            return 1;
        }
        return OmmaAiConfig.ACCESS_DENY.equals(mode) ? 2 : 0;
    }

    private void pickDialogs(String title, ArrayList<String> target, TextView buttonView) {
        AccountInstance account = AccountInstance.getInstance(UserConfig.selectedAccount);
        ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>(account.getMessagesController().getDialogs(0));
        if (dialogs.isEmpty()) {
            toast("Список чатов ещё не загружен");
            return;
        }
        ArrayList<Integer> selected = new ArrayList<>();
        for (int i = 0; i < dialogs.size(); i++) {
            if (target.contains(String.valueOf(dialogs.get(i).id))) {
                selected.add(i);
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        CharSequence[] labels = new CharSequence[dialogs.size()];
        for (int i = 0; i < dialogs.size(); i++) {
            labels[i] = (selected.contains(i) ? "☑ " : "☐ ") + dialogTitle(account, dialogs.get(i).id);
        }
        builder.setItems(labels, (dialog, which) -> {
            String id = String.valueOf(dialogs.get(which).id);
            if (selected.contains(which)) {
                selected.remove((Integer) which);
                target.remove(id);
            } else {
                selected.add(which);
                if (!target.contains(id)) {
                    target.add(id);
                }
            }
            OmmaAiConfig.save();
            ((android.app.Dialog) dialog).dismiss();
            pickDialogs(title, target, buttonView);
        });
        builder.setNegativeButton("Готово", (dialog, which) -> {
            if (buttonView != null) {
                buttonView.setText(buttonView.getText().toString().replaceAll("\\(\\d+\\)", "(" + target.size() + ")"));
            }
        });
        showDialog(builder.create());
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

    // ---------- assistant ----------

    private View buildConversationsCard(Context context) {
        LinearLayout card = card(context, "Диалоги");

        TextView history = button(context, "История диалогов");
        history.setOnClickListener(v -> presentFragment(new OmmaAiChatActivity()));
        card.addView(history, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 8));

        TextView forget = new TextView(context);
        forget.setText("Забыть все диалоги");
        forget.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        forget.setTextColor(0xFFE53935);
        forget.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
        forget.setOnClickListener(v -> {
            org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
            builder.setTitle("Забыть все диалоги?");
            builder.setMessage("Вся история переписки с ассистентом будет удалена.");
            builder.setPositiveButton("Удалить", (dialog, which) -> {
                OmmaAiHistory.deleteAll();
                toast("История очищена");
            });
            builder.setNegativeButton("Отмена", null);
            showDialog(builder.create());
        });
        card.addView(forget);

        hint(context, card, "Диалоги сохраняются на устройстве и продолжаются между сессиями.");
        return card;
    }

    private View buildAssistantCard(Context context) {
        LinearLayout card = card(context, "Ассистент");

        systemPromptField = field(context, card, "Системный промт", "Ты — ...", false);
        systemPromptField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        systemPromptField.setMinLines(3);
        systemPromptField.setGravity(Gravity.TOP | Gravity.LEFT);
        systemPromptField.setText(OmmaAiConfig.systemPrompt);

        temperatureLabel = label(context, card, "");
        temperatureBar = new SeekBar(context);
        temperatureBar.setMax(15);
        temperatureBar.setProgress(Math.round(OmmaAiConfig.temperature * 10));
        temperatureBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateTemperatureLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        card.addView(temperatureBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        updateTemperatureLabel();

        contextSwitch = toggle(context, card, "Учитывать контекст чата", OmmaAiConfig.useContext);
        fabSwitch = toggle(context, card, "Плавающая кнопка ассистента", OmmaAiConfig.fabEnabled);
        profanitySwitch = toggle(context, card, "Разрешить ассистенту материться", OmmaAiConfig.allowProfanity);
        reasoningSwitch = toggle(context, card, "Показывать рассуждения модели", OmmaAiConfig.showReasoning);

        opacityLabel = label(context, card, "");
        opacityBar = new SeekBar(context);
        opacityBar.setMax(100);
        opacityBar.setProgress(OmmaAiConfig.chatOpacityPercent);
        opacityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateOpacityLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        card.addView(opacityBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        updateOpacityLabel();
        hint(context, card, "Прозрачность чата ассистента: меньше — стекляннее, видно какой чат открыт.");

        TextView learned = new TextView(context);
        learned.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        learned.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        learned.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(4));
        learned.setText("Что ассистент запомнил:\n" + (OmmaAiConfig.learnedPreferences == null || OmmaAiConfig.learnedPreferences.isEmpty()
            ? "пока ничего" : OmmaAiConfig.learnedPreferences));
        card.addView(learned);
        return card;
    }

    private void updateTemperatureLabel() {
        temperatureLabel.setText(String.format(java.util.Locale.US, "Температура: %.1f", temperatureBar.getProgress() / 10f));
    }

    private void updateOpacityLabel() {
        opacityLabel.setText("Прозрачность чата: " + opacityBar.getProgress() + "%");
    }

    // ---------- custom styles ----------

    private View buildStylesCard(Context context) {
        LinearLayout card = card(context, "Стили сообщений");
        hint(context, card, "Встроенные: Деловой, Коротко, Церковный, Викингский. Ниже — свои стили, они появляются в меню отправки и «Предложить ответ».");

        stylesLayout = new LinearLayout(context);
        stylesLayout.setOrientation(LinearLayout.VERTICAL);
        card.addView(stylesLayout);
        renderCustomStyles();

        TextView add = button(context, "+ Новый стиль");
        add.setOnClickListener(v -> showAddStyleDialog());
        card.addView(add, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        return card;
    }

    private void showAddStyleDialog() {
        if (getParentActivity() == null) {
            return;
        }
        if (OmmaAiConfig.customStyles.size() >= 10) {
            toast("Максимум 10 своих стилей");
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Новый стиль");
        LinearLayout container = new LinearLayout(getParentActivity());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(10), AndroidUtilities.dp(20), 0);

        final EditText titleInput = new EditText(getParentActivity());
        titleInput.setHint("Название (например: Пиратский)");
        titleInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        container.addView(titleInput);

        final EditText instructionInput = new EditText(getParentActivity());
        instructionInput.setHint("Инструкция для модели (как писать)");
        instructionInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        instructionInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        instructionInput.setMinLines(3);
        instructionInput.setGravity(Gravity.TOP | Gravity.LEFT);
        container.addView(instructionInput);

        builder.setView(container);
        builder.setPositiveButton("Создать", (dialog, which) -> {
            if (!OmmaAiConfig.addCustomStyle(
                titleInput.getText().toString().trim(),
                instructionInput.getText().toString().trim())) {
                toast("Заполни название и инструкцию");
            }
            renderCustomStyles();
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private void renderCustomStyles() {
        if (stylesLayout == null) {
            return;
        }
        Context context = stylesLayout.getContext();
        stylesLayout.removeAllViews();
        for (OmmaAiConfig.Style style : new ArrayList<>(OmmaAiConfig.customStyles)) {
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
            item.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundGray), 12));

            TextView name = new TextView(context);
            name.setText(style.title + "\n" + style.instruction);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            item.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            TextView delete = new TextView(context);
            delete.setText("Удалить");
            delete.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            delete.setTextColor(0xFFE53935);
            delete.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(4), AndroidUtilities.dp(6));
            delete.setOnClickListener(v -> {
                OmmaAiConfig.removeCustomStyle(style);
                renderCustomStyles();
            });
            item.addView(delete);

            stylesLayout.addView(item, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));
        }
    }

    // ---------- templates ----------

    private View buildTemplatesCard(Context context) {
        LinearLayout card = card(context, "Шаблоны работ");
        hint(context, card, "Появляются как быстрые кнопки в панели ассистента.");

        templatesLayout = new LinearLayout(context);
        templatesLayout.setOrientation(LinearLayout.VERTICAL);
        card.addView(templatesLayout);
        renderTemplates();

        TextView add = button(context, "+ Добавить шаблон");
        add.setOnClickListener(v -> {
            OmmaAiConfig.templates.add(new OmmaAiConfig.Template("Новый шаблон", ""));
            renderTemplates();
        });
        card.addView(add, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        return card;
    }

    private void renderTemplates() {
        Context context = templatesLayout.getContext();
        templatesLayout.removeAllViews();
        for (OmmaAiConfig.Template template : OmmaAiConfig.templates) {
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
            item.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundGray), 12));

            LinearLayout top = new LinearLayout(context);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            EditText name = plainInput(context, "Название");
            name.setText(template.name);
            name.addTextChangedListener(watcher(text -> template.name = text));
            top.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            TextView delete = new TextView(context);
            delete.setText("Удалить");
            delete.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            delete.setTextColor(0xFFE53935);
            delete.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(4), AndroidUtilities.dp(6));
            delete.setOnClickListener(v -> {
                OmmaAiConfig.templates.remove(template);
                renderTemplates();
            });
            top.addView(delete);
            item.addView(top);

            EditText prompt = plainInput(context, "Промт шаблона");
            prompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            prompt.setMinLines(2);
            prompt.setText(template.prompt);
            prompt.addTextChangedListener(watcher(text -> template.prompt = text));
            item.addView(prompt);

            templatesLayout.addView(item, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
    }

    // ---------- saving ----------

    @Override
    public void onPause() {
        super.onPause();
        saveForm();
    }

    @Override
    public void onFragmentDestroy() {
        saveForm();
        super.onFragmentDestroy();
    }

    private void saveForm() {
        if (baseUrlField == null) {
            return;
        }
        OmmaAiConfig.provider = selectedProvider;
        OmmaAiConfig.baseUrl = baseUrlField.getText().toString().trim();
        OmmaAiConfig.apiKey = apiKeyField.getText().toString().trim();
        OmmaAiConfig.systemPrompt = systemPromptField.getText().toString();
        OmmaAiConfig.temperature = temperatureBar.getProgress() / 10f;
        if (opacityBar != null) {
            OmmaAiConfig.chatOpacityPercent = opacityBar.getProgress();
        }
        OmmaAiConfig.useContext = contextSwitch.isChecked();
        OmmaAiConfig.fabEnabled = fabSwitch.isChecked();
        OmmaAiConfig.allowProfanity = profanitySwitch.isChecked();
        OmmaAiConfig.showReasoning = reasoningSwitch.isChecked();
        OmmaAiConfig.save();
        OmmaAiOverlay.updateVisibility();
    }

    // ---------- view helpers ----------

    private interface TextSetter {
        void set(String text);
    }

    private static TextWatcher watcher(TextSetter setter) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                setter.set(s.toString());
            }
        };
    }

    private void toast(String text) {
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), text, Toast.LENGTH_SHORT).show();
        }
    }

    private static LinearLayout.LayoutParams cardParams() {
        return LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12);
    }

    private LinearLayout card(Context context, String title) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        card.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundWhite), 16));

        TextView header = new TextView(context);
        header.setText(title);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(ACCENT);
        header.setPadding(0, 0, 0, AndroidUtilities.dp(8));
        card.addView(header);
        return card;
    }

    private TextView label(Context context, LinearLayout parent, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        label.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
        parent.addView(label);
        return label;
    }

    private void hint(Context context, LinearLayout parent, String text) {
        TextView hint = new TextView(context);
        hint.setText(text);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        hint.setPadding(0, AndroidUtilities.dp(2), 0, AndroidUtilities.dp(6));
        parent.addView(hint);
    }

    private TextView valueRow(Context context, LinearLayout parent, String title) {
        label(context, parent, title);
        TextView value = new TextView(context);
        value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        value.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        value.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(11), AndroidUtilities.dp(12), AndroidUtilities.dp(11));
        value.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundGray), 12));
        parent.addView(value, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        return value;
    }

    private EditText field(Context context, LinearLayout parent, String title, String hint, boolean secret) {
        label(context, parent, title);
        EditText field = plainInput(context, hint);
        field.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundGray), 12));
        field.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        if (secret) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        parent.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        return field;
    }

    private EditText plainInput(Context context, String hint) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setBackground(null);
        return input;
    }

    private Switch toggle(Context context, LinearLayout parent, String title, boolean checked) {
        Switch toggle = new Switch(context);
        toggle.setText(title);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        toggle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        toggle.setChecked(checked);
        toggle.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        parent.addView(toggle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return toggle;
    }

    private TextView button(Context context, String text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(0xFFFFFFFF);
        button.setGravity(Gravity.CENTER);
        button.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        button.setBackground(rounded(ACCENT, 12));
        return button;
    }

    private static GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(AndroidUtilities.dp(radiusDp));
        return drawable;
    }
}
