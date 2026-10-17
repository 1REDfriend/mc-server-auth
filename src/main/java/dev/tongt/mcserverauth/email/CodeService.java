package dev.tongt.mcserverauth.email;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Issues and checks short-lived 6-digit email codes. In-memory; a restart invalidates all codes. */
public final class CodeService {
    private static final Duration HOUR = Duration.ofHours(1);
    /** Wrong guesses from other IPs allowed per code, as a multiple of maxAttempts, before it is requester-only. */
    private static final int FOREIGN_CAP_FACTOR = 3;

    public enum Purpose {
        REGISTER(Duration.ofSeconds(60), 5),
        RESET(Duration.ofMinutes(15), 3);

        private final Duration cooldown;
        private final int sendsPerIpPerHour;

        Purpose(Duration cooldown, int sendsPerIpPerHour) {
            this.cooldown = cooldown;
            this.sendsPerIpPerHour = sendsPerIpPerHour;
        }
    }

    public sealed interface Issue permits Issued, CoolingDown {}

    public record Issued(String code, Instant issuedAt) implements Issue {}

    public record CoolingDown(Duration remaining) implements Issue {}

    /** OK: accepted and consumed. WRONG: try again. BURNED: this attempt used up the code. NONE: no live code. */
    public enum Check { OK, WRONG, BURNED, NONE }

    private record Key(Purpose purpose, String subject) {}

    private record IpKey(Purpose purpose, String ip) {}

    private static final class LiveCode {
        final byte[] salt;
        final byte[] hash;
        final Instant expires;
        /** Only wrong attempts from this IP count toward burning the code. */
        final String requesterIp;
        int wrongAttempts;
        /** Wrong guesses from other IPs; at the cap the code becomes requester-only. */
        int foreignWrong;

        LiveCode(byte[] salt, byte[] hash, Instant expires, String requesterIp) {
            this.salt = salt;
            this.hash = hash;
            this.expires = expires;
            this.requesterIp = requesterIp;
        }
    }

    /** What one issue() replaced, so a failed send can be undone exactly. */
    private record Undo(Issued issued, LiveCode previousLive, Instant previousLastSent) {}

    private final Clock clock;
    private final SecureRandom random;
    private final Duration validity;
    private final int maxAttempts;
    private final Map<Key, LiveCode> live = new HashMap<>();
    private final Map<Key, Instant> lastSent = new HashMap<>();
    private final Map<Key, Undo> undo = new HashMap<>();
    private final Map<IpKey, Deque<Instant>> ipSends = new HashMap<>();

    public CodeService(Clock clock, SecureRandom random, Duration validity, int maxAttempts) {
        this.clock = clock;
        this.random = random;
        this.validity = validity;
        this.maxAttempts = maxAttempts;
    }

    /**
     * How long until {@link #issue} would succeed for this subject and IP, or empty if it would succeed now.
     * Issues nothing and uses no send slot, so callers can check before doing expensive work.
     */
    public synchronized Optional<Duration> cooldownRemaining(Purpose purpose, String subject, String ip) {
        return cooldown(purpose, subject, ip, clock.instant());
    }

    public synchronized Issue issue(Purpose purpose, String subject, String ip) {
        Instant now = clock.instant();
        prune(now);
        Optional<Duration> wait = cooldown(purpose, subject, ip, now);
        if (wait.isPresent()) {
            return new CoolingDown(wait.get());
        }
        Key key = new Key(purpose, subject);
        Deque<Instant> sends = ipSends.computeIfAbsent(new IpKey(purpose, ip), k -> new ArrayDeque<>());
        String code = String.format("%06d", random.nextInt(1_000_000));
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        LiveCode previousLive = live.put(key, new LiveCode(salt, digest(salt, code), now.plus(validity), ip));
        Instant previousLastSent = lastSent.put(key, now);
        Issued issued = new Issued(code, now);
        undo.put(key, new Undo(issued, previousLive, previousLastSent));
        sends.addLast(now);
        return issued;
    }

    private Optional<Duration> cooldown(Purpose purpose, String subject, String ip, Instant now) {
        Instant last = lastSent.get(new Key(purpose, subject));
        if (last != null && now.isBefore(last.plus(purpose.cooldown))) {
            return Optional.of(Duration.between(now, last.plus(purpose.cooldown)));
        }
        Deque<Instant> sends = ipSends.get(new IpKey(purpose, ip));
        if (sends != null) {
            pruneSends(sends, now);
            if (sends.size() >= purpose.sendsPerIpPerHour) {
                return Optional.of(Duration.between(now, sends.peekFirst().plus(HOUR)));
            }
        }
        return Optional.empty();
    }

    private static void pruneSends(Deque<Instant> sends, Instant now) {
        while (!sends.isEmpty() && !sends.peekFirst().isAfter(now.minus(HOUR))) {
            sends.removeFirst();
        }
    }

    /** Drops state that can no longer affect any decision. A full sweep is cheap at this scale. */
    private void prune(Instant now) {
        live.values().removeIf(c -> !now.isBefore(c.expires));
        lastSent.entrySet().removeIf(e -> !now.isBefore(e.getValue().plus(e.getKey().purpose().cooldown)));
        // An undo is only used right after its send; once the code it issued has expired it is stale.
        undo.values().removeIf(u -> !now.isBefore(u.issued().issuedAt().plus(validity)));
        ipSends.values().removeIf(sends -> {
            pruneSends(sends, now);
            return sends.isEmpty();
        });
    }

    /** Number of entries held in memory; for tests. */
    synchronized int trackedEntries() {
        return live.size() + lastSent.size() + undo.size() + ipSends.size();
    }

    /**
     * Undo one issue() whose email could not be sent: restores the code and cooldown it replaced and frees its
     * IP slot. Does nothing if the issue was already undone, verified, or superseded by a later one.
     */
    public synchronized void cancelIssue(Purpose purpose, String subject, String ip, Issued issued) {
        Key key = new Key(purpose, subject);
        Undo saved = undo.get(key);
        // Identity, not equality: two issues in the same instant (or with the same code) must not match.
        if (saved == null || saved.issued() != issued) {
            return;
        }
        undo.remove(key);
        if (saved.previousLive() == null) {
            live.remove(key);
        } else {
            live.put(key, saved.previousLive());
        }
        if (saved.previousLastSent() == null) {
            lastSent.remove(key);
        } else {
            lastSent.put(key, saved.previousLastSent());
        }
        Deque<Instant> sends = ipSends.get(new IpKey(purpose, ip));
        if (sends != null) {
            sends.removeLastOccurrence(issued.issuedAt());
        }
    }

    /**
     * Checks a code typed from {@code ip}. A wrong attempt burns the code only when it comes from the IP that
     * requested it, so someone else cannot burn a victim's code. Wrong guesses from other IPs are counted
     * separately; after 3 * maxAttempts of them the code is requester-only and any other IP gets WRONG without a
     * hash check, so many IPs cannot brute-force it.
     */
    public synchronized Check verify(Purpose purpose, String subject, String code, String ip) {
        Key key = new Key(purpose, subject);
        String attempt = code == null ? "" : code;
        LiveCode liveCode = live.get(key);
        if (liveCode == null) {
            return Check.NONE;
        }
        if (!clock.instant().isBefore(liveCode.expires)) {
            live.remove(key);
            return Check.NONE;
        }
        boolean requester = Objects.equals(liveCode.requesterIp, ip);
        if (!requester && liveCode.foreignWrong >= FOREIGN_CAP_FACTOR * maxAttempts) {
            return Check.WRONG; // requester-only now; do not even check the hash
        }
        if (MessageDigest.isEqual(liveCode.hash, digest(liveCode.salt, attempt))) {
            live.remove(key);
            undo.remove(key);
            return Check.OK;
        }
        if (!requester) {
            liveCode.foreignWrong++;
            return Check.WRONG;
        }
        liveCode.wrongAttempts++;
        if (liveCode.wrongAttempts >= maxAttempts) {
            live.remove(key);
            return Check.BURNED;
        }
        return Check.WRONG;
    }

    public synchronized boolean hasLiveCode(Purpose purpose, String subject) {
        LiveCode liveCode = live.get(new Key(purpose, subject));
        return liveCode != null && clock.instant().isBefore(liveCode.expires);
    }

    public synchronized void invalidate(String subject) {
        live.keySet().removeIf(key -> key.subject().equals(subject));
        undo.keySet().removeIf(key -> key.subject().equals(subject));
    }

    private static byte[] digest(byte[] salt, String code) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(salt);
            sha.update(code.getBytes(StandardCharsets.UTF_8));
            return sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
