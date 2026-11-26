package com.limelight.utils;

/** Pure policy shared by native fullscreen launch and rotation reconnects. */
public final class NativeDisplayPolicy {
    private NativeDisplayPolicy() {}

    /** Desktop density uses a 240 dpi baseline, rounded to 25% steps. */
    public static int scalePercent(int densityDpi) {
        return Math.max(100, Math.min(400, Math.round(densityDpi / 60.0f) * 25));
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
