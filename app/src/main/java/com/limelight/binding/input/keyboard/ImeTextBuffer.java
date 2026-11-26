package com.limelight.binding.input.keyboard;

/** Only unsent composition is editable. A small private tail is used solely for Backspace lengths. */
public final class ImeTextBuffer {
    public interface Sink {
        void text(String text);
        void delete(int beforeCodePoints, int afterCodePoints);
    }
    private static final int TAIL_LIMIT = 256;
    private final Sink sink;
    private final StringBuilder tail = new StringBuilder();
    private StringBuilder composition;
    private int regionStart, regionEnd;
    private boolean rejectedReplacement;

    public ImeTextBuffer(Sink sink) { this.sink = sink; }

    /** Reject re-selection of text already sent to the host, including the following replacement. */
    public boolean composingRegion(int start, int end) {
        int left = Math.min(start, end), right = Math.max(start, end);
        if (composition == null || left < 0 || right > composition.length()) {
            rejectedReplacement = true;
            return false;
        }
        regionStart = left;
        regionEnd = right;
        return true;
    }
    public void rejectReplacement() { rejectedReplacement = true; }
    public boolean compose(String text) {
        if (rejectedReplacement) return false;
        if (composition == null) {
            composition = new StringBuilder(text);
            regionStart = 0;
        } else {
            composition.replace(regionStart, regionEnd, text);
        }
        regionEnd = regionStart + text.length();
        return true;
    }
    public boolean commit(String text) {
        if (!compose(text)) {
            rejectedReplacement = false;
            return false;
        }
        String value = composition.toString();
        clearComposition();
        if (!value.isEmpty()) {
            sink.text(value);
            remember(value);
        }
        return true;
    }
    public void finish() {
        rejectedReplacement = false;
        if (composition != null) commit(composition.substring(regionStart, regionEnd));
    }
    private void clearComposition() {
        composition = null;
        regionStart = regionEnd = 0;
        rejectedReplacement = false;
    }
    /** Legacy ACTION_MULTIPLE was already delivered; retain only deletion context. */
    public void remember(String text) {
        clearComposition(); // ACTION_MULTIPLE replaced the local composing state with delivered text.
        tail.append(text);
        int excess = tail.length() - TAIL_LIMIT;
        if (excess > 0) {
            if (Character.isLowSurrogate(tail.charAt(excess))) excess++;
            tail.delete(0, excess);
        }
    }
    public void forgetTail() { tail.setLength(0); }
    public int compositionLength() { return composition == null ? 0 : composition.length(); }
    public static final class LocalDeletion {
        public final int start, end, beforeUnits, afterUnits;
        LocalDeletion(int start, int end, int beforeUnits, int afterUnits) {
            this.start = start; this.end = end;
            this.beforeUnits = beforeUnits; this.afterUnits = afterUnits;
        }
    }
    private static int units(String text, int requested, boolean points, boolean backwards) {
        if (points) {
            int count = Math.min(requested, text.codePointCount(0, text.length()));
            return backwards ? text.length() - text.offsetByCodePoints(text.length(), -count)
                             : text.offsetByCodePoints(0, count);
        }
        int result = Math.min(requested, text.length());
        int boundary = backwards ? text.length() - result : result;
        if (boundary > 0 && boundary < text.length() && Character.isLowSurrogate(text.charAt(boundary))) result++;
        return result;
    }
    public LocalDeletion delete(int beforeLength, int afterLength, boolean codePoints,
                                int selectionStart, int selectionEnd) {
        int length = compositionLength();
        int start = Math.max(0, Math.min(length, Math.min(selectionStart, selectionEnd)));
        int end = Math.max(start, Math.min(length, Math.max(selectionStart, selectionEnd)));
        // Android deletes outside the envelope containing both the selection and composing span.
        if (composition != null) {
            start = Math.min(start, regionStart);
            end = Math.max(end, regionEnd);
        }
        if (beforeLength < 0 || afterLength < 0) return new LocalDeletion(start, end, 0, 0);
        String prefix = composition == null ? "" : composition.substring(0, start);
        String suffix = composition == null ? "" : composition.substring(end);
        int localBefore = units(prefix, beforeLength, codePoints, true);
        int localAfter = units(suffix, afterLength, codePoints, false);
        beforeLength -= codePoints ? prefix.codePointCount(prefix.length() - localBefore, prefix.length())
                                   : Math.min(beforeLength, localBefore);
        afterLength -= codePoints ? suffix.codePointCount(0, localAfter) : Math.min(afterLength, localAfter);
        if (composition != null) {
            composition.delete(end, end + localAfter);
            composition.delete(start - localBefore, start);
            regionStart -= localBefore;
            regionEnd -= localBefore;
        }
        int knownUnits = units(tail.toString(), beforeLength, codePoints, true);
        int knownPoints = tail.codePointCount(tail.length() - knownUnits, tail.length());
        int before = knownPoints + Math.max(0, beforeLength - (codePoints ? knownPoints : knownUnits));
        tail.delete(tail.length() - knownUnits, tail.length());
        // Remote deletions are never suppressed: the IME has no committed context to reselect.
        if (before != 0 || afterLength != 0) sink.delete(before, afterLength);
        return new LocalDeletion(start, end, localBefore, localAfter);
    }
}
