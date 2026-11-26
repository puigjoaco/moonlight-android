package com.limelight.binding.input.keyboard;

/** Converts IME commits to US-position key strokes; Unicode packets are reserved for non-ASCII text. */
public final class TextInput {
    public interface Sink {
        void key(int vk, boolean down);
        void unicode(String text);
    }
    public static final class Stroke {
        public final int vk;
        public final boolean shift;
        Stroke(int vk, boolean shift) { this.vk = vk; this.shift = shift; }
    }
    private TextInput() { }
    public static Stroke ascii(char ch) {
        if (ch >= 'a' && ch <= 'z') return new Stroke(ch - 'a' + 0x41, false);
        if (ch >= 'A' && ch <= 'Z') return new Stroke(ch, true);
        if (ch >= '0' && ch <= '9') return new Stroke(ch, false);
        String plain = "`-=[]\\;',./";
        String shifted = "~_+{}|:\"<>?";
        int[] vk = {0xC0,0xBD,0xBB,0xDB,0xDD,0xDC,0xBA,0xDE,0xBC,0xBE,0xBF};
        int index = plain.indexOf(ch);
        if (index >= 0) return new Stroke(vk[index], false);
        index = shifted.indexOf(ch);
        if (index >= 0) return new Stroke(vk[index], true);
        index = ")!@#$%^&*(".indexOf(ch);
        if (index >= 0) return new Stroke(0x30 + index, true);
        switch (ch) {
            case ' ': return new Stroke(0x20, false);
            case '\n': case '\r': return new Stroke(0x0D, false);
            case '\t': return new Stroke(0x09, false);
            case '\b': return new Stroke(0x08, false);
            default: return null;
        }
    }
    public static void send(String text, Sink sink) {
        for (int index = 0; index < text.length();) {
            char ch = text.charAt(index);
            Stroke stroke = ascii(ch);
            if (stroke != null) {
                if (stroke.shift) sink.key(0xA0, true);
                sink.key(stroke.vk, true);
                sink.key(stroke.vk, false);
                if (stroke.shift) sink.key(0xA0, false);
                index++;
                if (ch == '\r' && index < text.length() && text.charAt(index) == '\n') index++;
            } else if (ch > 0x7F) {
                int start = index;
                do { index += Character.charCount(text.codePointAt(index)); }
                while (index < text.length() && text.charAt(index) > 0x7F);
                sink.unicode(text.substring(start, index));
            } else index++; // Ignore other control codes; they must never become an implicit Enter.
        }
    }
}
