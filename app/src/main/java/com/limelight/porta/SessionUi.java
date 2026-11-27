package com.limelight.porta;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.loadingindicator.LoadingIndicator;
import com.limelight.R;

/** Owns presentation only; Game continues to own connection and input lifecycles. */
public final class SessionUi {
    public static final class ReconnectPresentation {
        public Bitmap frame;
        public final long startedAt = SystemClock.uptimeMillis();
    }

    private final Activity activity;
    private final FrameLayout layer;
    private final SurfaceView surface;
    private final String name;
    private final Runnable cancel, retry, pairAgain;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable delayedCard;
    private AlertDialog dialog;
    private ImageView frozenFrame;
    private ReconnectPresentation reconnect;
    private boolean closed;
    private boolean showing;

    public boolean isShowing() { return showing; }

    public SessionUi(Activity activity, FrameLayout layer, SurfaceView surface, String name,
                     Runnable cancel, Runnable retry, Runnable pairAgain) {
        this.activity = activity;
        this.layer = layer;
        this.surface = surface;
        this.name = name == null || name.isEmpty() ? activity.getString(R.string.porta_computer) : name;
        this.cancel = cancel;
        this.retry = retry;
        this.pairAgain = pairAgain;
    }

    private void reset() {
        showing = true;
        handler.removeCallbacksAndMessages(null);
        layer.animate().cancel();
        layer.removeAllViews();
        layer.setAlpha(1);
        layer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        layer.setClickable(true);
        layer.setFocusableInTouchMode(true);
        layer.requestFocus();
        ((android.view.inputmethod.InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(layer.getWindowToken(), 0);
        layer.setVisibility(View.VISIBLE);
        layer.bringToFront();
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        frozenFrame = null;
    }

    public void connecting() { portal(null); }

    public void failed(SessionFailure.Cause cause) { portal(cause); }

    private void portal(SessionFailure.Cause cause) {
        reset();
        reconnect = null;
        layer.setBackgroundColor(PortaViews.color(activity, R.color.porta_background));
        LinearLayout screen = PortaViews.column(activity);
        screen.setPadding(PortaViews.dp(activity, 16), PortaViews.dp(activity, 16),
                PortaViews.dp(activity, 16), PortaViews.dp(activity, 32));
        layer.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout center = PortaViews.column(activity);
        center.setGravity(Gravity.CENTER);
        scroll.addView(center, new ScrollView.LayoutParams(-1, -1));
        boolean compact = activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        center.addView(new SessionPortalView(activity, cause == null), PortaViews.size(activity, -1, compact ? 220 : 330));
        String message = name;
        if (cause != null) {
            switch (cause) {
                case SERVICE_UNAVAILABLE: message = activity.getString(R.string.porta_service_unavailable, name); break;
                case PAIRING_REVOKED: message = activity.getString(R.string.porta_pair_revoked, name); break;
                case PORTS_BLOCKED: message = activity.getString(R.string.porta_ports_blocked); break;
                default: message = activity.getString(R.string.porta_no_response, name); break;
            }
        }
        TextView title = PortaViews.text(activity, message, cause == null ? 28 : 24, true);
        title.setGravity(Gravity.CENTER);
        title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams titleParams = PortaViews.size(activity, -1, -2);
        titleParams.topMargin = PortaViews.dp(activity, compact ? -40 : -60);
        titleParams.leftMargin = titleParams.rightMargin = PortaViews.dp(activity, 16);
        center.addView(title, titleParams);
        if (cause == null) {
            TextView status = PortaViews.text(activity, activity.getString(R.string.porta_connecting), 14, false);
            status.setTextColor(PortaViews.color(activity, R.color.porta_on_surface_variant));
            status.setGravity(Gravity.CENTER);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            PortaViews.space(center, 4);
            center.addView(status);
        }
        LinearLayout footer = PortaViews.column(activity);
        footer.setGravity(Gravity.CENTER);
        screen.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        if (cause != null) {
            boolean pairing = cause == SessionFailure.Cause.PAIRING_REVOKED;
            MaterialButton primary = PortaViews.button(activity, pairing ? R.string.porta_pair_again : R.string.porta_retry, true);
            primary.setOnClickListener(v -> { primary.setEnabled(false); (pairing ? pairAgain : retry).run(); });
            footer.addView(primary, PortaViews.size(activity, -1, -2));
            primary.setMinHeight(PortaViews.dp(activity, 56));
            PortaViews.space(footer, 8);
        }
        MaterialButton back = PortaViews.button(activity, cause == null ? R.string.porta_cancel : R.string.porta_back, false);
        back.setOnClickListener(v -> cancel.run());
        footer.addView(back);
        fadeIn(layer, 150);
    }

    public ReconnectPresentation reconnecting(ReconnectPresentation retained) {
        reset();
        reconnect = retained == null ? new ReconnectPresentation() : retained;
        frozenBackground(reconnect.frame);
        if (retained == null) captureFrame(reconnect);
        delayedCard = () -> {
            if (closed || activity.isFinishing()) return;
            LinearLayout card = PortaViews.column(activity);
            card.setGravity(Gravity.CENTER);
            card.setPadding(PortaViews.dp(activity, 24), PortaViews.dp(activity, 24),
                    PortaViews.dp(activity, 24), PortaViews.dp(activity, 16));
            card.setBackground(PortaViews.background(activity, R.color.porta_surface_container_high, 28));
            LoadingIndicator indicator = PortaViews.loading(activity, 48);
            indicator.setIndicatorSize(PortaViews.dp(activity, 32));
            indicator.setContainerColor(PortaViews.color(activity, R.color.porta_primary_container));
            indicator.setContentDescription(activity.getString(R.string.porta_reconnecting));
            card.addView(indicator, PortaViews.size(activity, 48, 48));
            PortaViews.space(card, 12);
            TextView status = PortaViews.text(activity, activity.getString(R.string.porta_reconnecting), 16, true);
            status.setGravity(Gravity.CENTER);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            card.addView(status, PortaViews.size(activity, -1, -2));
            PortaViews.space(card, 16);
            MaterialButton button = PortaViews.button(activity, R.string.porta_cancel, false);
            button.setOnClickListener(v -> cancel.run());
            card.addView(button);
            // Scroll when a short landscape window or 200% text leaves too little height.
            ScrollView scroll = new ScrollView(activity);
            scroll.addView(card);
            int width = Math.min(PortaViews.dp(activity, 240), Math.max(1, layer.getWidth() - PortaViews.dp(activity, 32)));
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, -2, Gravity.CENTER);
            params.topMargin = params.bottomMargin = PortaViews.dp(activity, 16);
            layer.addView(scroll, params);
            fadeIn(scroll, 250);
        };
        handler.postDelayed(delayedCard, Math.max(0, 300 - (SystemClock.uptimeMillis() - reconnect.startedAt)));
        return reconnect;
    }

    private void frozenBackground(Bitmap bitmap) {
        frozenFrame = new ImageView(activity);
        frozenFrame.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frozenFrame.setBackgroundColor(bitmap == null ? android.graphics.Color.TRANSPARENT : android.graphics.Color.BLACK);
        frozenFrame.setImageBitmap(bitmap);
        if (Build.VERSION.SDK_INT >= 31)
            frozenFrame.setRenderEffect(RenderEffect.createBlurEffect(PortaViews.dp(activity, 7),
                    PortaViews.dp(activity, 7), Shader.TileMode.CLAMP));
        layer.addView(frozenFrame, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(activity);
        float dim = activity.getResources().getBoolean(R.bool.porta_is_light) ? .32f : .42f;
        scrim.setBackgroundColor(Math.round(255 * dim) << 24);
        layer.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
    }

    private void captureFrame(ReconnectPresentation presentation) {
        if (Build.VERSION.SDK_INT < 24 || surface.getWidth() == 0 || surface.getHeight() == 0 ||
                !surface.getHolder().getSurface().isValid()) return;
        // Limit transient memory while preserving the aspect ratio on high resolution phones.
        float scale = Math.min(1f, 1280f / Math.max(surface.getWidth(), surface.getHeight()));
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int)(surface.getWidth() * scale)),
                Math.max(1, (int)(surface.getHeight() * scale)), Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(surface, bitmap, result -> {
                if (result == PixelCopy.SUCCESS) {
                    presentation.frame = bitmap;
                    if (!closed && frozenFrame != null) frozenFrame.setImageBitmap(bitmap);
                } else bitmap.recycle();
            }, handler);
        } catch (IllegalArgumentException e) { bitmap.recycle(); }
    }

    public void lost() {
        reset();
        ReconnectPresentation last = reconnect == null ? new ReconnectPresentation() : reconnect;
        frozenBackground(last.frame);
        if (last.frame == null) captureFrame(last);
        ImageView icon = PortaViews.image(activity, R.drawable.ic_porta_link_off, 24, 24);
        icon.setImageTintList(ColorStateList.valueOf(PortaViews.color(activity, R.color.porta_error)));
        LinearLayout content = PortaViews.column(activity);
        content.setGravity(Gravity.CENTER);
        content.setPadding(PortaViews.dp(activity, 24), PortaViews.dp(activity, 24),
                PortaViews.dp(activity, 24), PortaViews.dp(activity, 8));
        content.addView(icon);
        PortaViews.space(content, 16);
        TextView title = PortaViews.text(activity, activity.getString(R.string.porta_connection_lost, name), 24, true);
        title.setGravity(Gravity.CENTER);
        content.addView(title, PortaViews.size(activity, -1, -2));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        dialog = new MaterialAlertDialogBuilder(activity)
                .setBackground(PortaViews.background(activity, R.color.porta_surface_container_high, 28))
                .setView(scroll)
                .setNegativeButton(R.string.porta_exit, (d, w) -> cancel.run())
                .setPositiveButton(R.string.porta_retry, (d, w) -> retry.run())
                .setCancelable(false).create();
        // The overlay already supplies the specified scrim.
        dialog.setOnShowListener(d -> {
            if (Build.VERSION.SDK_INT >= 22)
                dialog.getWindow().setElevation(activity.getResources().getBoolean(R.bool.porta_is_light)
                        ? PortaViews.dp(activity, 2) : 0);
            dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.widget.Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            button.setTextColor(PortaViews.color(activity, R.color.porta_on_primary));
            button.setBackgroundTintList(ColorStateList.valueOf(PortaViews.color(activity, R.color.porta_primary)));
            if (button instanceof MaterialButton) ((MaterialButton) button).setCornerRadius(PortaViews.dp(activity, 100));
            button.setPadding(PortaViews.dp(activity, 20), 0, PortaViews.dp(activity, 20), 0);
        });
        dialog.show();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    public void unsupportedDecoder() {
        hide();
        dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.porta_connect_failed).setMessage(R.string.porta_decoder_unsupported)
                .setPositiveButton(R.string.porta_back, (d, w) -> cancel.run())
                .setCancelable(false).show();
    }

    public void hide() {
        showing = false;
        handler.removeCallbacksAndMessages(null);
        layer.animate().cancel();
        layer.animate().alpha(0).setDuration(150).withEndAction(() -> {
            layer.setVisibility(View.GONE); layer.removeAllViews();
        }).start();
        reconnect = null;
        if (dialog != null) { dialog.dismiss(); dialog = null; }
    }

    private static void fadeIn(View view, long duration) {
        view.setAlpha(0);
        view.animate().alpha(1).setDuration(duration).start();
    }

    public void close() {
        closed = true;
        showing = false;
        handler.removeCallbacksAndMessages(null);
        layer.animate().cancel();
        layer.removeAllViews();
        layer.setVisibility(View.GONE);
        if (dialog != null) { dialog.dismiss(); dialog = null; }
    }
}
