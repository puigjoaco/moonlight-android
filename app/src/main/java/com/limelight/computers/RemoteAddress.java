package com.limelight.computers;

import com.limelight.nvstream.http.ComputerDetails.AddressTuple;
import com.limelight.nvstream.http.NvHTTP;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/** Parses an Internet route without resolving DNS or contacting the computer. */
public final class RemoteAddress {
    private RemoteAddress() {}

    public static final class InvalidAddressException extends IllegalArgumentException {
        public final boolean localAddress;

        private InvalidAddressException(boolean localAddress) {
            super(localAddress ? "Use a public address" : "Invalid address or port");
            this.localAddress = localAddress;
        }
    }

    public static AddressTuple parse(String value) {
        String host = value.trim();
        String portText = null;
        boolean bracketed = host.startsWith("[");
        if (bracketed) {
            int close = host.indexOf(']');
            if (close < 0) throw new InvalidAddressException(false);
            String suffix = host.substring(close + 1);
            host = host.substring(1, close);
            if (!host.contains(":")) throw new InvalidAddressException(false);
            if (!suffix.isEmpty()) {
                if (!suffix.startsWith(":")) throw new InvalidAddressException(false);
                portText = suffix.substring(1);
            }
        } else if (host.indexOf(':') >= 0 && host.indexOf(':') == host.lastIndexOf(':')) {
            int colon = host.indexOf(':');
            portText = host.substring(colon + 1);
            host = host.substring(0, colon);
        }
        int port = NvHTTP.DEFAULT_HTTP_PORT;
        if (portText != null) {
            if (!portText.matches("[0-9]{1,5}")) throw new InvalidAddressException(false);
            port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) throw new InvalidAddressException(false);
        }
        if (host.isEmpty()) throw new InvalidAddressException(false);
        if (host.contains(":")) {
            // Restrict to numeric IPv6 syntax, so getByName cannot perform DNS or accept a zone ID.
            if (!host.matches("[0-9a-fA-F:.]+")) throw new InvalidAddressException(false);
            try {
                InetAddress ip = InetAddress.getByName(host);
                if (ip instanceof Inet4Address) validateIpv4(ip.getHostAddress());
                else if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                        || ip.isSiteLocalAddress() || ip.isMulticastAddress()
                        || (ip.getAddress()[0] & 0xfe) == 0xfc
                        || ((Inet6Address) ip).isIPv4CompatibleAddress()) {
                    throw new InvalidAddressException(true);
                }
            } catch (UnknownHostException e) {
                throw new InvalidAddressException(false);
            }
        } else if (host.matches("[0-9.]+")) {
            validateIpv4(host);
        } else {
            String name = host.toLowerCase(Locale.ROOT);
            if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
            if (name.length() > 253) throw new InvalidAddressException(false);
            for (String label : name.split("\\.", -1)) {
                if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                    throw new InvalidAddressException(false);
                }
            }
            // Obvious local names cannot be used from the Internet. Other hostnames are
            // deliberately not resolved here: saving must also work offline.
            if (!name.contains(".") || name.endsWith(".localhost") || name.endsWith(".local")
                    || name.endsWith(".lan") || name.endsWith(".home")
                    || name.endsWith(".internal") || name.endsWith(".localdomain")
                    || name.endsWith(".home.arpa") || name.endsWith(".ts.net")) {
                throw new InvalidAddressException(true);
            }
        }
        return new AddressTuple(host, port);
    }

    private static void validateIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) throw new InvalidAddressException(false);
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("[0-9]{1,3}") || (parts[i].length() > 1 && parts[i].startsWith("0"))) {
                throw new InvalidAddressException(false);
            }
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) throw new InvalidAddressException(false);
        }
        int a = octets[0], b = octets[1];
        if (a == 0 || a == 10 || a == 127 || a >= 224
                || (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254)
                || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                || (a == 198 && (b == 18 || b == 19))) {
            throw new InvalidAddressException(true);
        }
    }
}
