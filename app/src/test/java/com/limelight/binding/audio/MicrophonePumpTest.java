package com.limelight.binding.audio;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class MicrophonePumpTest {
    @Test public void assemblesPartialCaptureIntoExactlyTwentyMsAndSendsCaptureTimestamp() {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger reads = new AtomicInteger();
        List<Long> timestamps = new ArrayList<>();
        MicrophonePump.run((pcm, offset, count) -> {
            int n = Math.min(240, count);
            for (int i = 0; i < n; i++) pcm[offset + i] = (short)(offset + i);
            reads.incrementAndGet(); return n;
        }, (pcm, pts) -> {
            assertEquals(960, pcm.length); assertEquals(959, pcm[959]); assertEquals(980000, pts);
            return List.of(new MicrophonePump.Packet(new byte[] {1, 2}, pts));
        }, (opus, timestamp) -> { assertArrayEquals(new byte[] {1, 2}, opus); timestamps.add(timestamp); running.set(false); },
                () -> 1000000, running::get);
        assertEquals(4, reads.get()); assertEquals(List.of(47040L), timestamps);
    }
    @Test public void dropsStaleEncoderOutputInsteadOfSendingBacklog() {
        AtomicBoolean running = new AtomicBoolean(true);
        List<byte[]> sent = new ArrayList<>();
        MicrophonePump.run((pcm, offset, count) -> count,
                (pcm, pts) -> List.of(new MicrophonePump.Packet(new byte[] {1}, pts - 200000),
                        new MicrophonePump.Packet(new byte[] {2}, pts)),
                (opus, timestamp) -> { sent.add(opus); running.set(false); }, () -> 1000000, running::get);
        assertEquals(1, sent.size()); assertArrayEquals(new byte[] {2}, sent.get(0));
    }
    @Test public void stopWhileReadingNeverEncodesOrSends() {
        AtomicBoolean running = new AtomicBoolean(true);
        MicrophonePump.run((pcm, offset, count) -> { running.set(false); return -3; },
                (pcm, pts) -> { fail("encoded after stop"); return List.of(); },
                (opus, timestamp) -> fail("sent after stop"), () -> 1000000, running::get);
    }
    @Test(expected = IllegalStateException.class) public void oversizedEncoderOutputFailsClearly() {
        MicrophonePump.run((pcm, offset, count) -> count,
                (pcm, pts) -> List.of(new MicrophonePump.Packet(new byte[1025], pts)),
                (opus, timestamp) -> fail("sent oversized packet"), () -> 1000000, () -> true);
    }
}
