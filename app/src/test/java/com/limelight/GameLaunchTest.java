package com.limelight;

import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.PreferenceConfiguration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GameLaunchTest {
    private Context context;
    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("computers4.db");
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        context.getSharedPreferences("porta_home", 0).edit().clear().commit();
    }

    @Test public void gameReadsItsHostMicChoiceAndHostTogglesPreserveGlobalDefault() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("checkbox_phone_microphone", true).commit();
        PcView home = Robolectric.buildActivity(PcView.class).get();
        ReflectionHelpers.callInstanceMethod(home, "setMicrophone",
                ReflectionHelpers.ClassParameter.from(String.class, "host-off"),
                ReflectionHelpers.ClassParameter.from(boolean.class, false));
        Game game = Robolectric.buildActivity(Game.class,
                new Intent().putExtra(Game.EXTRA_PC_UUID, "host-off")).get();
        assertFalse(ReflectionHelpers.<Boolean>callInstanceMethod(game, "microphoneEnabled"));
        assertTrue(PreferenceConfiguration.microphoneEnabled(context, "unsaved-host"));
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("checkbox_phone_microphone", false));
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("checkbox_phone_microphone", false).commit();
        ReflectionHelpers.callInstanceMethod(home, "setMicrophone",
                ReflectionHelpers.ClassParameter.from(String.class, "host-on"),
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        game.setIntent(new Intent().putExtra(Game.EXTRA_PC_UUID, "host-on"));
        assertTrue(ReflectionHelpers.<Boolean>callInstanceMethod(game, "microphoneEnabled"));
        assertFalse(PreferenceConfiguration.microphoneEnabled(context, "unsaved-host"));
    }

    @Test public void commonGameEntryRecordsUsageWithoutADesktopButtonLaunch() {
        ComputerDatabaseManager db = new ComputerDatabaseManager(context);
        ComputerDetails host = new ComputerDetails();
        host.uuid = "host";
        host.name = "Test host";
        db.updateComputer(host);
        assertEquals(0, db.getComputerByUUID(host.uuid).lastUsed);
        Game game = Robolectric.buildActivity(Game.class,
                new Intent().putExtra(Game.EXTRA_PC_UUID, host.uuid)).get();
        long before = System.currentTimeMillis();
        game.recordComputerUsed();
        long used = db.getComputerByUUID(host.uuid).lastUsed;
        assertTrue(used >= before && used <= System.currentTimeMillis());
        db.close();
    }
}
