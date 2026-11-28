package com.limelight;

import android.content.res.Configuration;
import android.os.Looper;
import com.limelight.binding.audio.PhoneMicrophone;
import com.limelight.binding.video.PhoneCamera;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.StreamView;
import com.limelight.utils.RotationResizeState;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GameResizeTest {
    private Game game;
    private NvConnection conn;
    private PreferenceConfiguration prefs;
    private StreamView view;
    private MediaCodecDecoderRenderer decoder;
    private PhoneMicrophone microphone;
    private PhoneCamera camera;
    private RotationResizeState state;

    @Before public void setUp() {
        game = Robolectric.buildActivity(Game.class).get();
        conn = mock(NvConnection.class);
        when(conn.supportsResize()).thenReturn(true);
        prefs = new PreferenceConfiguration();
        prefs.width = 3120; prefs.height = 1440;
        view = mock(StreamView.class);
        decoder = mock(MediaCodecDecoderRenderer.class);
        microphone = mock(PhoneMicrophone.class);
        camera = mock(PhoneCamera.class);
        ReflectionHelpers.setField(game, "nativeResolution", true);
        ReflectionHelpers.setField(game, "connected", true);
        ReflectionHelpers.setField(game, "conn", conn);
        ReflectionHelpers.setField(game, "prefConfig", prefs);
        ReflectionHelpers.setField(game, "streamView", view);
        ReflectionHelpers.setField(game, "decoderRenderer", decoder);
        ReflectionHelpers.setField(game, "phoneMicrophone", microphone);
        ReflectionHelpers.setField(game, "phoneCamera", camera);
        state = ReflectionHelpers.getField(game, "resizeState");
        orient(true);
    }

    private void orient(boolean portrait) {
        game.getResources().getConfiguration().orientation = portrait ?
                Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
    }
    private void rotate() { ReflectionHelpers.callInstanceMethod(game, "resizeForRotation"); }
    private void deferFallback() { ReflectionHelpers.setField(game, "cameraPermissionPending", true); }

    @Test public void acknowledgedRotationUpdatesViewAndKeepsSessionAndCaptures() {
        rotate();
        verify(conn).sendResizeRequest(1, 1440, 3120);
        assertEquals(3120, prefs.width); // no speculative preference mutation
        game.resizeResult(1, 0, 1440, 3120);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1440, prefs.width); assertEquals(3120, prefs.height);
        verify(view).setDesiredAspectRatio(1440.0 / 3120);
        verify(view).requestLayout();
        verify(decoder).expectStreamDimensions(1440, 3120);
        assertFalse(state.isPending(1));
        verify(conn, never()).stopForRotation();
        verifyNoInteractions(microphone, camera);
    }

    @Test public void staleResultDoesNotCancelNewestTimerOrChangeSize() {
        rotate();
        orient(false); rotate();
        verify(conn).sendResizeRequest(2, 3120, 1440);
        game.resizeResult(1, 0, 1440, 3120);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(3120, prefs.width);
        assertTrue(state.isPending(2));
        game.resizeResult(2, 0, 3120, 1440);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(state.isPending(2));
        verify(conn, never()).stopForRotation();
    }

    @Test public void latestTimeoutFiresAtFourSecondsAndDefersBlockedReconnect() {
        rotate();
        deferFallback();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3999));
        assertTrue(state.isPending(1));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        assertFalse(state.isPending(1));
        assertTrue(ReflectionHelpers.<Boolean>getField(game, "resizeFallbackPending"));
        assertEquals(3120, prefs.width);
    }

    @Test public void rejectionIncludingUnknownStatusTriggersFallback() {
        rotate(); deferFallback();
        game.resizeResult(1, 255, 3120, 1440);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(state.isPending(1));
        assertTrue(ReflectionHelpers.<Boolean>getField(game, "resizeFallbackPending"));
        assertEquals(3120, prefs.width);
        verifyNoInteractions(decoder);
    }

    @Test public void staleTimeoutCannotCancelFinalAcknowledgedOrientation() {
        rotate();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        orient(false); rotate();
        game.resizeResult(2, 0, 3120, 1440);
        shadowOf(Looper.getMainLooper()).idle();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertFalse(ReflectionHelpers.<Boolean>getField(game, "resizeFallbackPending"));
        assertFalse(ReflectionHelpers.<Boolean>getField(game, "rotationRestart"));
        verify(conn, never()).stopForRotation();
    }

    @Test public void sameAxisDoesNotSendARequest() {
        orient(false); rotate();
        verify(conn, never()).sendResizeRequest(anyInt(), anyInt(), anyInt());
    }
}
