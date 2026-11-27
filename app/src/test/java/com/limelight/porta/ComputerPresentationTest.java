package com.limelight.porta;

import static org.junit.Assert.*;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager.PairState;

import org.junit.Test;

import java.util.*;

public class ComputerPresentationTest {
    private ComputerDetails host(ComputerDetails.State state, PairState pair, long used) {
        ComputerDetails c = new ComputerDetails();
        c.name = "Test computer";
        c.uuid = "test-device";
        c.state = state;
        c.pairState = pair;
        c.lastUsed = used;
        return c;
    }

    @Test
    public void knownReachabilityTakesPriorityOverStalePairState() {
        assertEquals(
                ComputerPresentation.Status.OFFLINE,
                ComputerPresentation.status(
                        host(ComputerDetails.State.OFFLINE, PairState.PAIRED, 0)));
        assertEquals(
                ComputerPresentation.Status.REFRESHING,
                ComputerPresentation.status(
                        host(ComputerDetails.State.UNKNOWN, PairState.PAIRED, 0)));
    }

    @Test
    public void onlineUnpairedHostsOpenPairing() {
        assertEquals(
                ComputerPresentation.Status.UNPAIRED,
                ComputerPresentation.status(
                        host(ComputerDetails.State.ONLINE, PairState.NOT_PAIRED, 0)));
        assertEquals(
                ComputerPresentation.Status.UNPAIRED,
                ComputerPresentation.status(host(ComputerDetails.State.ONLINE, null, 0)));
        assertEquals(
                ComputerPresentation.Status.ONLINE,
                ComputerPresentation.status(
                        host(ComputerDetails.State.ONLINE, PairState.PAIRED, 0)));
    }

    @Test
    public void onlineFirstThenMostRecentlyUsedWithinGroups() {
        ComputerDetails offline = host(ComputerDetails.State.OFFLINE, PairState.PAIRED, 999),
                older = host(ComputerDetails.State.ONLINE, PairState.PAIRED, 10),
                newer = host(ComputerDetails.State.ONLINE, PairState.PAIRED, 50),
                unknown = host(ComputerDetails.State.UNKNOWN, null, 200);
        List<ComputerDetails> list = new ArrayList<>(Arrays.asList(offline, older, unknown, newer));
        list.sort(ComputerPresentation.ORDER);
        assertEquals(Arrays.asList(newer, older, offline, unknown), list);
    }

    @Test
    public void tailscaleUsesTheWholeCgnatRangeAndMagicDns() {
        for (String address :
                new String[] {
                    "100.64.0.1", "100.127.255.254", "example.test.ts.net", "fd7a:115c:a1e0::1"
                }) {
            ComputerDetails c = new ComputerDetails();
            c.activeAddress = new ComputerDetails.AddressTuple(address, 1);
            assertEquals(ComputerPresentation.Route.TAILSCALE, ComputerPresentation.route(c));
        }
        for (String address :
                new String[] {
                    "100.63.255.254", "100.128.0.1", "100.invalid.test", "example.test"
                }) {
            ComputerDetails c = new ComputerDetails();
            c.activeAddress = new ComputerDetails.AddressTuple(address, 1);
            assertEquals(ComputerPresentation.Route.REMOTE, ComputerPresentation.route(c));
        }
    }

    @Test
    public void localRouteComparesAddressAndPortRatherThanIdentity() {
        ComputerDetails c = new ComputerDetails();
        c.localAddress = new ComputerDetails.AddressTuple("192.0.2.1", 1);
        c.activeAddress = new ComputerDetails.AddressTuple("192.0.2.1", 1);
        assertEquals(ComputerPresentation.Route.LOCAL, ComputerPresentation.route(c));
    }

    @Test
    public void unknownOrZeroMacCannotWake() {
        ComputerDetails c = new ComputerDetails();
        assertFalse(ComputerPresentation.hasMac(c));
        c.macAddress = "00:00:00:00:00:00";
        assertFalse(ComputerPresentation.hasMac(c));
        c.macAddress = "00:00:00:00:00:01";
        assertTrue(ComputerPresentation.hasMac(c));
    }

    @Test
    public void pollingUpdatesDoNotEraseLastUse() {
        ComputerDetails saved = host(ComputerDetails.State.UNKNOWN, null, 500);
        saved.update(host(ComputerDetails.State.ONLINE, PairState.PAIRED, 0));
        assertEquals(500, saved.lastUsed);
        ComputerDetails copy = new ComputerDetails(saved);
        assertEquals(500, copy.lastUsed);
    }
}
