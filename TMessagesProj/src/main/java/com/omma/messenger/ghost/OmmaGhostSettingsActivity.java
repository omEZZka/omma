package com.omma.messenger.ghost;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/** Settings screen of the Omma ghost mode. */
public class OmmaGhostSettingsActivity extends BaseFragment {

    private static final int ACCENT = 0xFF2563EB;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Призрак");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(24));
        scrollView.addView(root);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        card.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundWhite), 16));
        root.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        TextView header = new TextView(context);
        header.setText("Скрыть активность");
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(ACCENT);
        header.setPadding(0, 0, 0, AndroidUtilities.dp(8));
        card.addView(header);

        hint(context, card, "Пока включён призрак, собеседники не видят, что ты прочитал сообщения, печатал или был онлайн. Просмотры историй тоже не отправляются.");

        toggle(context, card, "Не отправлять «прочитано»", OmmaGhostConfig.hideReadReceipts, value -> OmmaGhostConfig.hideReadReceipts = value);
        toggle(context, card, "Не показывать «в сети»", OmmaGhostConfig.hideOnlineStatus, value -> OmmaGhostConfig.hideOnlineStatus = value);
        toggle(context, card, "Не показывать «печатает…»", OmmaGhostConfig.hideTyping, value -> OmmaGhostConfig.hideTyping = value);
        toggle(context, card, "Не показывать просмотры историй", OmmaGhostConfig.hideStoryViews, value -> OmmaGhostConfig.hideStoryViews = value);
        toggle(context, card, "Читать чат после отправки сообщения", OmmaGhostConfig.markReadAfterSend, value -> OmmaGhostConfig.markReadAfterSend = value);

        fragmentView = scrollView;
        return fragmentView;
    }

    private interface ValueSetter {
        void set(boolean value);
    }

    private void toggle(Context context, LinearLayout parent, String title, boolean checked, ValueSetter setter) {
        Switch toggle = new Switch(context);
        toggle.setText(title);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        toggle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        toggle.setChecked(checked);
        toggle.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            setter.set(isChecked);
            OmmaGhostConfig.save();
        });
        parent.addView(toggle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void hint(Context context, LinearLayout parent, String text) {
        TextView hint = new TextView(context);
        hint.setText(text);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        hint.setPadding(0, AndroidUtilities.dp(2), 0, AndroidUtilities.dp(10));
        parent.addView(hint);
    }

    private static GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(AndroidUtilities.dp(radiusDp));
        return drawable;
    }
}
