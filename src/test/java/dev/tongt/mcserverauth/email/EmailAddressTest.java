package dev.tongt.mcserverauth.email;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EmailAddressTest {

    @Test
    void parsesAndLowercasesDomainOnly() {
        assertEquals("Tong.T@gmail.com", EmailAddress.parse("  Tong.T@GMail.COM ").orElseThrow().value());
    }

    @Test
    void rejectsBadShapes() {
        assertEquals(Optional.empty(), EmailAddress.parse(""));
        assertEquals(Optional.empty(), EmailAddress.parse(null));
        assertEquals(Optional.empty(), EmailAddress.parse("no-at-sign"));
        assertEquals(Optional.empty(), EmailAddress.parse("a@b"));
        assertEquals(Optional.empty(), EmailAddress.parse("a@@b.com"));
        assertEquals(Optional.empty(), EmailAddress.parse("a b@c.com"));
    }

    @Test
    void rejectsHeaderAndListTricks() {
        assertEquals(Optional.empty(), EmailAddress.parse("\"x\"<v@evil.com>"));
        assertEquals(Optional.empty(), EmailAddress.parse("a,b@c.com"));
        assertEquals(Optional.empty(), EmailAddress.parse("a@b..com"));
        assertEquals(Optional.empty(), EmailAddress.parse(".a@b.com"));
        assertEquals(Optional.empty(), EmailAddress.parse("a.@b.com"));
        assertEquals(Optional.empty(), EmailAddress.parse("a..b@c.com"));
        assertEquals(Optional.empty(), EmailAddress.parse("a@-b.com"));
        assertEquals(Optional.empty(), EmailAddress.parse("a@b-.com"));
        assertEquals(Optional.empty(), EmailAddress.parse("a@b.c"));
        assertEquals(Optional.empty(), EmailAddress.parse("a@b.c0m"));
    }

    @Test
    void acceptsDottedPlusAddressWithSubdomain() {
        assertEquals("first.last+tag@sub.example.co",
                EmailAddress.parse("first.last+tag@sub.example.co").orElseThrow().value());
    }

    @Test
    void rejectsTooLong() {
        assertEquals(Optional.empty(), EmailAddress.parse("a".repeat(65) + "@example.com"));
        assertEquals(Optional.empty(), EmailAddress.parse("a@" + "b".repeat(250) + ".com"));
    }

    @Test
    void masksLocalPart() {
        assertEquals("t***@gmail.com", new EmailAddress("tongt@gmail.com").masked());
    }
}
