package com.omma.messenger.ai;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;

import java.lang.ref.WeakReference;

/** Draggable floating AI button shown above every screen of LaunchActivity. */
public final class OmmaAiOverlay {

    private static final int SIZE_DP = 56;
    private static WeakReference<FabView> fabRef;

    private OmmaAiOverlay() {
    }

    /** Call once from LaunchActivity.onCreate after the root overlays are added. */
    public static void attach(Context context, FrameLayout container) {
        FabView fab = new FabView(context, container);
        int size = AndroidUtilities.dp(SIZE_DP);
        container.addView(fab, new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT));
        container.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fab.applyPosition());
        fabRef = new WeakReference<>(fab);
        // The fragment stack is not wired up yet at this point of onCreate: reading it now
        // crashes with an NPE. The first visibility pass runs on the next frame instead.
        AndroidUtilities.runOnUIThread(OmmaAiOverlay::updateVisibility);
    }

    /** Call when the fragment stack, account or settings change. */
    public static void updateVisibility() {
        FabView fab = fabRef == null ? null : fabRef.get();
        if (fab == null) {
            return;
        }
        org.telegram.ui.ActionBar.BaseFragment last = null;
        try {
            last = org.telegram.ui.LaunchActivity.getSafeLastFragment();
        } catch (Throwable ignore) {
            // The stack may still be uninitialized during startup or configuration changes.
        }
        boolean aiChatOpen = last instanceof OmmaAiChatActivity;
        boolean visible = !aiChatOpen
            && OmmaAiConfig.fabEnabled
            && UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()
            && !isAppLocked();
        fab.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    static boolean isAppLocked() {
        return SharedConfig.passcodeHash.length() > 0 && SharedConfig.appLocked;
    }

    @SuppressLint("ViewConstructor")
    private static final class FabView extends View {

        private final FrameLayout container;
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path iconPath = new Path();
        private final int touchSlop;

        private float downX;
        private float downY;
        private float startX;
        private float startY;
        private boolean dragging;

        FabView(Context context, FrameLayout container) {
            super(context);
            this.container = container;
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            iconPaint.setColor(0xFFFFFFFF);
            iconPaint.setStyle(Paint.Style.FILL);
            setContentDescription("Omma AI");
            setClickable(true);
            setElevation(AndroidUtilities.dp(6));
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            backgroundPaint.setShader(new LinearGradient(0, 0, w, h, 0xFF2563EB, 0xFF3B82F6, Shader.TileMode.CLAMP));
            iconPath.reset();
            addStar(iconPath, w * 0.46f, h * 0.50f, w * 0.24f);
            addStar(iconPath, w * 0.70f, h * 0.70f, w * 0.10f);
        }

        private static void addStar(Path path, float cx, float cy, float radius) {
            float inner = radius * 0.3f;
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI / 4 * i - Math.PI / 2;
                float r = i % 2 == 0 ? radius : inner;
                float x = cx + (float) (Math.cos(angle) * r);
                float y = cy + (float) (Math.sin(angle) * r);
                if (i == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
            path.close();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float radius = getWidth() / 2f;
            canvas.drawCircle(radius, radius, radius, backgroundPaint);
            canvas.drawPath(iconPath, iconPaint);
        }

        private int size() {
            return AndroidUtilities.dp(SIZE_DP);
        }

        private float maxX() {
            return Math.max(0, container.getWidth() - size());
        }

        private float maxY() {
            return Math.max(0, container.getHeight() - size());
        }

        void applyPosition() {
            if (container.getWidth() == 0 || dragging) {
                return;
            }
            float x;
            float y;
            if (OmmaAiConfig.fabX < 0 || OmmaAiConfig.fabY < 0) {
                x = maxX() - AndroidUtilities.dp(16);
                y = maxY() - AndroidUtilities.dp(140);
            } else {
                x = OmmaAiConfig.fabX * maxX();
                y = OmmaAiConfig.fabY * maxY();
            }
            setTranslationX(clamp(x, maxX()));
            setTranslationY(clamp(y, maxY()));
        }

        private static float clamp(float value, float max) {
            return Math.max(0, Math.min(value, max));
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = getTranslationX();
                    startY = getTranslationY();
                    dragging = false;
                    animate().scaleX(0.92f).scaleY(0.92f).setDuration(120).start();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                        dragging = true;
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }
                    if (dragging) {
                        setTranslationX(clamp(startX + dx, maxX()));
                        setTranslationY(clamp(startY + dy, maxY()));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    boolean wasDrag = dragging;
                    dragging = false;
                    if (wasDrag) {
                        float fx = maxX() == 0 ? 0 : getTranslationX() / maxX();
                        float fy = maxY() == 0 ? 0 : getTranslationY() / maxY();
                        OmmaAiConfig.saveFabPosition(fx, fy);
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        performClick();
                    }
                    return true;
                }
            }
            return super.onTouchEvent(event);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            android.content.Context context = getContext();
            try {
                org.telegram.ui.ActionBar.BaseFragment last = org.telegram.ui.LaunchActivity.getSafeLastFragment();
                if (last != null) {
                    org.telegram.ui.ChatActivity chat = last instanceof org.telegram.ui.ChatActivity ? (org.telegram.ui.ChatActivity) last : null;
                    last.presentFragment(new OmmaAiChatActivity(chat));
                    return true;
                }
            } catch (Throwable ignore) {
                // The stack may not be ready; fall through to the toast below.
            }
            android.widget.Toast.makeText(context, "Подожди, приложение загружается…", android.widget.Toast.LENGTH_SHORT).show();
            return true;
        }
    }
}
