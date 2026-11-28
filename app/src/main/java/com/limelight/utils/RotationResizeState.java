package com.limelight.utils;

/** Main-thread-only request tracking. Each new target supersedes the entire previous request. */
public final class RotationResizeState {
    public static final long TIMEOUT_MS = 4000;
    private int sequence;
    private boolean pending;
    private int width, height;

    public boolean needsRequest(int currentWidth, int currentHeight, int targetWidth, int targetHeight) {
        return (pending ? width : currentWidth) != targetWidth ||
                (pending ? height : currentHeight) != targetHeight;
    }

    public int begin(int width, int height) {
        // v1 sequences must not wrap. This is unreachable in a human-length streaming session.
        if (sequence == -1) throw new IllegalStateException("Resize sequence exhausted");
        ++sequence;
        this.width = width;
        this.height = height;
        pending = true;
        return sequence;
    }

    public boolean isPending(int sequence) { return pending && this.sequence == sequence; }
    public boolean matchesSize(int width, int height) { return this.width == width && this.height == height; }
    public void clear() { pending = false; }
}
