package com.omma.messenger.ai;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * "Thinking" placeholder: three dots that rise and fade in sequence while the model answers.
 * The animation stops on detach so it never keeps the CPU busy in the background.
 */
@SuppressLint("ViewConstructor")
public final class OmmaThinkingView extends LinearLayout {

    private static final int DOT_COUNT = 3;
    private static final int DOT_PERIOD_MS = 1400;
    private static final int FRAME_MS = 50;

    private final DotsView dots;
    private final TextView label;

    public OmmaThinkingView(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(9), AndroidUtilities.dp(14), AndroidUtilities.dp(9));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Theme.getColor(Theme.key_dialogBackgroundGray));
        background.setCornerRadius(AndroidUtilities.dp(16));
        setBackground(background);

        dots = new DotsView(context);
        addView(dots, LayoutHelper.createLinear(AndroidUtilities.dp(28), AndroidUtilities.dp(18)));

        label = new TextView(context);
        label.setText("Думает…");
        label.setTextSize(13);
        label.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 6, 0, 0, 0));
    }

    public void setDotsHidden(boolean hidden) {
        dots.setVisibility(hidden ? GONE : VISIBLE);
        if (hidden) {
            label.setText("");
        }
    }

    @SuppressLint("ViewConstructor")
    private static final class DotsView extends View implements Runnable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private long startedAt;
        private boolean running;

        DotsView(Context context) {
            super(context);
            density = AndroidUtilities.density;
            paint.setColor(0xFF2563EB);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            startedAt = System.currentTimeMillis();
            running = true;
            postDelayed(this, FRAME_MS);
        }

        @Override
        protected void onDetachedFromWindow() {
            running = false;
            removeCallbacks(this);
            super.onDetachedFromWindow();
        }

        @Override
        public void run() {
            if (!running) {
                return;
            }
            invalidate();
            postDelayed(this, FRAME_MS);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float radius = 3f * density;
            float spacing = 8f * density;
            float centerY = getHeight() / 2f;
            float startX = (getWidth() - spacing * (DOT_COUNT - 1)) / 2f;
            float phase = (System.currentTimeMillis() - startedAt) % DOT_PERIOD_MS / (float) DOT_PERIOD_MS;

            for (int i = 0; i < DOT_COUNT; i++) {
                float local = (phase - i * 0.16f + 1f) % 1f;
                float wave = (float) Math.sin(local * Math.PI * 2f);
                float lift = Math.max(0f, wave) * 3.2f * density;
                int alpha = (int) (90 + Math.max(0f, wave) * 165);
                paint.setAlpha(Math.min(255, alpha));
                canvas.drawCircle(startX + i * spacing, centerY - lift, radius, paint);
            }
        }
    }
}
