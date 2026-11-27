package com.limelight.computers;

import static org.junit.Assert.*;

import com.limelight.nvstream.http.ComputerDetails.AddressTuple;
import org.junit.Test;

public class RemoteAddressTest {
    @Test public void publicLiteralsAndHostnamesDefaultTo47989WithoutDns() {
        for (String host : new String[]{"203.0.113.10", "2001:db8::10", "desktop.example.invalid"}) {
            assertEquals(new AddressTuple(host, 47989), RemoteAddress.parse("  " + host + "  "));
        }
    }

    @Test public void optionalPortsAcceptBothBoundsAndBracketedIpv6() {
        assertEquals(new AddressTuple("203.0.113.10", 1), RemoteAddress.parse("203.0.113.10:1"));
        assertEquals(new AddressTuple("desktop.example.invalid", 65535),
                RemoteAddress.parse("desktop.example.invalid:65535"));
        assertEquals(new AddressTuple("2001:db8::10", 48089), RemoteAddress.parse("[2001:db8::10]:48089"));
        assertEquals(new AddressTuple("2001:db8::10", 47989), RemoteAddress.parse("[2001:db8::10]"));
        assertEquals(new AddressTuple("2001:db8::abcd", 47989), RemoteAddress.parse("2001:db8::abcd"));
    }

    @Test public void malformedAddressesAndPortsAreRejected() {
        for (String value : new String[]{"", "203.0.113.10:", "203.0.113.10:0", "203.0.113.10:65536",
                "203.0.113.10:-1", "203.0.113.10:abc", "256.0.0.1", "203.0.113", "203.00.113.10",
                "[203.0.113.10]:47989", "[2001:db8::10", "[2001:db8::10]junk", "2001::db8::10",
                "https://desktop.example.com", "bad name.example.com", "-bad.example.com", "host..example",
                "host.example.com/path", "host.example.com: 47989", "[fe80::1%wlan0]:47989"}) {
            reject(value, false);
        }
    }

    @Test public void privateLocalAndNonUnicastAddressesAreRejected() {
        for (String value : new String[]{"0.0.0.0", "10.0.0.1", "172.16.0.1", "172.31.255.255",
                "192.168.1.1", "169.254.1.1", "127.0.0.1", "100.64.0.1", "100.127.255.255",
                "224.0.0.1", "255.255.255.255", "198.18.0.1", "::", "::1", "fc00::1", "fd12::1",
                "fe80::1", "fec0::1", "ff02::1", "::ffff:192.168.1.1", "::192.168.1.1",
                "localhost", "desktop.local", "desktop.lan", "desktop.home", "desktop.home.arpa",
                "desktop.localhost.", "desktop.internal", "desktop.localdomain", "desktop.example.ts.net"}) {
            reject(value, true);
        }
    }

    @Test public void publicAddressesBesidePrivateRangeBoundariesAreAccepted() {
        for (String value : new String[]{"100.63.255.255", "100.128.0.1", "172.15.255.255", "172.32.0.1",
                "192.169.1.1", "::ffff:203.0.113.10", "Desktop.Example.COM."}) {
            assertNotNull(RemoteAddress.parse(value));
        }
    }

    private void reject(String value, boolean local) {
        try {
            RemoteAddress.parse(value);
            fail("Accepted " + value);
        } catch (RemoteAddress.InvalidAddressException e) {
            assertEquals(value, local, e.localAddress);
        }
    }
}
