package com.limelight.binding.input.keyboard;

/** Mirrors this checkout's moonlight-common-c InputStream.c, including its initial centre. */
public final class CursorPosition {
    private float x = 0.5f, y = 0.5f;
    public synchronized float y() { return y; }
    public synchronized void absolute(int x, int y, int width, int height) {
        if (width <= 1 || height <= 1) return;
        this.x = Math.max(0, Math.min(x, width - 1)) / (float)(width - 1);
        this.y = Math.max(0, Math.min(y, height - 1)) / (float)(height - 1);
    }
    public synchronized void relative(int dx, int dy, int width, int height) {
        absolute((short)(x * width) + dx, (short)(y * height) + dy, width, height);
    }
}
