package dev.tongt.mcserverauth.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AccountStoreTest {
    @TempDir
    Path dir;
    private AccountStore store;

    @BeforeEach
    void open() throws SQLException {
        store = AccountStore.open(dir.resolve("accounts.db"));
    }

    @AfterEach
    void close() throws SQLException {
        store.close();
    }

    @Test
    void createAndFindIgnoringCase() throws SQLException {
        store.create("Steve", "hash1", "steve@example.com", 1000L);
        Account account = store.find("STEVE").orElseThrow();
        assertEquals("steve", account.nameLower());
        assertEquals("Steve", account.displayName());
        assertEquals("hash1", account.passwordHash());
        assertEquals("steve@example.com", account.email());
        assertEquals(1000L, account.createdAt());
        assertNull(account.lastLoginAt());
    }

    @Test
    void accountWithoutEmail() throws SQLException {
        store.create("Alex", "hash1", "", 1000L);
        Account account = store.find("alex").orElseThrow();
        assertFalse(account.hasEmail());
        assertEquals(0, store.countByEmail("steve@example.com"));
        store.create("Steve", "hash2", "steve@example.com", 1000L);
        assertTrue(store.find("steve").orElseThrow().hasEmail());
    }

    @Test
    void missingAccountIsEmpty() throws SQLException {
        assertEquals(Optional.empty(), store.find("nobody"));
    }

    @Test
    void duplicateNameIsRejected() throws SQLException {
        store.create("Steve", "hash1", "steve@example.com", 1000L);
        assertThrows(SQLException.class, () -> store.create("steve", "hash2", "x@example.com", 2000L));
    }

    @Test
    void updatePasswordAndRecordLogin() throws SQLException {
        store.create("Steve", "hash1", "steve@example.com", 1000L);
        assertTrue(store.updatePassword("steve", "hash2"));
        store.recordLogin("Steve", 5000L, "127.0.0.1");
        Account account = store.find("steve").orElseThrow();
        assertEquals("hash2", account.passwordHash());
        assertEquals(5000L, account.lastLoginAt());
        assertEquals("127.0.0.1", account.lastIp());
    }

    @Test
    void deleteAndCountByEmail() throws SQLException {
        store.create("A", "h", "same@example.com", 1L);
        store.create("B", "h", "same@example.com", 1L);
        assertEquals(2, store.countByEmail("same@example.com"));
        assertTrue(store.delete("a"));
        assertFalse(store.delete("a"));
        assertEquals(1, store.countByEmail("same@example.com"));
    }

    @Test
    void countByEmailIgnoresCase() throws SQLException {
        store.create("A", "h", "Same@example.com", 1L);
        store.create("B", "h", "same@example.com", 1L);
        assertEquals(2, store.countByEmail("SAME@example.com"));
    }

    @Test
    void createIfEmailBelowRespectsTheLimitIgnoringCase() throws SQLException {
        assertTrue(store.createIfEmailBelow("A", "h", "Same@example.com", 1L, 2));
        assertTrue(store.createIfEmailBelow("B", "h", "same@example.com", 1L, 2));
        assertFalse(store.createIfEmailBelow("C", "h", "SAME@example.com", 1L, 2));
        assertTrue(store.find("c").isEmpty());
        assertEquals(2, store.countByEmail("same@example.com"));
        assertTrue(store.createIfEmailBelow("D", "h", "other@example.com", 1L, 2));
    }

    @Test
    void createIfEmailBelowRejectsDuplicateNameAndStaysUsable() throws SQLException {
        assertTrue(store.createIfEmailBelow("Steve", "h", "a@example.com", 1L, 3));
        assertThrows(SQLException.class, () -> store.createIfEmailBelow("steve", "h", "b@example.com", 1L, 3));
        assertEquals(0, store.countByEmail("b@example.com"));
        store.create("Alex", "h", "c@example.com", 1L);
        assertTrue(store.find("alex").isPresent());
    }

    @Test
    void updateEmailIfBelowSetsTheEmail() throws SQLException {
        store.create("Alex", "h", "", 1L);
        assertEquals(AccountStore.EmailUpdate.OK, store.updateEmailIfBelow("alex", "alex@example.com", 3));
        assertEquals("alex@example.com", store.find("alex").orElseThrow().email());
    }

    @Test
    void updateEmailIfBelowRespectsTheLimitIgnoringCase() throws SQLException {
        store.create("A", "h", "same@example.com", 1L);
        store.create("B", "h", "Same@example.com", 1L);
        store.create("C", "h", "", 1L);
        assertEquals(AccountStore.EmailUpdate.LIMIT, store.updateEmailIfBelow("c", "SAME@example.com", 2));
        assertFalse(store.find("c").orElseThrow().hasEmail());
    }

    @Test
    void updateEmailIfBelowDoesNotCountTheAccountItself() throws SQLException {
        store.create("A", "h", "same@example.com", 1L);
        store.create("B", "h", "same@example.com", 1L);
        // B only changes the letter case of its own address: still two accounts on it.
        assertEquals(AccountStore.EmailUpdate.OK, store.updateEmailIfBelow("b", "Same@example.com", 2));
    }

    @Test
    void updateEmailIfBelowReportsAMissingAccount() throws SQLException {
        assertEquals(AccountStore.EmailUpdate.NO_ACCOUNT, store.updateEmailIfBelow("ghost", "g@example.com", 3));
    }

    @Test
    void serverPasswordHashRoundTrip() throws SQLException {
        assertEquals(Optional.empty(), store.serverPasswordHash());
        store.setServerPasswordHash("h1");
        store.setServerPasswordHash("h2");
        assertEquals(Optional.of("h2"), store.serverPasswordHash());
    }

    @Test
    void dataSurvivesReopen() throws SQLException {
        store.create("Steve", "hash1", "steve@example.com", 1000L);
        store.close();
        store = AccountStore.open(dir.resolve("accounts.db"));
        assertTrue(store.find("steve").isPresent());
    }
}
