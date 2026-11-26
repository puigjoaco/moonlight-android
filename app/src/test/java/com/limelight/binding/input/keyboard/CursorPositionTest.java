package com.limelight.binding.input.keyboard;

import org.junit.Test;
import static org.junit.Assert.*;

public class CursorPositionTest {
    @Test public void mirrorsNativeCentreThenPixelRoundingAndClamping() {
        CursorPosition position = new CursorPosition();
        assertEquals(0.5f, position.y(), 0);
        position.relative(0, 10, 100, 200);
        assertEquals(110f / 199, position.y(), 0);
        position.relative(0, -1000, 100, 200);
        assertEquals(0, position.y(), 0);
        position.absolute(1000, 1000, 100, 200);
        assertEquals(1, position.y(), 0);
        position.relative(0, -1, 100, 200); // Native multiplies by height then clamps to height - 1.
        assertEquals(1, position.y(), 0);
    }
    @Test public void ignoresZeroSizedReferenceFrames() {
        CursorPosition position = new CursorPosition();
        position.absolute(1, 1, 0, 0);
        assertEquals(0.5f, position.y(), 0);
    }
}
