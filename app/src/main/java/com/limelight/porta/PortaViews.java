package com.limelight.porta;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.button.MaterialButton;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;

public final class PortaViews {
    private PortaViews() {}

    public static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    public static int color(Context c, int id) {
        return androidx.core.content.ContextCompat.getColor(c, id);
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams size(Context c, int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : dp(c, w), h < 0 ? h : dp(c, h));
    }

    public static void space(LinearLayout l, int h) {
        l.addView(new View(l.getContext()), size(l.getContext(), 1, h));
    }

    @android.annotation.SuppressLint(
            "WrongConstant") // Layout and LineBreaker constants both use value 2; Layout supports
    // API 23.
    public static TextView text(Context c, CharSequence value, int sp, boolean medium) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color(c, R.color.porta_on_surface));
        Typeface font = ResourcesCompat.getFont(c, R.font.inter);
        t.setTypeface(
                Build.VERSION.SDK_INT >= 28
                        ? Typeface.create(font, medium ? (sp >= 24 ? 600 : 500) : 400, false)
                        : font);
        if (Build.VERSION.SDK_INT >= 23)
            t.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_BALANCED);
        return t;
    }

    public static GradientDrawable background(Context c, int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color(c, color));
        d.setCornerRadius(dp(c, radius));
        return d;
    }

    public static ImageView image(Context c, int icon, int w, int h) {
        ImageView i = new androidx.appcompat.widget.AppCompatImageView(c);
        i.setImageResource(icon);
        i.setLayoutParams(size(c, w, h));
        i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return i;
    }

    public static MaterialButton button(Context c, int label, boolean filled) {
        MaterialButton b =
                new MaterialButton(
                        c,
                        null,
                        filled
                                ? com.google.android.material.R.attr.materialButtonStyle
                                : com.google.android.material.R.attr.materialButtonStyle);
        if (!filled) {
            b.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
            b.setTextColor(color(c, R.color.porta_primary));
            b.setStrokeWidth(0);
        }
        b.setText(label);
        b.setAllCaps(false);
        b.setMinHeight(dp(c, 48));
        b.setCornerRadius(dp(c, 100));
        return b;
    }

    public static MaterialButton iconButton(Context c, int icon, int label) {
        MaterialButton b = button(c, label, false);
        b.setText("");
        b.setContentDescription(c.getString(label));
        b.setIconResource(icon);
        b.setIconTint(ColorStateList.valueOf(color(c, R.color.porta_on_surface_variant)));
        b.setIconPadding(0);
        b.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        return b;
    }

    public static com.google.android.material.loadingindicator.LoadingIndicator loading(
            Context c, int size) {
        com.google.android.material.loadingindicator.LoadingIndicator indicator =
                new com.google.android.material.loadingindicator.LoadingIndicator(c);
        indicator.setIndicatorSize(dp(c, size));
        indicator.setContainerWidth(dp(c, size));
        indicator.setContainerHeight(dp(c, size));
        indicator.setIndicatorColor(color(c, R.color.porta_primary));
        indicator.setContainerColor(android.graphics.Color.TRANSPARENT);
        return indicator;
    }

    public static android.widget.FrameLayout brand(
            Context c, int drawable, int width, int height, int glowWidth, int glowHeight) {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(c);
        frame.setLayoutParams(size(c, glowWidth, glowHeight));
        frame.setClipChildren(false);
        View glow = new View(c);
        boolean light = c.getResources().getBoolean(R.bool.porta_is_light);
        GradientDrawable halo =
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {0x6BFF9E66, 0x1FFF9E66, 0x00FF9E66});
        halo.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        halo.setGradientRadius(dp(c, glowWidth / 2f));
        glow.setBackground(halo);
        glow.setAlpha(light ? 0.6f : 1f);
        frame.addView(
                glow,
                new android.widget.FrameLayout.LayoutParams(
                        dp(c, glowWidth), dp(c, glowWidth), Gravity.CENTER));
        ImageView icon = image(c, drawable, width, height);
        frame.addView(
                icon,
                new android.widget.FrameLayout.LayoutParams(
                        dp(c, width), dp(c, height), Gravity.CENTER));
        frame.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    android.animation.ObjectAnimator pulse;

                    @Override
                    public void onViewAttachedToWindow(View view) {
                        if (android.provider.Settings.Global.getFloat(
                                        c.getContentResolver(),
                                        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                                        1f)
                                == 0) return;
                        pulse =
                                android.animation.ObjectAnimator.ofFloat(
                                        glow, "alpha", light ? 0.48f : 0.8f, light ? 0.6f : 1f);
                        pulse.setDuration(2400);
                        pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                        pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                        pulse.start();
                    }

                    @Override
                    public void onViewDetachedFromWindow(View view) {
                        if (pulse != null) pulse.cancel();
                    }
                });
        return frame;
    }

    public static ImageView computerIcon(Context c, int size) {
        ImageView i = image(c, R.drawable.ic_porta_desktop, size, size);
        i.setImageTintList(ColorStateList.valueOf(color(c, R.color.porta_on_tertiary_container)));
        int padding = dp(c, size == 44 ? 10 : 14);
        i.setPadding(padding, padding, padding, padding);
        i.setBackground(background(c, R.color.porta_tertiary_container, size == 44 ? 12 : 16));
        return i;
    }

    public static int routeLabel(ComputerDetails c) {
        switch (ComputerPresentation.route(c)) {
            case LOCAL:
                return R.string.porta_local;
            case TAILSCALE:
                return R.string.porta_tailscale;
            default:
                return R.string.porta_remote;
        }
    }

    public static String lastUsed(Context c, long time) {
        if (time <= 0) return c.getString(R.string.porta_never_used);
        long minutes = Math.max(0, System.currentTimeMillis() - time) / 60000;
        if (minutes < 1) return c.getString(R.string.porta_just_now);
        if (minutes < 60) return c.getString(R.string.porta_minutes_ago, minutes);
        if (minutes < 24 * 60) return c.getString(R.string.porta_hours_ago, minutes / 60);
        if (minutes < 48 * 60) return c.getString(R.string.porta_yesterday);
        return android.text.format.DateFormat.getMediumDateFormat(c)
                .format(new java.util.Date(time));
    }

    private static String lastUsedAccessibility(Context c, long time) {
        long minutes = Math.max(0, System.currentTimeMillis() - time) / 60000;
        if (minutes < 1) return c.getString(R.string.porta_used_just_now);
        if (minutes < 60)
            return c.getResources().getQuantityString(
                    R.plurals.porta_used_minutes_ago, (int) minutes, minutes);
        if (minutes < 24 * 60) {
            long hours = minutes / 60;
            return c.getResources().getQuantityString(
                    R.plurals.porta_used_hours_ago, (int) hours, hours);
        }
        return c.getString(R.string.porta_used_at, lastUsed(c, time));
    }

    public static String lastUsedDate(Context c, long time) {
        if (time <= 0) return c.getString(R.string.porta_never_used);
        String pattern =
                android.text.format.DateFormat.getBestDateTimePattern(
                        c.getResources().getConfiguration().locale, "d MMM");
        return android.text.format.DateFormat.format(pattern, time).toString();
    }

    public static String accessibilityDescription(Context c, ComputerDetails host, String name) {
        int state;
        switch (ComputerPresentation.status(host)) {
            case ONLINE:
                state = R.string.porta_online;
                break;
            case UNPAIRED:
                state = R.string.porta_unpaired;
                break;
            case OFFLINE:
                state = R.string.porta_offline;
                break;
            default:
                state = R.string.porta_refreshing;
        }
        return name
                + ", "
                + c.getString(state)
                + ", "
                + c.getString(routeLabel(host))
                + (host.lastUsed > 0 ? ", " + lastUsedAccessibility(c, host.lastUsed) : "");
    }

    public static String subtitle(Context c, ComputerDetails host, boolean firstRun) {
        switch (ComputerPresentation.status(host)) {
            case OFFLINE:
                return c.getString(R.string.porta_offline) + " · " + lastUsedDate(c, host.lastUsed);
            case UNPAIRED:
                return c.getString(firstRun ? routeLabel(host) : R.string.porta_unpaired);
            case REFRESHING:
                return c.getString(R.string.porta_refreshing);
            default:
                return c.getString(routeLabel(host)) + " · " + lastUsed(c, host.lastUsed);
        }
    }
}
