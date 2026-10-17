package dev.tongt.mcserverauth.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {

    @Test
    void toStringHidesHashAndEmail() {
        Account account = new Account("steve", "Steve", "$2a$12$secrethashvalue", "steve@example.com", 1L, null, null);
        String text = account.toString();
        assertTrue(text.contains("steve"));
        assertTrue(text.contains("Steve"));
        assertFalse(text.contains("secrethashvalue"));
        assertFalse(text.contains("example.com"));
    }
}
