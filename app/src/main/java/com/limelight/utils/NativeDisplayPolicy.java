package com.limelight.utils;

/** Display policy shared by preferences, native fullscreen launch and rotation reconnects. */
public final class NativeDisplayPolicy {
    private NativeDisplayPolicy() {}

    /** Largest panel mode, normalized to landscape for preferences and Game alike. */
    public static android.graphics.Point fullscreenPixels(android.view.Display display) {
        int width, height;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.view.Display.Mode active = display.getMode();
            width = active.getPhysicalWidth();
            height = active.getPhysicalHeight();
            for (android.view.Display.Mode mode : display.getSupportedModes()) {
                if ((long) mode.getPhysicalWidth() * mode.getPhysicalHeight() > (long) width * height) {
                    width = mode.getPhysicalWidth();
                    height = mode.getPhysicalHeight();
                }
            }
        } else {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            display.getRealMetrics(metrics);
            width = metrics.widthPixels;
            height = metrics.heightPixels;
        }
        return new android.graphics.Point(Math.max(width, height), Math.min(width, height));
    }

    /** Desktop density uses a 300 dpi baseline, rounded to 25% steps. */
    public static int scalePercent(int densityDpi) {
        return Math.max(100, Math.min(400, Math.round(densityDpi / 75.0f) * 25));
    }

    /** A saved explicit scale overrides Auto only when it matches a supported choice. */
    public static int scalePercent(int densityDpi, String preference) {
        if (preference != null && !preference.equals("auto")) {
            try {
                int percent = Integer.parseInt(preference);
                if (percent >= 100 && percent <= 400 && percent % 25 == 0) {
                    return percent;
                }
            } catch (NumberFormatException ignored) {
                // Unknown or stale restored preferences use Auto rather than failing a launch.
            }
        }
        return scalePercent(densityDpi);
    }

    public static int width(int physicalWidth, int physicalHeight, boolean portrait) {
        return portrait ? Math.min(physicalWidth, physicalHeight) : Math.max(physicalWidth, physicalHeight);
    }

    public static int height(int physicalWidth, int physicalHeight, boolean portrait) {
        return width(physicalWidth, physicalHeight, !portrait);
    }

    /** Same-axis changes (including 180-degree rotations) do not restart a stream. */
    public static boolean needsReconnect(int streamWidth, int streamHeight, boolean portrait) {
        return streamWidth != streamHeight && (streamHeight > streamWidth) != portrait;
    }
}
