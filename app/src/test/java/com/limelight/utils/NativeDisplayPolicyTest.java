package com.limelight.utils;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeDisplayPolicyTest {
    @Test public void densityIsRoundedAndBounded() {
        assertEquals(100, NativeDisplayPolicy.scalePercent(240));
        assertEquals(250, NativeDisplayPolicy.scalePercent(600));
        assertEquals(250, NativeDisplayPolicy.scalePercent(629));
        assertEquals(275, NativeDisplayPolicy.scalePercent(630));
        assertEquals(200, NativeDisplayPolicy.scalePercent(480));
        assertEquals(100, NativeDisplayPolicy.scalePercent(0));
        assertEquals(100, NativeDisplayPolicy.scalePercent(160));
        assertEquals(400, NativeDisplayPolicy.scalePercent(1200));
    }

    @Test public void explicitScaleOverridesAutoAtEverySupportedStep() {
        for (int percent = 100; percent <= 400; percent += 25) {
            assertEquals(percent, NativeDisplayPolicy.scalePercent(600, Integer.toString(percent)));
        }
        assertEquals(250, NativeDisplayPolicy.scalePercent(600, "auto"));
        assertEquals(200, NativeDisplayPolicy.scalePercent(480, "auto"));
    }

    @Test public void invalidSavedScalesFallBackToAuto() {
        for (String value : new String[] {null, "", "bogus", "0", "99", "401", "110", "250%", "999999999999"}) {
            assertEquals(250, NativeDisplayPolicy.scalePercent(600, value));
        }
    }

    @Test public void nativePixelsFollowOrientation() {
        assertEquals(1440, NativeDisplayPolicy.width(1440, 3120, true));
        assertEquals(3120, NativeDisplayPolicy.height(1440, 3120, true));
        assertEquals(3120, NativeDisplayPolicy.width(1440, 3120, false));
        assertEquals(1440, NativeDisplayPolicy.height(1440, 3120, false));
        assertEquals(1440, NativeDisplayPolicy.width(3120, 1440, true));
    }

    @Test public void restartOnlyWhenLatestAxisDiffers() {
        assertFalse(NativeDisplayPolicy.needsReconnect(1440, 3120, true));
        assertTrue(NativeDisplayPolicy.needsReconnect(1440, 3120, false));
        assertTrue(NativeDisplayPolicy.needsReconnect(3120, 1440, true));
        assertFalse(NativeDisplayPolicy.needsReconnect(3120, 1440, false));
        assertFalse(NativeDisplayPolicy.needsReconnect(2000, 2000, false));
    }
}
