package dev.tongt.mcserverauth.security;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;

public final class PasswordHasher {
    /** BCrypt only uses the first 72 bytes; longer input is refused instead of silently truncated. */
    public static final int MAX_BYTES = 72;

    private final int cost;

    public PasswordHasher(int cost) {
        if (cost < 4 || cost > 31) {
            throw new IllegalArgumentException("BCrypt cost must be between 4 and 31");
        }
        this.cost = cost;
    }

    public String hash(String password) {
        return BCrypt.withDefaults().hashToString(cost, password.toCharArray());
    }

    public boolean verify(String password, String hash) {
        if (hash == null || hash.isEmpty()) {
            return false;
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return false;
        }
        try {
            return BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
