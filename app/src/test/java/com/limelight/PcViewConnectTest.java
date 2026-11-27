package com.limelight;

import com.google.android.material.button.MaterialButton;
import com.limelight.computers.ComputerManagerService.ComputerManagerBinder;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager.PairState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PcViewConnectTest {
    @Test public void connectUsesLiveUnpairedStateInsteadOfTheStaleSheetSnapshot() {
        PcView home = mock(PcView.class);
        ComputerManagerBinder binder = mock(ComputerManagerBinder.class);
        ReflectionHelpers.setField(home, "managerBinder", binder);
        ComputerDetails snapshot = new ComputerDetails();
        snapshot.uuid = "host";
        snapshot.state = ComputerDetails.State.ONLINE;
        snapshot.pairState = PairState.PAIRED;
        snapshot.activeAddress = new ComputerDetails.AddressTuple("127.0.0.1", 47989);
        ComputerDetails live = new ComputerDetails(snapshot);
        live.pairState = PairState.NOT_PAIRED;
        when(binder.getComputer("host")).thenReturn(live);
        doCallRealMethod().when(home).connectDesktop(any(), any(), any(), any());
        MaterialButton button = mock(MaterialButton.class);
        Runnable dismiss = mock(Runnable.class);
        home.connectDesktop(snapshot, () -> false, button, dismiss);
        verify(dismiss).run();
        verify(home).doPair(live);
        verifyNoInteractions(button);
    }

    @Test public void connectKeepsUnknownHostInSheetUntilDiscoveryCompletes() {
        PcView home = mock(PcView.class);
        ComputerManagerBinder binder = mock(ComputerManagerBinder.class);
        ReflectionHelpers.setField(home, "managerBinder", binder);
        ComputerDetails host = new ComputerDetails();
        host.uuid = "host";
        host.state = ComputerDetails.State.UNKNOWN;
        when(binder.getComputer("host")).thenReturn(host);
        doCallRealMethod().when(home).connectDesktop(any(), any(), any(), any());
        MaterialButton button = mock(MaterialButton.class);
        Runnable dismiss = mock(Runnable.class);

        home.connectDesktop(host, () -> false, button, dismiss);

        verify(button).setEnabled(false);
        verifyNoInteractions(dismiss);
        verify(home, never()).doPair(any());
    }

    @Test public void stalePairingClientCannotReplaceTheCurrentCancellationTarget() {
        PcView home = Robolectric.buildActivity(PcView.class).get();
        NvHTTP stale = mock(NvHTTP.class);
        NvHTTP current = mock(NvHTTP.class);
        // Attempt 1 was cancelled while constructing its client; attempt 3 published first.
        ReflectionHelpers.setField(home, "pairGeneration", 3);
        assertTrue(home.publishPairingHttp(3, current));
        assertFalse(home.publishPairingHttp(1, stale));
        verify(stale).cancelPendingRequests();
        verifyNoInteractions(current);

        ReflectionHelpers.callInstanceMethod(home, "cancelPairing");

        verify(current).cancelPendingRequests();
        verifyNoMoreInteractions(stale, current);
        assertNull(ReflectionHelpers.getField(home, "pairingHttp"));
    }
}
