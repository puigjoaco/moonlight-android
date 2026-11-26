package com.limelight.binding.audio;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Capture/encode/transport wiring, independent of Android hardware for focused JVM tests. */
public final class MicrophonePump {
    public static final int SAMPLE_RATE = 48000, FRAME_SAMPLES = 960, MAX_PACKET = 1024;
    public interface Capture { int read(short[] pcm, int offset, int count); }
    public interface Encoder { List<Packet> encode(short[] pcm, long presentationUs); }
    public interface Sink { void send(byte[] opus, long timestampSamples); }
    public interface Clock { long microseconds(); }
    public static final class Packet {
        public final byte[] data;
        public final long presentationUs;
        public Packet(byte[] data, long presentationUs) { this.data = data; this.presentationUs = presentationUs; }
    }
    private MicrophonePump() { }

    /** Assemble short reads, encode one 20 ms frame, and discard delayed codec output. */
    public static void run(Capture capture, Encoder encoder, Sink sink, Clock clock, BooleanSupplier running) {
        short[] frame = new short[FRAME_SAMPLES];
        int filled = 0;
        long previousPts = 0;
        while (running.getAsBoolean()) {
            int count = capture.read(frame, filled, frame.length - filled);
            if (!running.getAsBoolean()) break;
            if (count <= 0 || count > frame.length - filled) throw new IllegalStateException("Microphone capture stopped");
            filled += count;
            if (filled != frame.length) continue;
            long pts = Math.max(previousPts + 20000, clock.microseconds() - 20000);
            previousPts = pts;
            for (Packet packet : encoder.encode(frame, pts)) {
                if (!running.getAsBoolean()) break;
                long age = clock.microseconds() - packet.presentationUs;
                if (age > 120000) continue; // Never retransmit queued old speech.
                if (packet.data.length == 0 || packet.data.length > MAX_PACKET) {
                    throw new IllegalStateException("Unsupported Opus encoder packet size");
                }
                sink.send(packet.data, packet.presentationUs * 48 / 1000);
            }
            filled = 0;
        }
    }
}
