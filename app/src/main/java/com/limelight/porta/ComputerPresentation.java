package com.limelight.porta;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;

import java.util.Comparator;

/** Pure presentation policy shared by rows and the connection sheet. */
public final class ComputerPresentation {
    public enum Status {
        ONLINE,
        UNPAIRED,
        OFFLINE,
        REFRESHING
    }

    public enum Route {
        LOCAL,
        TAILSCALE,
        REMOTE
    }

    private ComputerPresentation() {}

    public static Status status(ComputerDetails c) {
        if (c.state == ComputerDetails.State.OFFLINE) return Status.OFFLINE;
        if (c.state == ComputerDetails.State.UNKNOWN) return Status.REFRESHING;
        return c.pairState == PairingManager.PairState.PAIRED ? Status.ONLINE : Status.UNPAIRED;
    }

    public static Route route(ComputerDetails c) {
        ComputerDetails.AddressTuple a = c.activeAddress;
        if (a == null) a = c.manualAddress != null ? c.manualAddress : c.localAddress;
        if (a == null) return Route.REMOTE;
        if (ComputerDetails.isTailscaleAddress(a.address)
                || (c.activeAddress != null && c.activeAddressIsTailscale)) return Route.TAILSCALE;
        return a.equals(c.localAddress) ? Route.LOCAL : Route.REMOTE;
    }

    public static boolean hasMac(ComputerDetails c) {
        return c.macAddress != null
                && !c.macAddress.isEmpty()
                && !c.macAddress.equals("00:00:00:00:00:00");
    }

    public static final Comparator<ComputerDetails> ORDER =
            Comparator.comparingInt(
                            (ComputerDetails c) -> c.state == ComputerDetails.State.ONLINE ? 0 : 1)
                    .thenComparing(
                            Comparator.comparingLong((ComputerDetails c) -> c.lastUsed).reversed())
                    .thenComparing(c -> c.name == null ? "" : c.name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(c -> c.uuid == null ? "" : c.uuid);
}
