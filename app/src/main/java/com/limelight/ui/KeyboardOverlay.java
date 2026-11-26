package com.limelight.ui;

import android.annotation.SuppressLint;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.limelight.R;
import com.limelight.binding.input.keyboard.ModifierState;
import com.limelight.binding.input.keyboard.KeyboardLayout;
import com.limelight.binding.input.keyboard.VirtualKey;
import com.limelight.nvstream.NvConnection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.limelight.binding.input.keyboard.VirtualKey.*;

/** In-session keyboard accessories. Only the video view is translated; its size never changes. */
public final class KeyboardOverlay {
    public interface Host { void sendVirtualKey(VirtualKey key, boolean down); }
    private enum Mode { CLOSED, KEYBOARD, PANEL }

    // This is the complete main strip. Landscape adds the navigation group below.
    private static final VirtualKey[] STRIP_KEYS = { ESC, TAB, SHIFT, CTRL, ALT, WIN, CAPS };
    private static final VirtualKey[] LANDSCAPE_KEYS = { LEFT, UP, DOWN, RIGHT, DELETE };
    private static final int BACKGROUND = 0xF2212121, ACCENT = 0xFF80CBC4;
    private final Activity activity;
    private final StreamView stream;
    private final NvConnection connection;
    private final Host host;
    private final FrameLayout overlay;
    private final LinearLayout tray;
    private final LinearLayout bar;
    private final LinearLayout panel;
    private final Button floating;
    private final Button more;
    private final SharedPreferences positionPrefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ModifierState modifiers;
    private final List<Button> stateButtons = new ArrayList<>();
    private final List<Runnable> longPressCallbacks = new ArrayList<>();
    private Mode mode = Mode.CLOSED;
    private boolean connected, suspended, pip, caps, imeSeen, waitingForIme, imeVisible;
    private boolean directTouchActive, pointerUpdateQueued, imeAnimating;
    private boolean rightEdge;
    private float yFraction;
    private int imeHeight, lastImeHeight, safeLeft, safeRight, safeTop, safeBottom;
    private int inputGeneration;
    private ValueAnimator snapAnimator;
    private final Runnable dim = () -> floatingFade();
    private final Runnable imeFallback = () -> {
        if (mode == Mode.KEYBOARD && !imeVisible) showPanel();
    };

    public KeyboardOverlay(Activity activity, StreamView stream, NvConnection connection, Host host) {
        this.activity = activity;
        this.stream = stream;
        this.connection = connection;
        this.host = host;
        positionPrefs = activity.getSharedPreferences("KeyboardOverlay", Context.MODE_PRIVATE);
        rightEdge = positionPrefs.getBoolean("rightEdge", true);
        yFraction = positionPrefs.getFloat("yFraction", 0.5f);
        modifiers = new ModifierState(new ModifierState.Listener() {
            @Override public void send(VirtualKey key, boolean down) { host.sendVirtualKey(key, down); }
            @Override public void changed() { handler.post(KeyboardOverlay.this::refreshStates); }
        });
        overlay = new FrameLayout(activity);
        overlay.setClipChildren(false);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ((FrameLayout)stream.getParent()).addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        tray = new LinearLayout(activity);
        tray.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams trayParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        overlay.addView(tray, trayParams);
        bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> adaptStrip());
        bar.setBackground(new android.graphics.drawable.InsetDrawable(shape(BACKGROUND, 0, 0, 0), 0, dp(8), 0, 0));
        tray.addView(bar, new LinearLayout.LayoutParams(-1, dp(48)));
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(BACKGROUND);
        tray.addView(panel, new LinearLayout.LayoutParams(-1, 0));
        more = control("⋯", R.string.keyboard_more, () -> {
            if (mode == Mode.PANEL) showKeyboard(); else showPanel();
        });
        floating = control("", R.string.keyboard_show, this::toggle);
        floating.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_keyboard_overlay, 0, 0, 0);
        floating.setPadding(dp(12), dp(12), dp(12), dp(12));
        floating.setBackground(shape(0x99000000, 24, 0x4DFFFFFF, 1));
        overlay.addView(floating, new FrameLayout.LayoutParams(dp(48), dp(48)));
        installDrag();
        rebuildKeys();
        tray.setVisibility(View.GONE);
        floating.setVisibility(View.GONE);
        overlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                placeFloating(false);
                layoutTray();
            }
        });
        overlay.setOnApplyWindowInsetsListener((view, insets) -> {
            if (!imeAnimating) updateInsets(insets);
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) {
            overlay.setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(
                    WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                @Override public void onPrepare(WindowInsetsAnimation animation) {
                    if ((animation.getTypeMask() & WindowInsets.Type.ime()) != 0) imeAnimating = true;
                }
                @Override public void onEnd(WindowInsetsAnimation animation) {
                    if ((animation.getTypeMask() & WindowInsets.Type.ime()) != 0) {
                        imeAnimating = false;
                        if (overlay.getRootWindowInsets() != null) updateInsets(overlay.getRootWindowInsets());
                    }
                }
                @Override public WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> running) {
                    updateInsets(insets);
                    return insets;
                }
            });
        } else {
            // adjustNothing keeps the SurfaceView fixed, including on pre-insets-animation Android.
            overlay.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                Rect visible = new Rect();
                overlay.getWindowVisibleDisplayFrame(visible);
                int[] location = new int[2];
                overlay.getLocationOnScreen(location);
                int covered = Math.max(0, location[1] + overlay.getHeight() - visible.bottom);
                updateIme(covered > dp(100), covered > dp(100) ? covered : 0);
            });
        }
        overlay.requestApplyInsets();
    }

    public ModifierState modifiers() { return modifiers; }
    public boolean isOpen() { return mode != Mode.CLOSED; }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private boolean landscape() {
        return activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }
    private GradientDrawable shape(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (strokeWidth > 0) drawable.setStroke(dp(strokeWidth), strokeColor);
        return drawable;
    }
    private Button baseButton(String label, String description) {
        Button button = new Button(activity);
        button.setText(label);
        button.setTextSize(12);
        button.setTextColor(0xFFFFFFFF);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(2), dp(8), dp(2), 0);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(false); // Keep the stream as the IME's served view.
        button.setContentDescription(description);
        if (Build.VERSION.SDK_INT >= 26) button.setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1, 2);
        return button;
    }
    private Button control(String text, int description, Runnable action) {
        Button button = baseButton(text, activity.getString(description));
        button.setBackgroundColor(0x00000000);
        button.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            action.run();
        });
        return button;
    }
    private void styleKey(Button button) {
        VirtualKey key = (VirtualKey)button.getTag();
        ModifierState.State state = key.modifier == 0 ? ModifierState.State.OFF : modifiers.state(key);
        boolean locked = state == ModifierState.State.LOCKED;
        boolean lit = state != ModifierState.State.OFF;
        GradientDrawable background = shape(locked ? ACCENT : button.isPressed() ? 0xFF4A4A4A : 0x00303030,
                5, ACCENT, lit && !locked ? 2 : 0);
        // The visual strip is 40dp; the transparent 8dp above it extends each target to 48dp.
        button.setBackground(new android.graphics.drawable.InsetDrawable(background, dp(2), dp(8), dp(2), 0));
        button.setTextColor(locked ? 0xFF000000 : lit ? ACCENT : 0xFFFFFFFF);
        String description = activity.getString(key.description);
        String stateText = activity.getString(locked ? R.string.keyboard_locked : lit ? R.string.keyboard_next : R.string.keyboard_off);
        if (key == CAPS) {
            button.setText(activity.getString(R.string.keyboard_caps_state_label,
                    activity.getString(R.string.keyboard_caps_label), caps ? "●" : "○"));
            stateText = activity.getString(caps ? R.string.keyboard_on : R.string.keyboard_off);
        }
        if (key.modifier != 0 || key == CAPS) {
            if (Build.VERSION.SDK_INT >= 30) button.setStateDescription(stateText);
            else description += ", " + stateText;
        }
        button.setContentDescription(description);
    }
    private void refreshStates() {
        for (Button button : stateButtons) styleKey(button);
    }
    private void stroke(VirtualKey key) {
        stream.flushImeComposition();
        host.sendVirtualKey(key, true);
        host.sendVirtualKey(key, false);
        if (key == CAPS) { caps = !caps; refreshStates(); }
    }
    @SuppressLint("ClickableViewAccessibility")
    private Button keyButton(VirtualKey key) {
        Button button = baseButton(key.label, activity.getString(key.description));
        button.setTag(key);
        stateButtons.add(button);
        final boolean[] touching = { false }, cancelled = { false }, repeated = { false };
        final float[] start = new float[2];
        final int[] generation = { 0 };
        int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        boolean repeatable = key == BACKSPACE || key == LEFT || key == UP || key == DOWN || key == RIGHT;
        Runnable repeat = new Runnable() {
            @Override public void run() {
                if (!touching[0] || cancelled[0] || generation[0] != inputGeneration) return;
                repeated[0] = true;
                stroke(key); // Complete strokes: never rely on a WAN key-up to stop host repeat.
                handler.postDelayed(this, 70);
            }
        };
        Runnable lock = () -> {
            if (touching[0] && !cancelled[0] && generation[0] == inputGeneration && modifiers.longPress(key)) {
                button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
        };
        longPressCallbacks.add(lock);
        longPressCallbacks.add(repeat);
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touching[0] = true;
                    cancelled[0] = repeated[0] = false;
                    generation[0] = inputGeneration;
                    start[0] = event.getX(); start[1] = event.getY();
                    button.setPressed(true);
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (key.modifier != 0) {
                        stream.flushImeComposition();
                        modifiers.press(key);
                        handler.postDelayed(lock, ViewConfiguration.getLongPressTimeout());
                    } else if (repeatable) handler.postDelayed(repeat, ViewConfiguration.getLongPressTimeout());
                    refreshStates();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(event.getX() - start[0], event.getY() - start[1]) > slop) {
                        cancelled[0] = true;
                        handler.removeCallbacks(lock); handler.removeCallbacks(repeat);
                        if (key.modifier != 0) modifiers.release(key, true);
                        button.setPressed(false);
                        refreshStates();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!touching[0]) return true;
                    handler.removeCallbacks(lock); handler.removeCallbacks(repeat);
                    cancelled[0] |= event.getActionMasked() == MotionEvent.ACTION_CANCEL || generation[0] != inputGeneration;
                    if (key.modifier != 0) modifiers.release(key, cancelled[0]);
                    else if (!cancelled[0] && !repeated[0]) stroke(key);
                    button.setPressed(false);
                    if (!cancelled[0]) button.performClick();
                    touching[0] = false;
                    refreshStates();
                    return true;
                default: return true;
            }
        });
        button.setOnClickListener(v -> {
            if (touching[0]) return;
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (key.modifier != 0) { stream.flushImeComposition(); modifiers.press(key); modifiers.release(key, false); }
            else stroke(key);
            refreshStates();
        });
        if (key.modifier != 0) button.setOnLongClickListener(v -> {
            stream.flushImeComposition();
            modifiers.press(key); modifiers.longPress(key); modifiers.release(key, false);
            return true;
        });
        styleKey(button);
        return button;
    }
    private void adaptStrip() {
        if (!landscape() || bar.getWidth() == 0) return;
        int cell = KeyboardLayout.cellWidth(bar.getWidth() - bar.getPaddingLeft() - bar.getPaddingRight(), 14, dp(1), dp(48));
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (child instanceof Button) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)child.getLayoutParams();
                if (params.width != cell) { params.width = cell; child.setLayoutParams(params); }
            }
        }
    }
    private void rebuildKeys() {
        for (Runnable callback : longPressCallbacks) handler.removeCallbacks(callback);
        longPressCallbacks.clear();
        stateButtons.clear();
        bar.removeAllViews();
        boolean wide = landscape();
        for (VirtualKey key : STRIP_KEYS) {
            bar.addView(keyButton(key), wide ? new LinearLayout.LayoutParams(dp(48), dp(48)) :
                    new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        if (wide) {
            View divider = new View(activity);
            divider.setBackgroundColor(0xFF555555);
            bar.addView(divider, new LinearLayout.LayoutParams(dp(1), dp(20)));
            for (VirtualKey key : LANDSCAPE_KEYS) bar.addView(keyButton(key), new LinearLayout.LayoutParams(dp(48), dp(48)));
            bar.addView(new View(activity), new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        bar.addView(more, wide ? new LinearLayout.LayoutParams(dp(48), dp(48)) :
                new LinearLayout.LayoutParams(0, dp(48), 1));
        if (wide) bar.addView(control("⌄", R.string.keyboard_hide, this::close), new LinearLayout.LayoutParams(dp(48), dp(48)));
        bar.post(this::adaptStrip);
        panel.removeAllViews();
        VirtualKey[][] rows = {
                {INSERT,HOME,PAGE_UP,null,UP,null}, {DELETE,END,PAGE_DOWN,LEFT,DOWN,RIGHT},
                {F1,F2,F3,F4,F5,F6}, {F7,F8,F9,F10,F11,F12},
                {PRINT_SCREEN,SPACE,BACKSPACE,ENTER}
        };
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = new LinearLayout(activity);
            panel.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
            for (VirtualKey key : rows[r]) {
                row.addView(key == null ? new View(activity) : keyButton(key),
                        new LinearLayout.LayoutParams(0, -1, key == SPACE ? 2 : 1));
            }
            if (r == rows.length - 1) row.addView(control("⌄", R.string.keyboard_hide, this::close),
                    new LinearLayout.LayoutParams(0, -1, 1));
        }
    }

    public void toggle() {
        if (isOpen()) close(); else if (connected && !suspended && !pip) showKeyboard();
    }
    private void showKeyboard() {
        waitingForIme = mode == Mode.PANEL;
        mode = Mode.KEYBOARD;
        imeSeen = false;
        tray.setVisibility(View.VISIBLE);
        floating.setVisibility(View.GONE);
        more.setText("⋯");
        more.setContentDescription(activity.getString(R.string.keyboard_more));
        stream.requestFocus();
        if (Build.VERSION.SDK_INT >= 30 && stream.getWindowInsetsController() != null) {
            stream.getWindowInsetsController().show(WindowInsets.Type.ime());
        } else {
            ((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(stream, 0);
        }
        handler.removeCallbacks(imeFallback);
        handler.postDelayed(imeFallback, 450);
        layoutTray();
    }
    private void showPanel() {
        handler.removeCallbacks(imeFallback);
        mode = Mode.PANEL;
        waitingForIme = false;
        hideIme();
        more.setText(R.string.keyboard_letters_label);
        more.setContentDescription(activity.getString(R.string.keyboard_letters));
        layoutTray();
    }
    private void hideIme() {
        if (Build.VERSION.SDK_INT >= 30 && stream.getWindowInsetsController() != null) {
            stream.getWindowInsetsController().hide(WindowInsets.Type.ime());
        } else {
            ((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(stream.getWindowToken(), 0);
        }
    }
    public void close() {
        mode = Mode.CLOSED;
        directTouchActive = false;
        waitingForIme = imeSeen = false;
        handler.removeCallbacks(imeFallback);
        releaseOwnedKeys();
        tray.setVisibility(View.GONE);
        hideIme();
        stream.animate().cancel();
        stream.animate().translationY(0).setDuration(200).start();
        refreshVisibility();
    }
    public void releaseOwnedKeys() {
        inputGeneration++;
        for (Runnable runnable : longPressCallbacks) handler.removeCallbacks(runnable);
        modifiers.releaseAll();
        for (Button button : stateButtons) button.setPressed(false);
        refreshStates();
    }
    public void setConnected(boolean connected) {
        this.connected = connected;
        if (!connected) close();
        refreshVisibility();
    }
    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
        if (suspended) close();
        refreshVisibility();
    }
    public void configurationChanged(boolean pip) {
        this.pip = pip;
        close();
        lastImeHeight = 0;
        rebuildKeys();
        overlay.requestApplyInsets();
        overlay.post(() -> { placeFloating(false); layoutTray(); });
    }
    public void destroy() {
        close();
        if (snapAnimator != null) snapAnimator.cancel();
        handler.removeCallbacksAndMessages(null);
    }
    private void refreshVisibility() {
        boolean enabled = PreferenceManager.getDefaultSharedPreferences(activity).getBoolean("checkbox_keyboard_button", true);
        floating.setVisibility(connected && !suspended && !pip && !isOpen() && enabled ? View.VISIBLE : View.GONE);
        if (floating.getVisibility() == View.VISIBLE) wakeFloating();
    }
    private void updateInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            safeLeft = safe.left; safeRight = safe.right; safeTop = safe.top; safeBottom = safe.bottom;
            updateIme(insets.isVisible(WindowInsets.Type.ime()), insets.getInsets(WindowInsets.Type.ime()).bottom);
        } else {
            safeLeft = insets.getSystemWindowInsetLeft(); safeRight = insets.getSystemWindowInsetRight();
            safeTop = insets.getSystemWindowInsetTop();
            // The legacy bottom inset can include the IME; global-layout handles it separately.
            safeBottom = Math.min(insets.getSystemWindowInsetBottom(), dp(48));
        }
        placeFloating(false);
        layoutTray();
    }
    private void updateIme(boolean visible, int height) {
        boolean wasVisible = imeVisible;
        imeVisible = visible;
        imeHeight = height;
        if (visible) {
            imeSeen = true;
            if (height > dp(100)) lastImeHeight = height;
            if (waitingForIme) waitingForIme = false;
        } else if (wasVisible && imeSeen && mode == Mode.KEYBOARD && !waitingForIme) {
            close();
        }
        layoutTray();
    }
    private int panelHeight() {
        int minimum = dp(220) + safeBottom; // Five rows, at least 44dp each in either orientation.
        return Math.max(minimum, lastImeHeight > 0 ? lastImeHeight : dp(landscape() ? 200 : 280));
    }
    private void layoutTray() {
        if (!isOpen()) return;
        boolean showPanel = mode == Mode.PANEL || waitingForIme;
        int height = showPanel ? panelHeight() : 0;
        panel.setVisibility(showPanel ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams panelParams = (LinearLayout.LayoutParams)panel.getLayoutParams();
        if (panelParams.height != height) { panelParams.height = height; panel.setLayoutParams(panelParams); }
        panel.setPadding(safeLeft, 0, safeRight, safeBottom);
        bar.setPadding(safeLeft, 0, safeRight, 0);
        adaptStrip();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)tray.getLayoutParams();
        int bottom = showPanel ? 0 : Math.max(imeHeight, safeBottom);
        if (params.bottomMargin != bottom) { params.bottomMargin = bottom; tray.setLayoutParams(params); }
        pan(Build.VERSION.SDK_INT < 30);
    }
    public void pointerMoved() {
        // Coalesce to the UI thread, after the current input event's coordinate mapping completes.
        synchronized (this) {
            if (pointerUpdateQueued) return;
            pointerUpdateQueued = true;
        }
        handler.post(() -> {
            synchronized (KeyboardOverlay.this) { pointerUpdateQueued = false; }
            pan(false);
        });
    }
    public void directTouch(boolean active) {
        directTouchActive = active;
        if (!active) pointerMoved();
    }
    private void pan(boolean animate) {
        if (!isOpen() || directTouchActive) return;
        boolean panelVisible = mode == Mode.PANEL || waitingForIme;
        int covered = (panelVisible ? panelHeight() : Math.max(imeHeight, safeBottom)) + dp(48);
        float shift = covered; // Unknown relative cursor: expose the bottom composer by default.
        if (connection.hasAbsolutePointer()) {
            float cursorY = stream.getTop() + connection.getCursorY() * stream.getHeight();
            shift = Math.max(0, Math.min(covered, cursorY + dp(96) - (overlay.getHeight() - covered)));
        }
        stream.animate().cancel();
        if (animate) stream.animate().translationY(-shift).setDuration(200).start();
        else stream.setTranslationY(-shift);
    }
    private void wakeFloating() {
        handler.removeCallbacks(dim);
        floating.animate().cancel();
        floating.setAlpha(1);
        handler.postDelayed(dim, 3000);
    }
    private void floatingFade() {
        if (floating.getVisibility() == View.VISIBLE) floating.animate().alpha(0.35f).setDuration(300).start();
    }
    private int minX() { return safeLeft + dp(8); }
    private int maxX() { return Math.max(minX(), overlay.getWidth() - safeRight - dp(56)); }
    private int minY() { return safeTop + dp(8); }
    private int maxY() { return Math.max(minY(), overlay.getHeight() - safeBottom - dp(56)); }
    private void placeFloating(boolean animate) {
        float x = rightEdge ? maxX() : minX();
        float y = minY() + Math.max(0, Math.min(1, yFraction)) * (maxY() - minY());
        if (snapAnimator != null) snapAnimator.cancel();
        if (animate) {
            float startX = floating.getX(), startY = floating.getY();
            snapAnimator = ValueAnimator.ofFloat(0, 1);
            snapAnimator.setDuration(150);
            snapAnimator.addUpdateListener(animation -> {
                float fraction = (float)animation.getAnimatedValue();
                floating.setX(startX + (x - startX) * fraction);
                floating.setY(startY + (y - startY) * fraction);
            });
            snapAnimator.start();
        } else { floating.setX(x); floating.setY(y); }
        if (Build.VERSION.SDK_INT >= 29) floating.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, dp(48), dp(48))));
    }
    @SuppressLint("ClickableViewAccessibility")
    private void installDrag() {
        final float[] start = new float[4];
        final boolean[] dragging = { false };
        int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        floating.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (snapAnimator != null) snapAnimator.cancel();
                    wakeFloating();
                    dragging[0] = false;
                    start[0] = event.getRawX(); start[1] = event.getRawY();
                    start[2] = v.getX(); start[3] = v.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    wakeFloating();
                    float dx = event.getRawX() - start[0], dy = event.getRawY() - start[1];
                    if (Math.hypot(dx, dy) > slop) dragging[0] = true;
                    if (dragging[0]) {
                        v.setX(Math.max(minX(), Math.min(maxX(), start[2] + dx)));
                        v.setY(Math.max(minY(), Math.min(maxY(), start[3] + dy)));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging[0]) {
                        rightEdge = v.getX() + dp(24) >= overlay.getWidth() / 2f;
                        yFraction = maxY() == minY() ? 0.5f : (v.getY() - minY()) / (maxY() - minY());
                        positionPrefs.edit().putBoolean("rightEdge", rightEdge).putFloat("yFraction", yFraction).apply();
                        placeFloating(true);
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) v.performClick();
                    wakeFloating();
                    return true;
                default: return true;
            }
        });
    }
}
