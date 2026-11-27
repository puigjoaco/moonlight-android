package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.Display;
import android.view.WindowManager;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = PreferenceConfigurationTest.ShadowMoonBridge.class)
public class PreferenceConfigurationTest {
    @Implements(value = MoonBridge.class, isInAndroidSdk = false)
    public static class ShadowMoonBridge {
        @Implementation protected static void __staticInitializer__() { }
    }

    private Context context;
    private SharedPreferences prefs;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
    }

    @Test public void freshInstallUsesNativeFullscreenAndAbsoluteMouseWithoutSavingFixedPixels() {
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        String nativePixels = PreferenceConfiguration.getNativeFullscreenResolution(context);
        assertTrue(config.nativeResolution);
        assertEquals(nativePixels, config.width + "x" + config.height);
        assertTrue(config.absoluteMouseMode);
        assertFalse(prefs.contains(PreferenceConfiguration.RESOLUTION_PREF_STRING));
        assertEquals(PreferenceConfiguration.getDefaultBitrate(nativePixels, "60"), config.bitrate);
    }

    @Test public void settingsXmlDefaultsAgreeWithRuntimeDefaults() {
        PreferenceManager.setDefaultValues(context, com.limelight.R.xml.preferences, true);
        assertEquals(PreferenceConfiguration.DEFAULT_RESOLUTION,
                prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
        assertTrue(prefs.getBoolean("checkbox_absolute_mouse_mode", false));
        assertEquals(PreferenceConfiguration.DEFAULT_FPS,
                prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, null));
        assertTrue(PreferenceConfiguration.readPreferences(context).nativeResolution);
    }

    @Test public void nativeFrameRateFollowsPanelAndBitrateWithoutSavingNumericChoice() {
        WindowManager windows = mock(WindowManager.class);
        Display display = mock(Display.class);
        Context displayContext = spy(context);
        doReturn(windows).when(displayContext).getSystemService(Context.WINDOW_SERVICE);
        when(windows.getDefaultDisplay()).thenReturn(display);
        when(display.getMode()).thenReturn(mode(1440, 3120));
        when(display.getSupportedModes()).thenReturn(new Display.Mode[] {
                mode(1440, 3120), mode(1440, 3120, 120f), mode(1080, 2340, 144f)});
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(displayContext);
        assertEquals(120, config.fps);
        assertEquals(PreferenceConfiguration.getDefaultBitrate("3120x1440", "120"), config.bitrate);
        assertFalse(prefs.contains(PreferenceConfiguration.FPS_PREF_STRING));

        // A resolution reduction must not silently make native streaming exceed the panel rate.
        when(display.getSupportedModes()).thenReturn(new Display.Mode[] {
                mode(1440, 3120), mode(1080, 2340, 120f)});
        assertEquals(60, PreferenceConfiguration.readPreferences(displayContext).fps);
        prefs.edit().putString(PreferenceConfiguration.FPS_PREF_STRING, "native").commit();
        when(display.getSupportedModes()).thenReturn(new Display.Mode[] {mode(1440, 3120, 90f)});
        assertEquals(90, PreferenceConfiguration.readPreferences(displayContext).fps);
        assertEquals("native", prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, null));
    }

    @Test public void explicitFrameRatesAndBitratesSurviveTheNewDefault() {
        for (String fps : new String[] {"30", "60", "90", "120", "144"}) {
            prefs.edit().putString(PreferenceConfiguration.FPS_PREF_STRING, fps)
                    .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 47000).commit();
            PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
            assertEquals(Integer.parseInt(fps), config.fps);
            assertEquals(47000, config.bitrate);
        }
    }

    @Test public void savedFixedResolutionMouseAndScaleArePreserved() {
        prefs.edit().putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "1280x720")
                .putBoolean("checkbox_absolute_mouse_mode", false)
                .putString("list_desktop_scale", "250").commit();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertEquals(1280, config.width);
        assertEquals(720, config.height);
        assertFalse(config.nativeResolution);
        assertFalse(config.absoluteMouseMode);
        assertEquals(250, config.desktopScale);
        assertEquals("1280x720", prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
    }

    @Test public void legacyResolutionAndFpsAreMigratedWithoutChangingChoice() {
        prefs.edit().putString("list_resolution_fps", "1080p30").commit();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertEquals(1920, config.width);
        assertEquals(1080, config.height);
        assertEquals(30, config.fps);
        assertFalse(config.nativeResolution);
        assertEquals("1920x1080", prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
    }

    @Test public void sentinelResolvesAgainWhenDisplayChangesAndStandardPixelsStayNative() {
        Context displayContext = mock(Context.class);
        WindowManager windows = mock(WindowManager.class);
        Display display = mock(Display.class);
        when(displayContext.getSystemService(Context.WINDOW_SERVICE)).thenReturn(windows);
        when(windows.getDefaultDisplay()).thenReturn(display);
        when(display.getMode()).thenReturn(mode(1440, 3120), mode(1080, 1920));
        when(display.getSupportedModes()).thenReturn(new Display.Mode[0]);
        assertEquals("3120x1440", PreferenceConfiguration.resolveResolution(displayContext,
                PreferenceConfiguration.DEFAULT_RESOLUTION));
        assertEquals("1920x1080", PreferenceConfiguration.resolveResolution(displayContext,
                PreferenceConfiguration.DEFAULT_RESOLUTION));
        assertEquals("1280x720", PreferenceConfiguration.resolveResolution(displayContext, "1280x720"));
        prefs.edit().putString(PreferenceConfiguration.RESOLUTION_PREF_STRING,
                PreferenceConfiguration.DEFAULT_RESOLUTION).commit();
        assertTrue(PreferenceConfiguration.readPreferences(context).nativeResolution);
    }

    @Test public void nativeDefaultBitrateUsesTheSameLargestModeAsGame() {
        WindowManager windows = mock(WindowManager.class);
        Display display = mock(Display.class);
        Context displayContext = spy(context);
        doReturn(windows).when(displayContext).getSystemService(Context.WINDOW_SERVICE);
        when(windows.getDefaultDisplay()).thenReturn(display);
        when(display.getMode()).thenReturn(mode(1920, 1080));
        when(display.getSupportedModes()).thenReturn(new Display.Mode[] {
                mode(1920, 1080), mode(3840, 2160), mode(2560, 1440)});
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(displayContext);
        android.graphics.Point gamePixels = com.limelight.utils.NativeDisplayPolicy.fullscreenPixels(display);
        assertEquals(3840, gamePixels.x);
        assertEquals(2160, gamePixels.y);
        assertEquals(gamePixels.x, config.width);
        assertEquals(gamePixels.y, config.height);
        assertEquals(80000, config.bitrate);
        assertEquals(80000, PreferenceConfiguration.getDefaultBitrate(displayContext));
        prefs.edit().putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 12345).commit();
        assertEquals(12345, PreferenceConfiguration.readPreferences(displayContext).bitrate);
    }

    private Display.Mode mode(int width, int height) {
        return mode(width, height, 60f);
    }

    private Display.Mode mode(int width, int height, float refreshRate) {
        return ReflectionHelpers.callConstructor(Display.Mode.class,
                ClassParameter.from(int.class, 1), ClassParameter.from(int.class, width),
                ClassParameter.from(int.class, height), ClassParameter.from(float.class, refreshRate));
    }
}
