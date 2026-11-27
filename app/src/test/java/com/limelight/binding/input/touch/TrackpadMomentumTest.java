package com.limelight.binding.input.touch;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrackpadMomentumTest {
    private TrackpadMomentum flick(double x, double y) {
        TrackpadMomentum momentum = new TrackpadMomentum();
        momentum.addSample(0, 0, 1000);
        momentum.addSample(x / 2, y / 2, 1020);
        momentum.addSample(x, y, 1040);
        return momentum;
    }

    @Test public void slowMovesAndStationaryTapsDoNotGlide() {
        assertFalse(flick(4, 4).start(1040));
        assertFalse(flick(0, 0).start(1040));
        TrackpadMomentum momentum = new TrackpadMomentum();
        momentum.addSample(100, 100, 1000);
        assertFalse(momentum.start(1000));
    }

    @Test public void releaseAfterPauseDiscardsVelocityEvenWithStationarySamples() {
        assertFalse(flick(100, 0).start(1090));
        TrackpadMomentum momentum = flick(100, 0);
        momentum.addSample(100, 0, 1080);
        momentum.addSample(100, 0, 1100);
        assertFalse(momentum.start(1100));
    }

    @Test public void oldMotionDoesNotMaskRecentReversal() {
        TrackpadMomentum momentum = new TrackpadMomentum();
        momentum.addSample(0, 0, 0);
        momentum.addSample(1000, 0, 100);
        momentum.addSample(950, 0, 140);
        assertTrue(momentum.start(140));
        assertTrue(momentum.step(16).x < 0);
    }

    @Test public void decayHasExpectedDistanceAndDirection() {
        TrackpadMomentum momentum = flick(40, -20);
        assertTrue(momentum.start(1040));
        TrackpadMomentum.Delta first = momentum.step(16);
        assertEquals(1000 * .320 * (1 - Math.exp(-16.0 / 320)), first.x, .0001);
        assertEquals(-first.x / 2, first.y, .0001);
        assertTrue(momentum.step(16).x < first.x);
    }

    @Test public void integratedDistanceIsIndependentOfFrameRate() {
        TrackpadMomentum sixty = flick(40, 0);
        TrackpadMomentum oneTwenty = flick(40, 0);
        assertTrue(sixty.start(1040));
        assertTrue(oneTwenty.start(1040));
        double a = 0, b = 0;
        for (int i = 0; i < 40; i++) a += sixty.step(16).x;
        for (int i = 0; i < 80; i++) b += oneTwenty.step(8).x;
        assertEquals(a, b, .0001);
    }

    @Test public void glideStopsWithinOneSecondAndCancelPreventsMoreMotion() {
        TrackpadMomentum momentum = flick(400, 0);
        assertTrue(momentum.start(1040));
        momentum.step(5000);
        assertFalse(momentum.isActive());
        assertEquals(0, momentum.step(16).x, 0);
        momentum = flick(40, 0);
        assertTrue(momentum.start(1040));
        momentum.cancel();
        assertFalse(momentum.isActive());
        assertEquals(0, momentum.step(16).x, 0);
        assertFalse(momentum.start(1050));
    }

    @Test public void minimumSpeedEndsGlideEarlyAndInvalidTimesDoNotMove() {
        TrackpadMomentum momentum = flick(12, 0);
        assertTrue(momentum.start(1040));
        assertEquals(0, momentum.step(0).x, 0);
        assertEquals(0, momentum.step(-10).x, 0);
        momentum.step(700);
        assertFalse(momentum.isActive());
        momentum = flick(40, 0);
        assertFalse(momentum.start(1030));
    }
}
