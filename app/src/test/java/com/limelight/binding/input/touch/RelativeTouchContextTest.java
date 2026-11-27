package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.*;

public class RelativeTouchContextTest {
    private MockedStatic<Looper> looper;
    private MockedConstruction<Handler> handlers;
    private MockedStatic<SystemClock> clock;
    private NvConnection connection;
    private View view;

    @Before public void setUp() {
        looper = mockStatic(Looper.class);
        handlers = mockConstruction(Handler.class);
        clock = mockStatic(SystemClock.class);
        connection = mock(NvConnection.class);
        view = mock(View.class);
    }

    @After public void tearDown() {
        handlers.close();
        clock.close();
        looper.close();
    }

    private RelativeTouchContext context(int width, int height, boolean desktop) {
        when(view.getWidth()).thenReturn(width);
        when(view.getHeight()).thenReturn(height);
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        prefs.absoluteMouseMode = desktop;
        RelativeTouchContext context = new RelativeTouchContext(connection, 0, 1280, 720, view, prefs);
        context.setPointerCount(1);
        context.touchDownEvent(400, 400, 1000, true);
        return context;
    }

    @Test public void desktopDiagonalUsesViewPixelsInBothOrientations() {
        for (int[] size : new int[][] {{1440, 3120}, {3120, 1440}, {1080, 2400}, {1920, 1080}}) {
            RelativeTouchContext context = context(size[0], size[1], true);
            context.touchMoveEvent(500, 500, 1100);
            verify(connection).sendMouseMoveAsMousePosition((short)100, (short)100,
                    (short)size[0], (short)size[1]);
            verifyNoMoreInteractions(connection);
            reset(connection);
        }
    }

    @Test public void desktopSlowMotionAndReversalLoseNoPixels() {
        RelativeTouchContext context = context(1440, 3120, true);
        context.touchMoveEvent(500, 500, 1001);
        for (int i = 1; i <= 10; i++) {
            context.touchMoveEvent(500 + i, 500 + i, 1010 + i);
        }
        verify(connection, times(10)).sendMouseMoveAsMousePosition((short)1, (short)1,
                (short)1440, (short)3120);
        context.touchMoveEvent(500, 500, 1100);
        verify(connection).sendMouseMoveAsMousePosition((short)100, (short)100,
                (short)1440, (short)3120);
        verify(connection).sendMouseMoveAsMousePosition((short)-10, (short)-10,
                (short)1440, (short)3120);
        verifyNoMoreInteractions(connection);
    }

    @Test public void liftingAndReplacingFingerDoesNotMoveCursor() {
        RelativeTouchContext context = context(1440, 3120, true);
        context.touchMoveEvent(500, 500, 1100);
        context.touchUpEvent(500, 500, 1200);
        context.touchDownEvent(900, 1500, 1300, true);
        context.touchMoveEvent(930, 1530, 1310);
        verify(connection).sendMouseMoveAsMousePosition((short)100, (short)100, (short)1440, (short)3120);
        verify(connection).sendMouseMoveAsMousePosition((short)30, (short)30, (short)1440, (short)3120);
        verifyNoMoreInteractions(connection);
    }

    @Test public void stationaryTapClicksAtExistingCursor() {
        RelativeTouchContext context = context(1440, 3120, true);
        context.touchUpEvent(400, 400, 1100);
        verify(connection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        verifyNoMoreInteractions(connection);
    }

    @Test public void tapJitterNeverMovesCursor() {
        RelativeTouchContext context = context(1440, 3120, true);
        context.touchMoveEvent(405, 405, 1020);
        context.touchMoveEvent(402, 402, 1040);
        context.touchUpEvent(402, 402, 1050);
        verify(connection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        verifyNoMoreInteractions(connection);
        verify(handlers.constructed().get(0), never()).postDelayed(any(), eq(16L));
    }

    private void flick(RelativeTouchContext context) {
        context.touchMoveEvent(450, 400, 1020);
        context.touchMoveEvent(500, 400, 1040);
        context.touchUpEvent(500, 400, 1040);
        context.setPointerCount(0);
    }

    private Runnable frame() {
        ArgumentCaptor<Runnable> frame = ArgumentCaptor.forClass(Runnable.class);
        verify(handlers.constructed().get(0), atLeastOnce()).postDelayed(frame.capture(), eq(16L));
        return frame.getValue();
    }

    @Test public void flickContinuesAfterLiftAndNewFingerCancelsIt() {
        RelativeTouchContext context = context(1440, 3120, true);
        when(SystemClock.uptimeMillis()).thenReturn(2000L, 2016L);
        flick(context);
        clearInvocations(connection);
        Runnable frame = frame();
        frame.run();
        verify(connection).sendMouseMoveAsMousePosition((short)39, (short)0, (short)1440, (short)3120);
        context.setPointerCount(1);
        context.touchDownEvent(900, 900, 1100, true);
        clearInvocations(connection);
        frame.run();
        verifyNoInteractions(connection);
    }

    @Test public void pauseBeforeLiftDoesNotStartAnimation() {
        RelativeTouchContext context = context(1440, 3120, true);
        context.touchMoveEvent(450, 400, 1020);
        context.touchMoveEvent(500, 400, 1040);
        context.touchUpEvent(500, 400, 1100);
        verify(handlers.constructed().get(0), never()).postDelayed(any(), eq(16L));
    }

    @Test public void multiFingerGestureCannotGlideAfterReturningToOneFinger() {
        RelativeTouchContext context = context(1440, 3120, true);
        context.setPointerCount(2);
        context.touchMoveEvent(450, 400, 1020);
        context.setPointerCount(1);
        context.touchMoveEvent(500, 400, 1040);
        context.touchUpEvent(500, 400, 1040);
        verify(handlers.constructed().get(0), never()).postDelayed(any(), eq(16L));
    }

    @Test public void cancellationRemovesQueuedMomentumAndStopsItsCallback() {
        RelativeTouchContext context = context(1440, 3120, true);
        flick(context);
        Runnable frame = frame();
        clearInvocations(connection);
        context.cancelTouch();
        frame.run();
        verifyNoInteractions(connection);
        verify(handlers.constructed().get(0), atLeastOnce()).removeCallbacks(frame);
    }

    @Test public void secondaryFingerImmediatelyStopsAnExistingGlide() {
        RelativeTouchContext context = context(1440, 3120, true);
        flick(context);
        Runnable frame = frame();
        clearInvocations(connection);
        context.setPointerCount(2);
        frame.run();
        verifyNoInteractions(connection);
    }

    @Test public void newGestureReleasesPendingTapBeforeAllowingInertia() {
        RelativeTouchContext context = context(1440, 3120, true);
        context.touchUpEvent(400, 400, 1010);
        context.setPointerCount(0);
        context.setPointerCount(1);
        context.touchDownEvent(400, 400, 1020, true);
        context.touchMoveEvent(450, 400, 1040);
        context.touchMoveEvent(500, 400, 1060);
        context.touchUpEvent(500, 400, 1060);
        verify(handlers.constructed().get(0)).postDelayed(any(), eq(16L));
        context.cancelTouch();
        context.cancelTouch();
        verify(connection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
    }

    @Test public void dragDoesNotGlideAndCancellationReleasesButtonOnce() {
        RelativeTouchContext context = context(1440, 3120, true);
        ArgumentCaptor<Runnable> timer = ArgumentCaptor.forClass(Runnable.class);
        verify(handlers.constructed().get(0)).postDelayed(timer.capture(), eq(650L));
        timer.getValue().run();
        flick(context);
        context.cancelTouch();
        verify(connection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        verify(connection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
        verify(handlers.constructed().get(0), never()).postDelayed(any(), eq(16L));
    }

    @Test public void relativePortraitUsesPortraitReference() {
        RelativeTouchContext context = context(720, 1280, false);
        context.touchMoveEvent(500, 500, 1040);
        verify(connection).sendMouseMove((short)100, (short)100);
        verifyNoMoreInteractions(connection);
    }

    @Test public void relativeTallPortraitUsesOneScaleForBothAxes() {
        RelativeTouchContext context = context(1440, 3120, false);
        context.touchMoveEvent(500, 500, 1040);
        verify(connection).sendMouseMove((short)50, (short)50);
        verifyNoMoreInteractions(connection);
    }

    @Test public void twoFingerTapIsReleasedBeforeAnImmediatePrimaryFlick() {
        RelativeTouchContext primary = context(1440, 3120, true);
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        prefs.absoluteMouseMode = true;
        RelativeTouchContext secondary = new RelativeTouchContext(connection, 1, 1280, 720, view, prefs);
        primary.setPointerCount(2);
        secondary.setPointerCount(2);
        secondary.touchDownEvent(600, 600, 1005, true);
        secondary.touchUpEvent(600, 600, 1010);
        primary.setPointerCount(1);
        secondary.setPointerCount(1);
        primary.touchUpEvent(400, 400, 1015);
        primary.setPointerCount(0);
        secondary.setPointerCount(0);
        verify(connection).sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
        verify(connection, never()).sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
        ArgumentCaptor<Runnable> release = ArgumentCaptor.forClass(Runnable.class);
        verify(handlers.constructed().get(1)).postDelayed(release.capture(), eq(100L));
        clearInvocations(handlers.constructed().get(1));

        primary.setPointerCount(1);
        secondary.setPointerCount(1);
        primary.touchDownEvent(400, 400, 1020, true);
        primary.touchMoveEvent(450, 400, 1040);
        primary.touchMoveEvent(500, 400, 1060);
        primary.touchUpEvent(500, 400, 1060);
        org.mockito.InOrder order = inOrder(connection);
        order.verify(connection).sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
        order.verify(connection).sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
        order.verify(connection, times(2)).sendMouseMoveAsMousePosition((short)50, (short)0, (short)1440, (short)3120);
        verify(handlers.constructed().get(1)).removeCallbacks(release.getValue());
        secondary.cancelTouch();
        verify(connection).sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
        verify(handlers.constructed().get(0)).postDelayed(any(), eq(16L));
    }

    @Test public void gameModeRetainsUpstreamSensitivity() {
        RelativeTouchContext context = context(1920, 1080, false);
        context.touchMoveEvent(550, 550, 1100);
        verify(connection).sendMouseMove((short)100, (short)100);
        verifyNoMoreInteractions(connection);
    }

    @Test public void desktopTwoFingerScrollRetainsUpstreamSensitivity() {
        RelativeTouchContext context = context(1440, 3120, true);
        context.setPointerCount(2);
        context.touchMoveEvent(400, 530, 1100);
        verify(connection).sendMouseHighResScroll((short)150);
        verifyNoMoreInteractions(connection);
    }
}
