package dev.tongt.mcserverauth.security;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Remembers recent successful logins per (name, IP) so a quick reconnect skips the dialogs. Off by default. */
public final class SessionCache {
    private record Key(String nameLower, InetAddress ip) {}

    private final Clock clock;
    private final boolean enabled;
    private final Duration ttl;
    private final Map<Key, Instant> entries = new ConcurrentHashMap<>();

    public SessionCache(Clock clock, boolean enabled, Duration ttl) {
        this.clock = clock;
        this.enabled = enabled;
        this.ttl = ttl;
    }

    public void remember(String nameLower, InetAddress ip) {
        if (enabled) {
            entries.put(new Key(nameLower, ip), clock.instant());
        }
    }

    public boolean isRemembered(String nameLower, InetAddress ip) {
        if (!enabled) {
            return false;
        }
        Key key = new Key(nameLower, ip);
        Instant at = entries.get(key);
        if (at == null) {
            return false;
        }
        if (clock.instant().isAfter(at.plus(ttl))) {
            entries.remove(key);
            return false;
        }
        return true;
    }

    public void forget(String nameLower) {
        entries.keySet().removeIf(key -> key.nameLower().equals(nameLower));
    }
}
