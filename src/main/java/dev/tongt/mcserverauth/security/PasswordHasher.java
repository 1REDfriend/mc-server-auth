package dev.tongt.mcserverauth.security;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;

public final class PasswordHasher {
    /** BCrypt only uses the first 72 bytes; longer input is refused instead of silently truncated. */
    public static final int MAX_BYTES = 72;

    private final int cost;

    public PasswordHasher(int cost) {
        this.cost = cost;
    }

    public String hash(String password) {
        return BCrypt.withDefaults().hashToString(cost, password.toCharArray());
    }

    public boolean verify(String password, String hash) {
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
