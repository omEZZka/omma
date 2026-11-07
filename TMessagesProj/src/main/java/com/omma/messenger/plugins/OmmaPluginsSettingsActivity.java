package com.omma.messenger.plugins;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;

/** Import and status screen for official exteraGram Python/Elyx plugin containers. */
public final class OmmaPluginsSettingsActivity extends BaseFragment {
    private static final int PICK_PLUGIN = 7001;
    private LinearLayout list;

    @Override
    public View createView(android.content.Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setTitle("Плагины");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(20));
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        TextView info = text(context, "Omma поддерживает плагины exteraGram: Python (.py) и Elyx (.elyx/.eaf). Файл копируется в закрытое хранилище приложения.", 13);
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        root.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        TextView importButton = button(context, "Импортировать плагин");
        importButton.setOnClickListener(v -> pickPlugin());
        root.addView(importButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        renderPlugins(context);
        fragmentView = root;
        return root;
    }

    private void pickPlugin() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_PLUGIN);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode == PICK_PLUGIN && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            boolean imported = OmmaPluginManager.importPlugin(getParentActivity(), data.getData());
            String pluginId = null;
            if (imported) {
                File directory = new File(getParentActivity().getFilesDir(), "extera_plugins");
                File[] files = directory.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (!OmmaPluginManager.getEnabledPlugins().contains(safeId(file))) {
                            pluginId = OmmaPluginManager.loadPluginFile(file);
                            if (pluginId != null) {
                                OmmaPluginManager.setPluginEnabled(pluginId, true);
                            }
                            break;
                        }
                    }
                }
            }
            String message;
            if (pluginId != null) {
                message = "Плагин загружен: " + pluginId;
            } else if (imported) {
                message = "Импортировано, но запуск не удался";
            } else {
                message = "Файл не распознан";
            }
            android.widget.Toast.makeText(getParentActivity(), message, android.widget.Toast.LENGTH_SHORT).show();
            if (list != null) renderPlugins(getParentActivity());
        }
    }

    private static String safeId(File file) {
        return file.getName();
    }

    private void renderPlugins(android.content.Context context) {
        list.removeAllViews();
        File directory = new File(context.getFilesDir(), "extera_plugins");
        File[] files = directory.listFiles();
        if (files == null || files.length == 0) {
            TextView empty = text(context, "Пока нет импортированных плагинов.", 14);
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            list.addView(empty);
            return;
        }
        for (File file : files) {
            String id = OmmaPluginManager.pluginIdForFileName(file);
            String status = id != null && OmmaPluginManager.getEnabledPlugins().contains(id) ? " · работает" : "";
            TextView item = text(context, file.getName() + status, 15);
            item.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            item.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
            item.setOnClickListener(v -> {
                if (id == null) {
                    return;
                }
                boolean enabled = OmmaPluginManager.getEnabledPlugins().contains(id);
                if (enabled) {
                    OmmaPluginManager.setPluginEnabled(id, false);
                } else {
                    OmmaPluginManager.loadPluginFile(file);
                    OmmaPluginManager.setPluginEnabled(id, true);
                }
                renderPlugins(context);
            });
            list.addView(item, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));
        }
    }

    private static TextView text(android.content.Context context, String value, int size) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        return view;
    }

    private static TextView button(android.content.Context context, String value) {
        TextView view = text(context, value, 14);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(0xFFFFFFFF);
        view.setTypeface(AndroidUtilities.bold());
        view.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(0xFF2563EB);
        background.setCornerRadius(AndroidUtilities.dp(12));
        view.setBackground(background);
        return view;
    }
}
