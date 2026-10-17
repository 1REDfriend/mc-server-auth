package dev.tongt.mcserverauth.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    // Cost 4 keeps tests fast; production uses 12.
    private final PasswordHasher hasher = new PasswordHasher(4);

    @Test
    void hashIsNotThePassword() {
        assertNotEquals("secret123", hasher.hash("secret123"));
    }

    @Test
    void verifiesCorrectPasswordOnly() {
        String hash = hasher.hash("secret123");
        assertTrue(hasher.verify("secret123", hash));
        assertFalse(hasher.verify("secret124", hash));
    }

    @Test
    void samePasswordGivesDifferentHashes() {
        assertNotEquals(hasher.hash("secret123"), hasher.hash("secret123"));
    }

    @Test
    void overlongInputIsRejectedWithoutThrowing() {
        String hash = hasher.hash("secret123");
        assertFalse(hasher.verify("ก".repeat(64), hash)); // 192 UTF-8 bytes, above BCrypt's 72
    }

    @Test
    void nullOrEmptyHashIsRejectedWithoutThrowing() {
        assertFalse(hasher.verify("secret123", null));
        assertFalse(hasher.verify("secret123", ""));
    }

    @Test
    void costOutsideBcryptRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PasswordHasher(3));
        assertThrows(IllegalArgumentException.class, () -> new PasswordHasher(32));
        assertDoesNotThrow(() -> new PasswordHasher(4));
    }

    @Test
    void malformedHashIsRejectedWithoutThrowing() {
        assertFalse(hasher.verify("secret123", "not-a-hash"));
    }
}
