package dev.tongt.mcserverauth.security;

import dev.tongt.mcserverauth.testutil.MutableClock;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SessionCacheTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
    private final InetAddress ip = InetAddress.getLoopbackAddress();

    @Test
    void disabledNeverRemembers() {
        SessionCache cache = new SessionCache(clock, false, Duration.ofMinutes(5));
        cache.remember("steve", ip);
        assertFalse(cache.isRemembered("steve", ip));
    }

    @Test
    void enabledRemembersUntilExpiry() {
        SessionCache cache = new SessionCache(clock, true, Duration.ofMinutes(5));
        cache.remember("steve", ip);
        assertTrue(cache.isRemembered("steve", ip));
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertFalse(cache.isRemembered("steve", ip));
    }

    @Test
    void differentIpIsNotRemembered() throws UnknownHostException {
        SessionCache cache = new SessionCache(clock, true, Duration.ofMinutes(5));
        cache.remember("steve", ip);
        assertFalse(cache.isRemembered("steve", InetAddress.getByName("10.0.0.2")));
    }

    @Test
    void forgetRemovesAllEntriesForName() {
        SessionCache cache = new SessionCache(clock, true, Duration.ofMinutes(5));
        cache.remember("steve", ip);
        cache.forget("steve");
        assertFalse(cache.isRemembered("steve", ip));
    }
}
