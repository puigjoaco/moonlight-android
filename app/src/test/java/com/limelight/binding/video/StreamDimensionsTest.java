package com.limelight.binding.video;

import java.util.Arrays;
import java.util.Base64;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamDimensionsTest {
    // Generated with ffmpeg color=360x640 + libx264/libaom, and color=640x360 + libx265.
    static final byte[] H264 = Base64.getDecoder().decode("AAAAAWdCwBbaBcFHl8BEAAADAAQAAAMACDxYuoA=");
    static final byte[] HEVC = Base64.getDecoder().decode("AAAAAUIBAQFgAAADAJAAAAMAAAMAP6AFAgFx8uWVlSkwvAWgIAAAAwAgAAADACE=");
    static final byte[] AV1 = Base64.getDecoder().decode("EgAKCwAAAAxNnn82vkAQMiYQAJAAAACgACABxXhQZYU2knkQM/8U1LzSgLytUlfbsZ2j6yDw0A==");

    @Test public void realH264SpsIncludesCropAndEmulationPrevention() {
        assertSize(StreamDimensions.h264(H264, H264.length), 360, 640);
        byte[] threeBytePrefix = Arrays.copyOfRange(H264, 1, H264.length);
        assertSize(StreamDimensions.h264(threeBytePrefix, threeBytePrefix.length), 360, 640);
    }

    @Test public void realHevcSpsIncludesConformanceCropAndEmulationPrevention() {
        assertSize(StreamDimensions.hevc(HEVC, HEVC.length), 640, 360);
    }

    @Test public void realAv1SkipsTemporalDelimiterAndFindsSequenceHeader() {
        assertSize(StreamDimensions.av1(AV1, AV1.length), 360, 640);
        assertNull(StreamDimensions.av1(new byte[] {0x12, 0}, 2));
    }

    @Test public void phoneSizedHevcMain10AndAv1SequenceHeaders() {
        byte[] hevcMain10 = Base64.getDecoder().decode("AAAAAUIBAQIgAAADAJAAAAMAAAMAlqAC0IAMMTZZWVKTC8BaAgAAAwACAAADAAIQ");
        assertSize(StreamDimensions.hevc(hevcMain10, hevcMain10.length), 1440, 3120);
        byte[] av1 = Base64.getDecoder().decode("EgAKDAAAAGLrC+z7lfIAgA==");
        assertSize(StreamDimensions.av1(av1, av1.length), 3120, 1440);
    }

    @Test public void av1ReducedStillPictureHeader() {
        BitWriter b = new BitWriter();
        b.put(0, 3).put(1, 1).put(1, 1).put(8, 5); // profile, still, reduced, level
        b.put(11, 4).put(11, 4).put(1439, 12).put(3119, 12);
        byte[] obu = b.av1Obu();
        assertSize(StreamDimensions.av1(obu, obu.length), 1440, 3120);
    }

    @Test public void av1TimingDecoderModelAndMultipleOperatingPoints() {
        BitWriter b = new BitWriter();
        b.put(2, 3).put(0, 1).put(0, 1).put(1, 1); // profile, still, reduced, timing present
        b.put(1, 32).put(60, 32).put(1, 1).put(3, 3); // timing and UVLC equal interval = 2
        b.put(1, 1).put(3, 5).put(1, 32).put(7, 5).put(9, 5); // decoder model, delay bits = 4
        b.put(1, 1).put(1, 5); // initial delay present, two operating points
        b.put(0, 12).put(8, 5).put(0, 1).put(1, 1).put(0, 4).put(0, 4).put(1, 1).put(1, 1).put(0, 4);
        b.put(0, 12).put(4, 5).put(0, 1).put(0, 1); // no tier bit, model, or initial delay
        b.put(11, 4).put(11, 4).put(3119, 12).put(1439, 12);
        byte[] obu = b.av1Obu();
        assertSize(StreamDimensions.av1(obu, obu.length), 3120, 1440);
    }

    private static final class BitWriter {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int value, used;
        BitWriter put(long bits, int count) {
            for (int i = count - 1; i >= 0; --i) {
                value = (value << 1) | (int)((bits >> i) & 1);
                if (++used == 8) { out.write(value); value = used = 0; }
            }
            return this;
        }
        byte[] av1Obu() {
            if (used != 0) out.write(value << (8 - used));
            byte[] payload = out.toByteArray();
            byte[] obu = new byte[payload.length + 2];
            obu[0] = 0x0a; obu[1] = (byte)payload.length;
            System.arraycopy(payload, 0, obu, 2, payload.length);
            return obu;
        }
    }

    @Test public void truncatedHeadersAreRejected() {
        for (int length = 0; length < 8; ++length) {
            final int n = length;
            assertThrows(IllegalArgumentException.class, () -> StreamDimensions.h264(H264, n));
            assertThrows(IllegalArgumentException.class, () -> StreamDimensions.hevc(HEVC, n));
        }
        assertThrows(IllegalArgumentException.class, () -> StreamDimensions.av1(new byte[] {0x0a, 127, 0}, 3));
        assertThrows(IllegalArgumentException.class, () -> StreamDimensions.av1(new byte[] {(byte)0x80}, 1));
        assertThrows(IllegalArgumentException.class, () -> StreamDimensions.av1(new byte[] {0x0a, (byte)0x80}, 2));
    }

    static void assertSize(StreamDimensions dimensions, int width, int height) {
        assertNotNull(dimensions);
        assertEquals(width, dimensions.width);
        assertEquals(height, dimensions.height);
    }
}
