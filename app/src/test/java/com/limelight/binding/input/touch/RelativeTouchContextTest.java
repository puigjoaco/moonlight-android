package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.*;

public class RelativeTouchContextTest {
    private MockedStatic<Looper> looper;
    private MockedConstruction<Handler> handlers;
    private NvConnection connection;
    private View view;

    @Before public void setUp() {
        looper = mockStatic(Looper.class);
        handlers = mockConstruction(Handler.class);
        connection = mock(NvConnection.class);
        view = mock(View.class);
    }

    @After public void tearDown() {
        handlers.close();
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
        for (int i = 1; i <= 10; i++) {
            context.touchMoveEvent(400 + i, 400 + i, 1000 + i);
        }
        verify(connection, times(10)).sendMouseMoveAsMousePosition((short)1, (short)1,
                (short)1440, (short)3120);
        context.touchMoveEvent(400, 400, 1100);
        verify(connection).sendMouseMoveAsMousePosition((short)-10, (short)-10,
                (short)1440, (short)3120);
        verifyNoMoreInteractions(connection);
    }

    @Test public void liftingAndReplacingFingerDoesNotMoveCursor() {
        RelativeTouchContext context = context(1440, 3120, true);
        context.touchMoveEvent(500, 500, 1100);
        context.touchUpEvent(500, 500, 1200);
        context.touchDownEvent(900, 1500, 1300, true);
        context.touchMoveEvent(901, 1501, 1310);
        verify(connection).sendMouseMoveAsMousePosition((short)100, (short)100, (short)1440, (short)3120);
        verify(connection).sendMouseMoveAsMousePosition((short)1, (short)1, (short)1440, (short)3120);
        verifyNoMoreInteractions(connection);
    }

    @Test public void stationaryTapClicksAtExistingCursor() {
        RelativeTouchContext context = context(1440, 3120, true);
        context.touchUpEvent(400, 400, 1100);
        verify(connection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        verifyNoMoreInteractions(connection);
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
