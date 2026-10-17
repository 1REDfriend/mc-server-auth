package dev.tongt.mcserverauth.security;

import dev.tongt.mcserverauth.testutil.MutableClock;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AttemptLimiterTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
    private final AttemptLimiter limiter =
            new AttemptLimiter(clock, 5, Duration.ofMinutes(10), Duration.ofMinutes(10));
    private final InetAddress ip = InetAddress.getLoopbackAddress();

    @Test
    void bansAfterFailuresInsideWindow() {
        for (int i = 0; i < 4; i++) limiter.recordFailure(ip);
        assertTrue(limiter.banRemaining(ip).isEmpty());
        limiter.recordFailure(ip);
        assertEquals(Duration.ofMinutes(10), limiter.banRemaining(ip).orElseThrow());
    }

    @Test
    void failuresOutsideWindowDoNotCount() {
        for (int i = 0; i < 4; i++) limiter.recordFailure(ip);
        clock.advance(Duration.ofMinutes(11));
        limiter.recordFailure(ip);
        assertTrue(limiter.banRemaining(ip).isEmpty());
    }

    @Test
    void banExpires() {
        for (int i = 0; i < 5; i++) limiter.recordFailure(ip);
        clock.advance(Duration.ofMinutes(10));
        assertTrue(limiter.banRemaining(ip).isEmpty());
    }

    @Test
    void recordFailurePrunesStaleAddresses() throws Exception {
        InetAddress old = InetAddress.getByName("10.0.0.1");
        InetAddress banned = InetAddress.getByName("10.0.0.2");
        limiter.recordFailure(old);
        for (int i = 0; i < 5; i++) limiter.recordFailure(banned);
        assertEquals(2, limiter.trackedAddresses());
        clock.advance(Duration.ofMinutes(11));
        limiter.recordFailure(ip);
        assertEquals(1, limiter.trackedAddresses());
    }
}
