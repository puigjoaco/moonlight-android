package com.limelight.utils;

import org.junit.Test;
import static org.junit.Assert.*;

public class RotationResizeStateTest {
    @Test public void sameAxisIncluding180DegreesDoesNothing() {
        RotationResizeState state = new RotationResizeState();
        assertFalse(state.needsRequest(3120, 1440, 3120, 1440));
        assertTrue(state.needsRequest(3120, 1440, 1440, 3120));
    }

    @Test public void newestTargetWinsIncludingReturnToOriginalBeforeAck() {
        RotationResizeState state = new RotationResizeState();
        int first = state.begin(1440, 3120);
        assertEquals(1, first);
        assertFalse(state.needsRequest(3120, 1440, 1440, 3120));
        assertTrue(state.needsRequest(3120, 1440, 3120, 1440));
        int last = state.begin(3120, 1440);
        assertEquals(2, last);
        assertFalse(state.isPending(first)); // stale result or timeout
        assertTrue(state.isPending(last));
        assertFalse(state.matchesSize(1440, 3120));
        assertTrue(state.matchesSize(3120, 1440));
    }

    @Test public void completionFailureAndStopInvalidateTimeoutAndDuplicateResult() {
        RotationResizeState state = new RotationResizeState();
        int sequence = state.begin(1440, 3120);
        assertEquals(4000, RotationResizeState.TIMEOUT_MS);
        assertTrue(state.isPending(sequence));
        state.clear();
        assertFalse(state.isPending(sequence));
        assertEquals(2, state.begin(3120, 1440));
        state.clear();
        assertFalse(state.isPending(2));
    }
}
