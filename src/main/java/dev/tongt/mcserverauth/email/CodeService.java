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

/** Issues and checks short-lived 6-digit email codes. In-memory; a restart invalidates all codes. */
public final class CodeService {
    private static final Duration HOUR = Duration.ofHours(1);

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
        int wrongAttempts;

        LiveCode(byte[] salt, byte[] hash, Instant expires) {
            this.salt = salt;
            this.hash = hash;
            this.expires = expires;
        }
    }

    /** What one issue() replaced, so a failed send can be undone exactly. */
    private record Undo(Instant issuedAt, LiveCode previousLive, Instant previousLastSent) {}

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

    public synchronized Issue issue(Purpose purpose, String subject, String ip) {
        Instant now = clock.instant();
        Key key = new Key(purpose, subject);
        Instant last = lastSent.get(key);
        if (last != null && now.isBefore(last.plus(purpose.cooldown))) {
            return new CoolingDown(Duration.between(now, last.plus(purpose.cooldown)));
        }
        Deque<Instant> sends = ipSends.computeIfAbsent(new IpKey(purpose, ip), k -> new ArrayDeque<>());
        while (!sends.isEmpty() && !sends.peekFirst().isAfter(now.minus(HOUR))) {
            sends.removeFirst();
        }
        if (sends.size() >= purpose.sendsPerIpPerHour) {
            return new CoolingDown(Duration.between(now, sends.peekFirst().plus(HOUR)));
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        LiveCode previousLive = live.put(key, new LiveCode(salt, digest(salt, code), now.plus(validity)));
        Instant previousLastSent = lastSent.put(key, now);
        undo.put(key, new Undo(now, previousLive, previousLastSent));
        sends.addLast(now);
        return new Issued(code, now);
    }

    /**
     * Undo one issue() whose email could not be sent: restores the code and cooldown it replaced and frees its
     * IP slot. Does nothing if the issue was already undone, verified, or superseded by a later one.
     */
    public synchronized void cancelIssue(Purpose purpose, String subject, String ip, Issued issued) {
        Key key = new Key(purpose, subject);
        Undo saved = undo.get(key);
        if (saved == null || !saved.issuedAt().equals(issued.issuedAt())
                || !issued.issuedAt().equals(lastSent.get(key))) {
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

    public synchronized Check verify(Purpose purpose, String subject, String code) {
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
        if (MessageDigest.isEqual(liveCode.hash, digest(liveCode.salt, attempt))) {
            live.remove(key);
            undo.remove(key);
            return Check.OK;
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
