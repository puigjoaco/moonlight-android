package com.limelight.porta;

import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.button.MaterialButton;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager.PairState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.function.BooleanSupplier;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ConnectSheetTest {
    @Test public void cameraChoiceIsReadAfterTheDesktopLookup() {
        PcView home = spy(Robolectric.buildActivity(PcView.class).get());
        ComputerDetails host = new ComputerDetails();
        host.state = ComputerDetails.State.ONLINE;
        host.pairState = PairState.PAIRED;
        host.activeAddress = new ComputerDetails.AddressTuple("127.0.0.1", 47989);
        doNothing().when(home).connectDesktop(any(), any(), any(), any());
        ConnectSheet sheet = createSheet(home, host);
        View content = sheet.onCreateView(LayoutInflater.from(home), null, null);
        MaterialButton camera = findButton(content, home.getString(R.string.porta_camera));
        MaterialButton connect = ReflectionHelpers.getField(sheet, "connectButton");
        camera.performClick();
        connect.performClick();
        ArgumentCaptor<BooleanSupplier> selection = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(home).connectDesktop(eq(host), selection.capture(), eq(connect), any());
        assertTrue(selection.getValue().getAsBoolean());

        camera.performClick();

        assertFalse(selection.getValue().getAsBoolean());
    }

    @Test public void connectEnablesOnlyAfterReachabilityAndPairingResolve() {
        PcView home = spy(Robolectric.buildActivity(PcView.class).get());
        ComputerDetails host = new ComputerDetails();
        host.state = ComputerDetails.State.UNKNOWN;
        ConnectSheet sheet = createSheet(home, host);
        sheet.onCreateView(LayoutInflater.from(home), null, null);
        MaterialButton connect = ReflectionHelpers.getField(sheet, "connectButton");
        assertFalse(connect.isEnabled());
        host.state = ComputerDetails.State.ONLINE;
        sheet.refreshConnectionState();
        assertFalse(connect.isEnabled());
        host.activeAddress = new ComputerDetails.AddressTuple("127.0.0.1", 47989);
        sheet.refreshConnectionState();
        assertFalse(connect.isEnabled());
        host.pairState = PairState.NOT_PAIRED;
        sheet.refreshConnectionState();
        assertTrue(connect.isEnabled());
        // Polling must preserve a disabled button during an asynchronous Desktop lookup.
        connect.setEnabled(false);
        host.pairState = PairState.PAIRED;
        sheet.refreshConnectionState();
        assertFalse(connect.isEnabled());
    }

    private ConnectSheet createSheet(PcView home, ComputerDetails host) {
        home.setTheme(R.style.Theme_Porta);
        host.uuid = "host";
        host.name = "Test host";
        doReturn(host).when(home).getComputer(host.uuid);
        doReturn(host.name).when(home).displayName(host);
        doNothing().when(home).measureLatency(any(), any());
        ConnectSheet sheet = spy(new ConnectSheet());
        doReturn(home).when(sheet).requireActivity();
        doReturn(true).when(sheet).isAdded();
        android.os.Bundle args = new android.os.Bundle();
        args.putString("uuid", host.uuid);
        sheet.setArguments(args);
        return sheet;
    }

    private MaterialButton findButton(View view, String text) {
        if (view instanceof MaterialButton
                && text.contentEquals(((MaterialButton) view).getText())) return (MaterialButton) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                MaterialButton button = findButton(group.getChildAt(i), text);
                if (button != null) return button;
            }
        }
        return null;
    }

    @Test public void shortLandscapeSheetCanScrollToConnectWithNestedScrollingEnabled() {
        for (float fontScale : new float[] {1f, 2f}) {
            assertConnectReachable(fontScale);
        }
    }

    private void assertConnectReachable(float fontScale) {
        PcView home = spy(Robolectric.buildActivity(PcView.class).get());
        android.content.res.Configuration config = new android.content.res.Configuration(
                home.getResources().getConfiguration());
        config.fontScale = fontScale;
        home.getResources().updateConfiguration(config, home.getResources().getDisplayMetrics());
        home.setTheme(R.style.Theme_Porta);
        ComputerDetails host = new ComputerDetails();
        host.uuid = "host";
        host.name = "Test host";
        host.state = ComputerDetails.State.OFFLINE;
        host.pairState = PairState.PAIRED;
        doReturn(host).when(home).getComputer("host");
        doReturn(host.name).when(home).displayName(host);
        ConnectSheet sheet = spy(new ConnectSheet());
        doReturn(home).when(sheet).requireActivity();
        android.os.Bundle args = new android.os.Bundle();
        args.putString("uuid", "host");
        sheet.setArguments(args);
        View content = sheet.onCreateView(LayoutInflater.from(home), null, null);
        assertTrue(content instanceof NestedScrollView);
        NestedScrollView scroll = (NestedScrollView) content;
        assertTrue(scroll.isNestedScrollingEnabled());
        int width = PortaViews.dp(home, 640);
        int height = PortaViews.dp(home, 300);
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        scroll.layout(0, 0, width, height);
        View connect = ReflectionHelpers.getField(sheet, "connectButton");
        assertTrue(scroll.getChildAt(0).getHeight() > height);
        scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
        Rect bounds = new Rect(0, 0, connect.getWidth(), connect.getHeight());
        scroll.offsetDescendantRectToMyCoords(connect, bounds);
        assertTrue(bounds.bottom - scroll.getScrollY() <= height);
        assertTrue(bounds.top - scroll.getScrollY() >= 0);
    }
    @Test public void statusTracksTheSelectedLocalTailscaleAndInternetRoute() {
        PcView home = spy(Robolectric.buildActivity(PcView.class).get());
        ComputerDetails host = new ComputerDetails();
        host.state = ComputerDetails.State.ONLINE;
        host.pairState = PairState.PAIRED;
        host.localAddress = new ComputerDetails.AddressTuple("192.168.4.27", 47989);
        host.activeAddress = host.localAddress;
        ConnectSheet sheet = createSheet(home, host);
        sheet.onCreateView(LayoutInflater.from(home), null, null);
        android.widget.TextView status = ReflectionHelpers.getField(sheet, "routeView");
        assertTrue(status.getText().toString().startsWith(home.getString(R.string.porta_local)));
        host.activeAddress = new ComputerDetails.AddressTuple("torre", 47989);
        host.activeAddressIsTailscale = true;
        sheet.refreshConnectionState();
        assertTrue(status.getText().toString().startsWith(home.getString(R.string.porta_tailscale)));
        host.activeAddress = new ComputerDetails.AddressTuple("203.0.113.10", 47989);
        host.activeAddressIsTailscale = false;
        sheet.refreshConnectionState();
        assertTrue(status.getText().toString().startsWith(home.getString(R.string.porta_internet)));
    }

}
