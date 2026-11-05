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

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;

/** Settings screen of the Omma AI assistant. Changes are saved when the screen is closed. */
public class OmmaAiSettingsActivity extends BaseFragment {

    private static final int ACCENT = 0xFF2563EB;

    private TextView providerValue;
    private TextView modelValue;
    private TextView keyLink;
    private EditText baseUrlField;
    private EditText apiKeyField;
    private EditText modelField;
    private EditText systemPromptField;
    private TextView temperatureLabel;
    private SeekBar temperatureBar;
    private Switch contextSwitch;
    private Switch fabSwitch;
    private LinearLayout templatesLayout;
    private String selectedProvider;

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
        root.addView(buildAssistantCard(context), cardParams());
        root.addView(buildTemplatesCard(context), cardParams());

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

        TextView loadModels = button(context, "Загрузить модели");
        loadModels.setOnClickListener(v -> loadModels());
        card.addView(loadModels, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 8));

        modelValue = valueRow(context, card, "Модель из каталога");
        modelValue.setOnClickListener(v -> showCatalogPicker());
        modelField = field(context, card, "Идентификатор модели", "например, gpt-4o-mini", false);
        modelField.setText(OmmaAiConfig.model);

        hint(context, card, "Ключ хранится только на устройстве и зашифрован Android Keystore.");

        updateProviderViews();
        updateModelValue();
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
            if (!OmmaAiConfig.getModelCatalog(provider.id).isEmpty()) {
                OmmaAiConfig.ModelOption first = OmmaAiConfig.getModelCatalog(provider.id).get(0);
                modelField.setText(first.id);
            }
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
            toast(OmmaAiConfig.requiresApiKey(selectedProvider) ? "Укажи base URL и API-ключ" : "Укажи base URL");
            return;
        }
        toast("Загружаю модели…");
        OmmaAiClient.fetchModels(baseUrl, apiKey, (models, error) -> {
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
            showModelPicker(models);
        });
    }

    private void showModelPicker(List<String> models) {
        java.util.ArrayList<String> allModels = new java.util.ArrayList<>();
        for (OmmaAiConfig.ModelOption option : OmmaAiConfig.getModelCatalog(selectedProvider)) {
            allModels.add(option.id);
        }
        for (String model : OmmaAiConfig.getDiscoveredModels(selectedProvider)) {
            if (!allModels.contains(model)) {
                allModels.add(model);
            }
        }
        for (String model : models) {
            if (!allModels.contains(model)) {
                allModels.add(model);
            }
        }
        CharSequence[] items = allModels.toArray(new CharSequence[0]);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Моделей: " + allModels.size());
        builder.setItems(items, (dialog, which) -> {
            modelField.setText(allModels.get(which));
            updateModelValue();
        });
        showDialog(builder.create());
    }

    private void showCatalogPicker() {
        List<OmmaAiConfig.ModelOption> catalog = OmmaAiConfig.getModelCatalog();
        CharSequence[] items = new CharSequence[catalog.size()];
        for (int i = 0; i < catalog.size(); i++) {
            OmmaAiConfig.ModelOption option = catalog.get(i);
            String badge = option.free ? " · бесплатно" : "";
            String key = option.requiresApiKey ? " · ключ" : " · без ключа";
            items[i] = option.title + badge + key + "\n" + option.id;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Каталог моделей");
        builder.setItems(items, (dialog, which) -> {
            OmmaAiConfig.ModelOption option = catalog.get(which);
            selectedProvider = option.provider;
            OmmaAiConfig.Provider provider = OmmaAiConfig.findProvider(option.provider);
            if (!TextUtils.isEmpty(provider.baseUrl)) {
                baseUrlField.setText(provider.baseUrl);
            }
            modelField.setText(option.id);
            updateProviderViews();
            updateModelValue();
        });
        showDialog(builder.create());
    }

    private void updateModelValue() {
        if (modelValue == null || modelField == null) {
            return;
        }
        OmmaAiConfig.ModelOption selected = OmmaAiConfig.findModel(selectedProvider, modelField.getText().toString().trim());
        modelValue.setText(selected == null ? "Выбрать из постоянного каталога" : selected.title);
    }

    // ---------- assistant ----------

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
        return card;
    }

    private void updateTemperatureLabel() {
        temperatureLabel.setText(String.format(java.util.Locale.US, "Температура: %.1f", temperatureBar.getProgress() / 10f));
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
        OmmaAiConfig.model = modelField.getText().toString().trim();
        OmmaAiConfig.systemPrompt = systemPromptField.getText().toString();
        OmmaAiConfig.temperature = temperatureBar.getProgress() / 10f;
        OmmaAiConfig.useContext = contextSwitch.isChecked();
        OmmaAiConfig.fabEnabled = fabSwitch.isChecked();
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
