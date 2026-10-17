package dev.tongt.mcserverauth.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Counts password failures per IP key (see {@link IpKey}) and issues temporary bans. In-memory only. */
public final class AttemptLimiter {
    private final Clock clock;
    private final int failuresBeforeBan;
    private final Duration window;
    private final Duration banDuration;
    private final Map<String, Deque<Instant>> recentFailures = new HashMap<>();
    private final Map<String, Instant> bannedUntil = new HashMap<>();

    public AttemptLimiter(Clock clock, int failuresBeforeBan, Duration window, Duration banDuration) {
        this.clock = clock;
        this.failuresBeforeBan = failuresBeforeBan;
        this.window = window;
        this.banDuration = banDuration;
    }

    public synchronized void recordFailure(String ip) {
        Instant now = clock.instant();
        prune(now);
        Deque<Instant> failures = recentFailures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        failures.addLast(now);
        if (failures.size() >= failuresBeforeBan) {
            bannedUntil.put(ip, now.plus(banDuration));
            recentFailures.remove(ip);
        }
    }

    /** Drops failures outside the window, addresses with none left, and expired bans. Cheap at this scale. */
    private void prune(Instant now) {
        Instant cutoff = now.minus(window);
        recentFailures.values().removeIf(failures -> {
            while (!failures.isEmpty() && failures.peekFirst().isBefore(cutoff)) {
                failures.removeFirst();
            }
            return failures.isEmpty();
        });
        bannedUntil.values().removeIf(until -> !now.isBefore(until));
    }

    /** Number of addresses with failures or bans held in memory; for tests. */
    synchronized int trackedAddresses() {
        return recentFailures.size() + bannedUntil.size();
    }

    public synchronized Optional<Duration> banRemaining(String ip) {
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
