package com.limelight.binding.video;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Camera v1 framing, deliberately small enough for an unfragmented encrypted control packet. */
public final class CameraPacketizer {
    public static final int CHUNK = 768, HEADER = 24, MAX_FRAME = CHUNK * 512;
    public interface Sink { int send(byte[] packet); }
    private CameraPacketizer() { }
    public static boolean send(byte[] frame, boolean key, int rotation, int sequence, long timestampUs, Sink sink) {
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)
            throw new IllegalArgumentException("Invalid camera rotation");
        if (frame != null && (frame.length == 0 || frame.length > MAX_FRAME)) return false;
        int count = frame == null ? 0 : (frame.length + CHUNK - 1) / CHUNK;
        for (int index = 0; index < Math.max(1, count); index++) {
            int bytes = frame == null ? 0 : Math.min(CHUNK, frame.length - index * CHUNK);
            ByteBuffer p = ByteBuffer.allocate(HEADER + bytes).order(ByteOrder.LITTLE_ENDIAN);
            p.put((byte)1).put((byte)(frame == null ? 2 : key ? 1 : 0)).putShort((short)rotation);
            p.putInt(0).putInt(sequence).putShort((short)index).putShort((short)count).putLong(timestampUs);
            if (frame != null) p.put(frame, index * CHUNK, bytes);
            if (sink.send(p.array()) != 0) return false;
        }
        return true;
    }
    /** Android's H.264 encoders emit Annex-B access units and csd buffers; reject anything else. */
    public static byte[] annexB(byte[] data) {
        if (data.length < 1 || data.length > MAX_FRAME) throw new IllegalArgumentException("Invalid H264 size");
        if (data.length >= 4 && data[0] == 0 && data[1] == 0 &&
                (data[2] == 1 || (data[2] == 0 && data[3] == 1))) return data;
        throw new IllegalArgumentException("Unsupported H264 framing");
    }
    public static boolean hasNal(byte[] data, int type) {
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                int start = data[i + 2] == 1 ? i + 3 :
                        i + 4 < data.length && data[i + 2] == 0 && data[i + 3] == 1 ? i + 4 : -1;
                if (start >= 0 && (data[start] & 31) == type) return true;
            }
        }
        return false;
    }
    public static byte[] join(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
