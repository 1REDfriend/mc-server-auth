package dev.tongt.mcserverauth.security;

import java.net.Inet6Address;
import java.net.InetAddress;

/** Key for IP-based limits: IPv4 as-is, IPv6 grouped by its /64 prefix so one subscriber cannot rotate addresses. */
public final class IpKey {
    private IpKey() {}

    public static String of(InetAddress address) {
        if (address instanceof Inet6Address) {
            byte[] raw = address.getAddress();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", raw[i]));
            }
            return sb.append("::/64").toString();
        }
        return address.getHostAddress();
    }
}
