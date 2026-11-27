package com.limelight.porta;

import static com.limelight.porta.PortaViews.*;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;

public class ConnectSheet extends BottomSheetDialogFragment {
    private boolean camera;
    private TextView nameView, routeView;
    private View statusView;
    private MaterialButton connectButton;
    private ComputerDetails.State displayedState;
    private boolean waitingForDiscovery;
    private String latency;

    public static void show(PcView activity, String uuid) {
        if (activity.getSupportFragmentManager().findFragmentByTag("connect") != null) return;
        ConnectSheet sheet = new ConnectSheet();
        Bundle args = new Bundle();
        args.putString("uuid", uuid);
        sheet.setArguments(args);
        sheet.show(activity.getSupportFragmentManager(), "connect");
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        PcView home = (PcView) requireActivity();
        ComputerDetails c = home.getComputer(requireArguments().getString("uuid"));
        LinearLayout sheet = column(home);
        sheet.setPadding(dp(home, 20), dp(home, 12), dp(home, 20), dp(home, 24));
        sheet.setBackground(background(home, R.color.porta_surface_container_low, 28));
        View handle = new View(home);
        handle.setBackground(background(home, R.color.porta_outline, 4));
        LinearLayout.LayoutParams hl = size(home, 32, 4);
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(handle, hl);
        space(sheet, 24);
        if (c == null) {
            dismiss();
            return sheet;
        }
        displayedState = c.state;
        waitingForDiscovery = waitingForDiscovery(c);
        LinearLayout header = row(home);
        header.addView(computerIcon(home, 56));
        LinearLayout labels = column(home);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.leftMargin = dp(home, 16);
        header.addView(labels, lp);
        nameView = text(home, home.displayName(c), 24, true);
        labels.addView(nameView);
        LinearLayout status = row(home);
        ComputerPresentation.Status hostStatus = ComputerPresentation.status(c);
        View dot = new View(home);
        android.graphics.drawable.GradientDrawable statusDot =
                background(
                        home,
                        hostStatus == ComputerPresentation.Status.OFFLINE
                                ? R.color.porta_outline
                                : R.color.porta_online,
                        8);
        if (hostStatus == ComputerPresentation.Status.OFFLINE) {
            statusDot.setColor(android.graphics.Color.TRANSPARENT);
            statusDot.setStroke(dp(home, 1.5f), color(home, R.color.porta_outline));
        }
        statusView = dot;
        dot.setBackground(statusDot);
        status.addView(dot, size(home, 8, 8));
        String routeName =
                home.getString(
                        hostStatus == ComputerPresentation.Status.OFFLINE
                                ? R.string.porta_offline
                                : routeLabel(c));
        TextView route =
                text(home, routeName + " · " + home.getString(R.string.porta_measuring), 14, false);
        routeView = route;
        route.setTextColor(color(home, R.color.porta_on_surface_variant));
        LinearLayout.LayoutParams routeLp = size(home, -2, -2);
        routeLp.leftMargin = dp(home, 6);
        status.addView(route, routeLp);
        labels.addView(status);
        if (hostStatus != ComputerPresentation.Status.OFFLINE)
            home.measureLatency(
                    c,
                    value -> {
                        if (isAdded() && getView() != null) {
                            latency = value;
                            refreshConnectionState();
                        }
                    });
        else route.setText(routeName);
        MaterialButton more = iconButton(home, R.drawable.ic_porta_more, R.string.porta_more);
        more.setOnClickListener(v -> home.showComputerMenu(v, c));
        header.addView(more, size(home, 48, 48));
        sheet.addView(header);
        space(sheet, 24);
        LinearLayout tiles = row(home);
        boolean largeText = home.getResources().getConfiguration().fontScale > 1.3f;
        if (largeText) tiles.setOrientation(LinearLayout.VERTICAL);
        MaterialButton mic = button(home, R.string.porta_mic, true);
        MaterialButton cam = button(home, R.string.porta_camera, true);
        for (MaterialButton tile : new MaterialButton[] {mic, cam}) {
            tile.setCornerRadius(dp(home, 16));
            tile.setMinHeight(dp(home, 72));
            tile.setInsetTop(0);
            tile.setInsetBottom(0);
            tile.setIconPadding(dp(home, 12));
            tile.setIconSize(dp(home, 40));
            tile.setPadding(dp(home, 14), dp(home, 8), dp(home, 14), dp(home, 8));
            tile.setIconGravity(MaterialButton.ICON_GRAVITY_START);
            tile.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            tile.setCheckable(true);
            LinearLayout.LayoutParams tl =
                    new LinearLayout.LayoutParams(largeText ? -1 : 0, -2, largeText ? 0 : 1);
            int gap = tile == mic ? dp(home, 8) : 0;
            tl.setMargins(0, 0, largeText ? 0 : gap, largeText ? gap : 0);
            tiles.addView(tile, tl);
            ViewCompat.setAccessibilityDelegate(
                    tile,
                    new androidx.core.view.AccessibilityDelegateCompat() {
                        @Override
                        public void onInitializeAccessibilityNodeInfo(
                                View host, AccessibilityNodeInfoCompat info) {
                            super.onInitializeAccessibilityNodeInfo(host, info);
                            info.setClassName("android.widget.Switch");
                        }
                    });
        }
        updateTile(mic, home.microphoneEnabled(c.uuid), false);
        updateTile(cam, false, true);
        mic.setOnClickListener(v -> home.toggleMicrophone(c.uuid, mic));
        cam.setOnClickListener(
                v -> {
                    camera = !camera;
                    updateTile(cam, camera, true);
                });
        sheet.addView(tiles);
        space(sheet, 20);
        MaterialButton connect = button(home, R.string.porta_connect, true);
        connectButton = connect;
        connect.setInsetTop(0);
        connect.setInsetBottom(0);
        if (c.state == ComputerDetails.State.OFFLINE) {
            connect.setText(R.string.porta_wake);
            connect.setEnabled(ComputerPresentation.hasMac(c));
        } else connect.setEnabled(!waitingForDiscovery);
        connect.setOnClickListener(v -> home.connectDesktop(c, () -> camera, connect, this::dismiss));
        sheet.addView(connect, size(home, -1, 56));
        NestedScrollView scroll = new NestedScrollView(home);
        scroll.setFillViewport(true);
        scroll.addView(sheet);
        return scroll;
    }

    private static boolean waitingForDiscovery(ComputerDetails c) {
        return c.state == ComputerDetails.State.UNKNOWN
                || (c.state == ComputerDetails.State.ONLINE
                        && (c.pairState == null || c.activeAddress == null));
    }

    /** Discovery continues while the sheet is open (including after Wake-on-LAN). */
    public void refreshConnectionState() {
        if (!isAdded() || connectButton == null || routeView == null) return;
        PcView home = (PcView) requireActivity();
        ComputerDetails c = home.getComputer(requireArguments().getString("uuid"));
        if (c == null) {
            dismiss();
            return;
        }
        boolean offline = c.state == ComputerDetails.State.OFFLINE;
        boolean waiting = waitingForDiscovery(c);
        if (c.state != displayedState || waiting != waitingForDiscovery) {
            boolean wasOffline = displayedState == ComputerDetails.State.OFFLINE;
            boolean wasWaiting = waitingForDiscovery;
            displayedState = c.state;
            waitingForDiscovery = waiting;
            connectButton.setText(offline ? R.string.porta_wake : R.string.porta_connect);
            // A pending Desktop lookup keeps the button disabled until its callback returns.
            connectButton.setEnabled(
                    !waiting
                            && (offline
                                    ? ComputerPresentation.hasMac(c)
                                            && (wasWaiting || connectButton.isEnabled())
                                    : wasOffline || wasWaiting || connectButton.isEnabled()));
            android.graphics.drawable.GradientDrawable shape =
                    background(home, offline ? R.color.porta_outline : R.color.porta_online, 8);
            if (offline) {
                shape.setColor(android.graphics.Color.TRANSPARENT);
                shape.setStroke(dp(home, 1.5f), color(home, R.color.porta_outline));
            }
            statusView.setBackground(shape);
            if (wasOffline && !offline)
                home.measureLatency(
                        c,
                        value -> {
                            if (isAdded()) {
                                latency = value;
                                refreshConnectionState();
                            }
                        });
        }
        routeView.setText(
                offline
                        ? home.getString(R.string.porta_offline)
                        : home.getString(routeLabel(c))
                                + " · "
                                + (latency == null
                                        ? home.getString(R.string.porta_measuring)
                                        : latency));
        refreshName();
    }

    public void refreshName() {
        if (!isAdded() || nameView == null) return;
        PcView home = (PcView) requireActivity();
        ComputerDetails computer = home.getComputer(requireArguments().getString("uuid"));
        if (computer != null) nameView.setText(home.displayName(computer));
    }

    public static void updateTile(MaterialButton tile, boolean on, boolean camera) {
        android.content.Context c = tile.getContext();
        Boolean previous = (Boolean) tile.getTag();
        int previousColor =
                tile.getBackgroundTintList() == null
                        ? color(c, R.color.porta_surface_container_high)
                        : tile.getBackgroundTintList().getDefaultColor();
        tile.setTag(on);
        tile.setChecked(on);
        int iconId =
                camera
                        ? (on ? R.drawable.ic_porta_camera : R.drawable.ic_porta_camera_off)
                        : (on ? R.drawable.ic_porta_mic : R.drawable.ic_porta_mic_off);
        android.graphics.drawable.Drawable icon =
                androidx.appcompat.content.res.AppCompatResources.getDrawable(c, iconId).mutate();
        // Icon vectors carry their own color; MaterialButton clears inherited layer tints.
        android.graphics.drawable.GradientDrawable iconBackground =
                background(
                        c,
                        on ? R.color.porta_primary : R.color.porta_surface_container_highest,
                        12);
        android.graphics.drawable.LayerDrawable layers =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[] {
                            iconBackground,
                            new android.graphics.drawable.InsetDrawable(icon, dp(c, 9))
                        });
        tile.setIconTint(null);
        tile.setIcon(layers);
        tile.setBackgroundTintList(
                ColorStateList.valueOf(
                        color(
                                c,
                                on
                                        ? R.color.porta_primary_container
                                        : R.color.porta_surface_container_high)));
        tile.setTextColor(
                color(c, on ? R.color.porta_on_primary_container : R.color.porta_on_surface));
        if (previous != null && previous != on) {
            boolean reduced =
                    android.provider.Settings.Global.getFloat(
                                    c.getContentResolver(),
                                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                                    1f)
                            == 0;
            int haptic =
                    android.os.Build.VERSION.SDK_INT >= 34
                            ? (on
                                    ? HapticFeedbackConstants.TOGGLE_ON
                                    : HapticFeedbackConstants.TOGGLE_OFF)
                            : HapticFeedbackConstants.CLOCK_TICK;
            tile.performHapticFeedback(haptic);
            if (!reduced) {
                int target =
                        color(
                                c,
                                on
                                        ? R.color.porta_primary_container
                                        : R.color.porta_surface_container_high);
                android.animation.ValueAnimator colors =
                        android.animation.ValueAnimator.ofArgb(previousColor, target);
                colors.setDuration(150);
                colors.addUpdateListener(
                        a ->
                                tile.setBackgroundTintList(
                                        ColorStateList.valueOf((Integer) a.getAnimatedValue())));
                colors.start();
                android.animation.ValueAnimator corners =
                        android.animation.ValueAnimator.ofFloat(
                                dp(c, on ? 12 : 20), dp(c, on ? 20 : 12));
                corners.setDuration(250);
                corners.addUpdateListener(
                        a -> iconBackground.setCornerRadius((Float) a.getAnimatedValue()));
                corners.start();
            } else iconBackground.setCornerRadius(dp(c, on ? 20 : 12));
        } else iconBackground.setCornerRadius(dp(c, on ? 20 : 12));
        ViewCompat.setStateDescription(
                tile, c.getString(on ? R.string.porta_active : R.string.porta_off));
    }
}
