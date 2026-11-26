package com.limelight.binding.input.keyboard;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ImeTextBufferTest {
    private final List<String> events = new ArrayList<>();
    private final ImeTextBuffer buffer = new ImeTextBuffer(new ImeTextBuffer.Sink() {
        public void text(String value) { events.add("text:" + value); }
        public void delete(int before, int after) { events.add("delete:" + before + ":" + after); }
    });
    @Test public void finishingCompositionCommitsExactlyOnce() {
        buffer.compose("hel"); buffer.compose("hello"); buffer.finish(); buffer.finish();
        assertEquals(List.of("text:hello"), events);
    }
    @Test public void autocorrectInsideCompositionIsSentOnlyWhenCommitted() {
        buffer.compose("teh"); buffer.compose("the");
        assertTrue(events.isEmpty());
        buffer.commit("the "); buffer.finish();
        assertEquals(List.of("text:the "), events);
    }
    @Test public void dictationRevisionInsideCompositionIsSentOnce() {
        buffer.compose("turn left"); buffer.compose("turn right"); buffer.compose("turn right at the light");
        assertTrue(events.isEmpty());
        buffer.finish(); buffer.finish();
        assertEquals(List.of("text:turn right at the light"), events);
    }
    @Test public void outOfSpanRecompositionAndItsReplacementAreIgnoredWithoutCorruption() {
        buffer.commit("teh");
        assertFalse(buffer.composingRegion(0, 3));
        assertFalse(buffer.compose("th"));
        assertFalse(buffer.commit("the"));
        buffer.finish(); buffer.compose(" next"); buffer.commit(" next");
        assertEquals(List.of("text:teh", "text: next"), events);
    }
    @Test public void rejectedReplacementCannotDiscardAValidUnsentComposition() {
        buffer.compose("hello");
        assertFalse(buffer.composingRegion(-4, 1));
        assertFalse(buffer.commit("corruption"));
        buffer.finish();
        assertEquals(List.of("text:hello"), events);
    }
    @Test public void replacementInsideUnsentSpanPreservesTheRestOfTheComposition() {
        buffer.compose("teh cat");
        assertTrue(buffer.composingRegion(0, 3));
        buffer.commit("the");
        assertEquals(List.of("text:the cat"), events);
    }
    @Test public void utf16DeletionCountsEmojiAsOneBackspace() {
        buffer.commit("a😀"); buffer.delete(2, 0, false, 0, 0); buffer.delete(1, 0, false, 0, 0);
        assertEquals(List.of("text:a😀", "delete:1:0", "delete:1:0"), events);
    }
    @Test public void partialSurrogateRequestUsesOneWholeBackspace() {
        buffer.commit("a😀"); buffer.delete(1, 0, false, 0, 0); buffer.delete(1, 0, false, 0, 0);
        assertEquals(List.of("text:a😀", "delete:1:0", "delete:1:0"), events);
    }
    @Test public void codePointDeletionCountsEmojiWithoutConsumingAdjacentLetter() {
        buffer.commit("a😀"); buffer.delete(1, 0, true, 0, 0);
        assertEquals(List.of("text:a😀", "delete:1:0"), events);
    }
    @Test public void deletionAroundLocalCompositionReachesBothRemoteSides() {
        buffer.commit("hello"); buffer.compose("world"); buffer.delete(2, 1, false, 5, 5); buffer.finish();
        assertEquals(List.of("text:hello", "delete:2:1", "text:world"), events);
    }
    @Test public void legacyTextIsRememberedWithoutSendingItTwice() {
        buffer.remember("a😀"); buffer.finish(); buffer.delete(2, 0, false, 0, 0);
        assertEquals(List.of("delete:1:0"), events);
    }
    @Test public void deletionConsumesUnsentPrefixBeforeRemoteText() {
        buffer.commit("A"); buffer.compose("xy"); buffer.composingRegion(1, 2);
        buffer.delete(1, 0, false, 2, 2); buffer.finish();
        assertEquals(List.of("text:A", "text:y"), events);
    }
    @Test public void localPrefixAndSuffixAreRemovedBeforeForwardingOnlyTheRemainder() {
        buffer.commit("A"); buffer.compose("😀x😀"); buffer.composingRegion(2, 3);
        ImeTextBuffer.LocalDeletion edit = buffer.delete(3, 3, false, 3, 3);
        assertEquals(2, edit.beforeUnits); assertEquals(2, edit.afterUnits);
        buffer.finish();
        assertEquals(List.of("text:A", "delete:1:1", "text:x"), events);
    }
    @Test public void codePointDeletionConsumesLocalEmojiAsOne() {
        buffer.compose("😀x😀"); buffer.composingRegion(2, 3);
        buffer.delete(1, 1, true, 3, 3); buffer.finish();
        assertEquals(List.of("text:x"), events);
    }
    @Test public void forwardDeletionAfterCaretDoesNotDeleteThePendingSuffix() {
        buffer.compose("xy"); buffer.composingRegion(0, 1);
        ImeTextBuffer.LocalDeletion edit = buffer.delete(0, 1, false, 2, 2);
        assertEquals(0, edit.start); assertEquals(2, edit.end);
        assertEquals(0, edit.beforeUnits); assertEquals(0, edit.afterUnits);
        buffer.finish();
        assertEquals(List.of("delete:0:1", "text:xy"), events);
    }
    @Test public void reversedSelectionAndCompositionFormOneDeletionEnvelope() {
        buffer.compose("a😀xy"); buffer.composingRegion(3, 4);
        ImeTextBuffer.LocalDeletion edit = buffer.delete(1, 1, true, 3, 1);
        assertEquals(1, edit.start); assertEquals(4, edit.end);
        assertEquals(1, edit.beforeUnits); assertEquals(1, edit.afterUnits);
        buffer.finish();
        assertEquals(List.of("text:😀x"), events);
    }
    @Test public void backwardDeletionBeforeCaretDoesNotDeleteAnUnsentPrefixInsideTheEnvelope() {
        buffer.compose("xy"); buffer.composingRegion(1, 2);
        ImeTextBuffer.LocalDeletion edit = buffer.delete(1, 0, false, 0, 0);
        assertEquals(0, edit.start); assertEquals(2, edit.end);
        buffer.finish();
        assertEquals(List.of("delete:1:0", "text:xy"), events);
    }
    @Test public void deliveredLegacyTextClearsThePendingComposition() {
        buffer.compose("hel"); buffer.remember("hello"); buffer.finish();
        assertEquals(0, buffer.compositionLength());
        assertTrue(events.isEmpty());
        buffer.delete(1, 0, false, 0, 0);
        assertEquals(List.of("delete:1:0"), events);
    }
    @Test public void twoThousandCharacterCommitKeepsOnlyPrivateDeletionTailWithoutOffsets() {
        String text = "a".repeat(1998) + "😀";
        buffer.commit(text);
        assertEquals(0, buffer.compositionLength());
        buffer.delete(2, 0, false, 0, 0);
        buffer.compose("e\u0301"); buffer.finish();
        buffer.delete(2, 0, true, 0, 0);
        assertEquals(List.of("text:" + text, "delete:1:0", "text:e\u0301", "delete:2:0"), events);
    }
}
