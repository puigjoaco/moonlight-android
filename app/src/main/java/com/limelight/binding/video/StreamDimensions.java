package com.limelight.binding.video;

import java.util.Arrays;

/** Reads only the coded-size prefix of parameter sets; no dependency on control/video ordering. */
final class StreamDimensions {
    final int width, height;

    private StreamDimensions(int width, int height) {
        if (width < 1 || height < 1 || width > 16384 || height > 16384)
            throw new IllegalArgumentException("Invalid video dimensions");
        this.width = width;
        this.height = height;
    }

    static StreamDimensions h264(byte[] data, int length) {
        Bits b = nalu(data, length, 1);
        int profile = b.read(8);
        b.skip(16); // constraints, level
        b.ue(); // SPS id
        int chroma = 1;
        boolean separatePlane = false;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 ||
                profile == 83 || profile == 86 || profile == 118 || profile == 128 || profile == 138 ||
                profile == 139 || profile == 134 || profile == 135) {
            chroma = b.ue();
            if (chroma > 3) throw new IllegalArgumentException("Invalid chroma format");
            if (chroma == 3) separatePlane = b.read(1) != 0;
            b.ue(); b.ue(); b.skip(1);
            if (b.read(1) != 0) {
                for (int i = 0; i < (chroma == 3 ? 12 : 8); ++i) {
                    if (b.read(1) != 0) {
                        int last = 8, next = 8;
                        for (int j = 0; j < (i < 6 ? 16 : 64); ++j) {
                            if (next != 0) next = (last + b.se() + 256) & 255;
                            if (next != 0) last = next;
                        }
                    }
                }
            }
        }
        b.ue();
        int poc = b.ue();
        if (poc == 0) b.ue();
        else if (poc == 1) {
            b.skip(1); b.se(); b.se();
            int count = b.ue();
            if (count > 255) throw new IllegalArgumentException("Invalid POC cycle");
            for (int i = 0; i < count; ++i) b.se();
        } else if (poc > 2) throw new IllegalArgumentException("Invalid POC type");
        b.ue(); b.skip(1);
        int width = (b.ue() + 1) * 16;
        int height = (b.ue() + 1) * 16;
        int frameOnly = b.read(1);
        height *= 2 - frameOnly;
        if (frameOnly == 0) b.skip(1);
        b.skip(1);
        if (b.read(1) != 0) {
            if (separatePlane) chroma = 0;
            int cropX = chroma == 1 || chroma == 2 ? 2 : 1;
            int cropY = (chroma == 1 ? 2 : 1) * (2 - frameOnly);
            width -= (b.ue() + b.ue()) * cropX;
            height -= (b.ue() + b.ue()) * cropY;
        }
        return new StreamDimensions(width, height);
    }

    static StreamDimensions hevc(byte[] data, int length) {
        Bits b = nalu(data, length, 2);
        b.skip(4); // VPS id
        int layers = b.read(3);
        b.skip(1);
        b.skip(96); // general profile_tier_level, including level_idc
        boolean[] profile = new boolean[layers], level = new boolean[layers];
        for (int i = 0; i < layers; ++i) {
            profile[i] = b.read(1) != 0; level[i] = b.read(1) != 0;
        }
        if (layers > 0) b.skip((8 - layers) * 2);
        for (int i = 0; i < layers; ++i) {
            if (profile[i]) b.skip(88);
            if (level[i]) b.skip(8);
        }
        b.ue(); // SPS id
        int chroma = b.ue();
        if (chroma > 3) throw new IllegalArgumentException("Invalid chroma format");
        if (chroma == 3 && b.read(1) != 0) chroma = 0;
        int width = b.ue(), height = b.ue();
        if (b.read(1) != 0) {
            width -= (b.ue() + b.ue()) * (chroma == 1 || chroma == 2 ? 2 : 1);
            height -= (b.ue() + b.ue()) * (chroma == 1 ? 2 : 1);
        }
        return new StreamDimensions(width, height);
    }

    /** AV1 low-overhead OBU stream: sequence headers travel inline with the key frame. */
    static StreamDimensions av1(byte[] data, int length) {
        if (length < 0 || length > data.length) throw new IllegalArgumentException("Invalid length");
        int pos = 0;
        while (pos < length) {
            int header = data[pos++] & 255;
            if ((header & 0x81) != 0) throw new IllegalArgumentException("Invalid OBU header");
            int type = (header >> 3) & 15;
            if ((header & 4) != 0) {
                if (pos == length) throw new IllegalArgumentException("Missing OBU extension");
                ++pos;
            }
            long size = length - pos;
            if ((header & 2) != 0) {
                size = 0;
                boolean complete = false;
                for (int i = 0; i < 8 && pos < length; ++i) {
                    int next = data[pos++] & 255;
                    size |= (long)(next & 127) << (7 * i);
                    if ((next & 128) == 0) { complete = true; break; }
                }
                if (!complete) throw new IllegalArgumentException("Invalid OBU length");
            }
            if (size > length - pos) throw new IllegalArgumentException("Truncated OBU");
            if (type == 1) return av1Sequence(new Bits(Arrays.copyOfRange(data, pos, pos + (int)size)));
            pos += (int)size;
        }
        return null;
    }

    private static StreamDimensions av1Sequence(Bits b) {
        b.skip(4); // seq_profile, still_picture
        boolean reduced = b.read(1) != 0;
        if (reduced) b.skip(5);
        else {
            boolean decoderModel = false;
            int delayBits = 0;
            if (b.read(1) != 0) {
                b.skip(64);
                if (b.read(1) != 0) b.ue(); // equal_picture_interval / UVLC
                decoderModel = b.read(1) != 0;
                if (decoderModel) { delayBits = b.read(5) + 1; b.skip(42); }
            }
            boolean initialDelay = b.read(1) != 0;
            int count = b.read(5) + 1;
            for (int i = 0; i < count; ++i) {
                b.skip(12);
                if (b.read(5) > 7) b.skip(1);
                if (decoderModel && b.read(1) != 0) b.skip(2 * delayBits + 1);
                if (initialDelay && b.read(1) != 0) b.skip(4);
            }
        }
        int widthBits = b.read(4) + 1, heightBits = b.read(4) + 1;
        return new StreamDimensions(b.read(widthBits) + 1, b.read(heightBits) + 1);
    }

    private static Bits nalu(byte[] data, int length, int headerBytes) {
        if (length < 4 || length > data.length) throw new IllegalArgumentException("Invalid NAL length");
        int start = data[0] == 0 && data[1] == 0 && data[2] == 1 ? 3 :
                data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1 ? 4 : -1;
        if (start < 0 || start + headerBytes >= length) throw new IllegalArgumentException("Missing NAL prefix");
        byte[] rbsp = new byte[length - start - headerBytes];
        int out = 0, zeros = 0;
        for (int i = start + headerBytes; i < length; ++i) {
            int value = data[i] & 255;
            if (zeros >= 2 && value == 3) { zeros = 0; continue; }
            rbsp[out++] = data[i];
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return new Bits(Arrays.copyOf(rbsp, out));
    }

    private static final class Bits {
        final byte[] data;
        int position;
        Bits(byte[] data) { this.data = data; }
        int read(int count) {
            if (count < 0 || count > 31 || count > data.length * 8 - position)
                throw new IllegalArgumentException("Truncated bitstream");
            int value = 0;
            for (int i = 0; i < count; ++i, ++position)
                value = (value << 1) | ((data[position / 8] >> (7 - position % 8)) & 1);
            return value;
        }
        void skip(int count) {
            if (count < 0 || count > data.length * 8 - position)
                throw new IllegalArgumentException("Truncated bitstream");
            position += count;
        }
        int ue() {
            int zeros = 0;
            while (read(1) == 0) if (++zeros > 30) throw new IllegalArgumentException("Invalid Exp-Golomb");
            return (1 << zeros) - 1 + read(zeros);
        }
        int se() { int value = ue(); return (value & 1) != 0 ? (value + 1) / 2 : -value / 2; }
    }
}
