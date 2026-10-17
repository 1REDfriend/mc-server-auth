package dev.tongt.mcserverauth.security;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PasswordRulesTest {
    private final PasswordRules rules = new PasswordRules(6, 64);

    @Test
    void acceptsValidPassword() {
        assertEquals(Optional.empty(), rules.check("secret123", "secret123", pw -> false));
    }

    @Test
    void rejectsTooShortAndTooLong() {
        assertEquals(Optional.of("error.password-length"), rules.check("abc", "abc", pw -> false));
        String longPw = "a".repeat(65);
        assertEquals(Optional.of("error.password-length"), rules.check(longPw, longPw, pw -> false));
    }

    @Test
    void rejectsMoreThan72Bytes() {
        String thai = "ก".repeat(30); // 30 characters, 90 bytes
        assertEquals(Optional.of("error.password-too-long"), rules.check(thai, thai, pw -> false));
    }

    @Test
    void rejectsMismatch() {
        assertEquals(Optional.of("error.password-mismatch"), rules.check("secret123", "secret321", pw -> false));
    }

    @Test
    void rejectsServerPassword() {
        assertEquals(Optional.of("error.password-same-as-server"),
                rules.check("secret123", "secret123", pw -> pw.equals("secret123")));
    }
}
