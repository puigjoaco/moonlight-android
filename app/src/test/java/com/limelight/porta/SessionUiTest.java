package com.limelight.porta;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.view.SurfaceView;
import com.limelight.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class SessionUiTest {
    private boolean contains(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (contains(group.getChildAt(i), text)) return true;
        }
        return false;
    }

    @Test public void reconnectCardWaitsThreeHundredMilliseconds() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.Theme_Porta);
        controller.setup();
        FrameLayout layer = new FrameLayout(activity);
        activity.setContentView(layer);
        SessionUi ui = new SessionUi(activity, layer, new SurfaceView(activity), "torre", () -> {}, () -> {}, () -> {});
        ui.reconnecting(null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(299));
        assertFalse(contains(layer, activity.getString(R.string.porta_reconnecting)));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        assertTrue(contains(layer, activity.getString(R.string.porta_reconnecting)));
        ui.close();
        controller.pause().stop().destroy();
    }

    @Test public void fastReconnectDoesNotFlashAStaleCard() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.Theme_Porta);
        controller.setup();
        FrameLayout layer = new FrameLayout(activity);
        activity.setContentView(layer);
        SessionUi ui = new SessionUi(activity, layer, new SurfaceView(activity), "torre", () -> {}, () -> {}, () -> {});
        ui.reconnecting(null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        ui.hide();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertFalse(ui.isShowing());
        assertFalse(contains(layer, activity.getString(R.string.porta_reconnecting)));
        assertEquals(View.GONE, layer.getVisibility());
        ui.close();
        controller.pause().stop().destroy();
    }
}
