package com.limelight.binding.input.touch;

import java.util.ArrayDeque;

/** Touch velocity and frame-rate-independent inertia, in view pixels and milliseconds. */
public final class TrackpadMomentum {
    private static final long SAMPLE_WINDOW_MS = 70;
    private static final long RELEASE_STALE_MS = 50;
    private static final long MIN_SAMPLE_SPAN_MS = 8;
    private static final double START_SPEED = 300; // pixels/second
    private static final double STOP_SPEED = 35;
    private static final double FRICTION_MS = 320;
    private static final long MAX_DURATION_MS = 1000;

    private static final class Sample {
        final double x, y;
        final long time;

        Sample(double x, double y, long time) {
            this.x = x;
            this.y = y;
            this.time = time;
        }
    }

    public static final class Delta {
        public final double x, y;

        Delta(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private double velocityX, velocityY;
    private long elapsedMs;
    private long lastMotionTime;
    private boolean active;

    public void cancel() {
        active = false;
        samples.clear();
        velocityX = velocityY = 0;
        elapsedMs = 0;
    }

    public void addSample(double x, double y, long time) {
        Sample last = samples.peekLast();
        if (last != null && time < last.time) {
            return;
        }
        if (last == null || x != last.x || y != last.y) {
            lastMotionTime = time;
        }
        if (last != null && time == last.time) {
            samples.removeLast();
        }
        samples.addLast(new Sample(x, y, time));
        while (!samples.isEmpty() && time - samples.peekFirst().time > SAMPLE_WINDOW_MS) {
            samples.removeFirst();
        }
    }

    public boolean start(long releaseTime) {
        active = false;
        Sample first = samples.peekFirst();
        Sample last = samples.peekLast();
        if (first == null || last.time - first.time < MIN_SAMPLE_SPAN_MS ||
                releaseTime < last.time || releaseTime - lastMotionTime >= RELEASE_STALE_MS) {
            return false;
        }
        // Include the short lift delay so a slowing finger does not get an extra kick.
        double seconds = (releaseTime - first.time) / 1000.0;
        velocityX = (last.x - first.x) / seconds;
        velocityY = (last.y - first.y) / seconds;
        elapsedMs = 0;
        active = Math.hypot(velocityX, velocityY) >= START_SPEED;
        samples.clear();
        return active;
    }

    public boolean isActive() {
        return active;
    }

    public Delta step(long frameMs) {
        if (!active || frameMs <= 0) {
            return new Delta(0, 0);
        }
        long dt = Math.min(frameMs, MAX_DURATION_MS - elapsedMs);
        double decay = Math.exp(-dt / FRICTION_MS);
        // Integrate the exponential exactly; distance stays the same at 60 and 120 Hz.
        double integral = FRICTION_MS / 1000.0 * (1 - decay);
        Delta delta = new Delta(velocityX * integral, velocityY * integral);
        velocityX *= decay;
        velocityY *= decay;
        elapsedMs += dt;
        active = elapsedMs < MAX_DURATION_MS && Math.hypot(velocityX, velocityY) >= STOP_SPEED;
        return delta;
    }
}
