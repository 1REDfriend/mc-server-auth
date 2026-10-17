package dev.tongt.mcserverauth.security;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Counts password failures per IP and issues temporary bans. In-memory only. */
public final class AttemptLimiter {
    private final Clock clock;
    private final int failuresBeforeBan;
    private final Duration window;
    private final Duration banDuration;
    private final Map<InetAddress, Deque<Instant>> recentFailures = new HashMap<>();
    private final Map<InetAddress, Instant> bannedUntil = new HashMap<>();

    public AttemptLimiter(Clock clock, int failuresBeforeBan, Duration window, Duration banDuration) {
        this.clock = clock;
        this.failuresBeforeBan = failuresBeforeBan;
        this.window = window;
        this.banDuration = banDuration;
    }

    public synchronized void recordFailure(InetAddress ip) {
        Instant now = clock.instant();
        Deque<Instant> failures = recentFailures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        failures.addLast(now);
        while (!failures.isEmpty() && failures.peekFirst().isBefore(now.minus(window))) {
            failures.removeFirst();
        }
        if (failures.size() >= failuresBeforeBan) {
            bannedUntil.put(ip, now.plus(banDuration));
            recentFailures.remove(ip);
        }
    }

    public synchronized Optional<Duration> banRemaining(InetAddress ip) {
        Instant until = bannedUntil.get(ip);
        if (until == null) {
            return Optional.empty();
        }
        Duration left = Duration.between(clock.instant(), until);
        if (left.isNegative() || left.isZero()) {
            bannedUntil.remove(ip);
            return Optional.empty();
        }
        return Optional.of(left);
    }
}
