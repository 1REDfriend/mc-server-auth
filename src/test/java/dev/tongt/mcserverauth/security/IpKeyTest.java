package dev.tongt.mcserverauth.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class IpKeyTest {
    @Test
    void ipv4IsUnchanged() throws UnknownHostException {
        assertEquals("10.0.0.7", IpKey.of(InetAddress.getByName("10.0.0.7")));
    }

    @Test
    void ipv6InTheSame64ShareAKey() throws UnknownHostException {
        assertEquals(IpKey.of(InetAddress.getByName("2001:db8:1:2::1")),
                IpKey.of(InetAddress.getByName("2001:db8:1:2:aaaa:bbbb:cccc:dddd")));
    }

    @Test
    void ipv6InDifferent64Differ() throws UnknownHostException {
        assertNotEquals(IpKey.of(InetAddress.getByName("2001:db8:1:2::1")),
                IpKey.of(InetAddress.getByName("2001:db8:1:3::1")));
    }

    @Test
    void ipv4AndIpv6KeysDiffer() throws UnknownHostException {
        assertNotEquals(IpKey.of(InetAddress.getByName("10.0.0.7")),
                IpKey.of(InetAddress.getByName("2001:db8:1:2::1")));
    }
}
