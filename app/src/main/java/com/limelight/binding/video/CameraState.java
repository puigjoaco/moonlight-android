package com.limelight.binding.video;

/** Camera is opt-in for each new stream; only rotation recreation restores the requested state. */
public final class CameraState {
    private boolean requested;
    public CameraState(boolean restoredRequested) { requested = restoredRequested; }
    public boolean requested() { return requested; }
    public void toggle() { requested = !requested; }
    public void off() { requested = false; }
    public boolean shouldCapture(boolean connected, boolean supported, boolean foreground,
                                 boolean stopped, boolean reconnecting, boolean pip) {
        return requested && connected && supported && foreground && !stopped && !reconnecting && !pip;
    }
    /** Clockwise sensor-to-display transform, without front-camera mirroring. */
    public static int rotation(int sensor, int display, boolean front) {
        return (sensor + (front ? display : -display) + 360) % 360;
    }
}
