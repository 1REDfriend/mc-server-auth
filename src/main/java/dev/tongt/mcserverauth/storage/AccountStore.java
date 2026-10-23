package dev.tongt.mcserverauth.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Optional;

/** SQLite-backed account storage. Thread-safe; call it off the main server thread. */
public final class AccountStore implements AutoCloseable {
    private static final String SELECT_ACCOUNT = """
            SELECT name_lower, name_display, password_hash, email, created_at, last_login_at, last_ip
            FROM accounts WHERE name_lower = ?""";

    private final Connection connection;

    private AccountStore(Connection connection) {
        this.connection = connection;
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static AccountStore open(Path file) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite driver not found", e);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS accounts (
                      name_lower    TEXT PRIMARY KEY,
                      name_display  TEXT NOT NULL,
                      password_hash TEXT NOT NULL,
                      email         TEXT NOT NULL,
                      created_at    INTEGER NOT NULL,
                      last_login_at INTEGER,
                      last_ip       TEXT
                    )""");
            statement.execute("CREATE INDEX IF NOT EXISTS accounts_email ON accounts(email COLLATE NOCASE)");
            statement.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return new AccountStore(connection);
    }

    public synchronized Optional<Account> find(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_ACCOUNT)) {
            ps.setString(1, key(name));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long lastLogin = rs.getLong(6);
                Long lastLoginAt = rs.wasNull() ? null : lastLogin;
                return Optional.of(new Account(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), lastLoginAt, rs.getString(7)));
            }
        }
    }

    public synchronized void create(String displayName, String passwordHash, String email, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO accounts (name_lower, name_display, password_hash, email, created_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, key(displayName));
            ps.setString(2, displayName);
            ps.setString(3, passwordHash);
            ps.setString(4, email);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    /**
     * Creates the account only if fewer than {@code limit} accounts use this email (ignoring case). The count and
     * the insert run in one transaction under this store's lock, so concurrent registrations cannot both pass.
     *
     * @return false, with nothing written, when the email already has {@code limit} accounts
     */
    public synchronized boolean createIfEmailBelow(String displayName, String passwordHash, String email, long now,
                                                   int limit) throws SQLException {
        connection.setAutoCommit(false);
        try {
            if (countByEmail(email) >= limit) {
                connection.rollback();
                return false;
            }
            create(displayName, passwordHash, email, now);
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public enum EmailUpdate { OK, LIMIT, NO_ACCOUNT }

    /**
     * Sets the account's email if fewer than {@code limit} other accounts use it (ignoring case). Like
     * {@link #createIfEmailBelow}, the count and the update are one transaction under this store's lock.
     */
    public synchronized EmailUpdate updateEmailIfBelow(String name, String email, int limit) throws SQLException {
        connection.setAutoCommit(false);
        try {
            int others;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM accounts WHERE email = ? COLLATE NOCASE AND name_lower <> ?")) {
                ps.setString(1, email);
                ps.setString(2, key(name));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    others = rs.getInt(1);
                }
            }
            if (others >= limit) {
                connection.rollback();
                return EmailUpdate.LIMIT;
            }
            int updated;
            try (PreparedStatement ps = connection.prepareStatement("UPDATE accounts SET email = ? WHERE name_lower = ?")) {
                ps.setString(1, email);
                ps.setString(2, key(name));
                updated = ps.executeUpdate();
            }
            connection.commit();
            return updated > 0 ? EmailUpdate.OK : EmailUpdate.NO_ACCOUNT;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized boolean updatePassword(String name, String passwordHash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE accounts SET password_hash = ? WHERE name_lower = ?")) {
            ps.setString(1, passwordHash);
            ps.setString(2, key(name));
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized void recordLogin(String name, long now, String ip) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE accounts SET last_login_at = ?, last_ip = ? WHERE name_lower = ?")) {
            ps.setLong(1, now);
            ps.setString(2, ip);
            ps.setString(3, key(name));
            ps.executeUpdate();
        }
    }

    public synchronized boolean delete(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM accounts WHERE name_lower = ?")) {
            ps.setString(1, key(name));
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized int countByEmail(String email) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM accounts WHERE email = ? COLLATE NOCASE")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public synchronized Optional<String> serverPasswordHash() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM settings WHERE key = 'server_password_hash'")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    public synchronized void setServerPasswordHash(String hash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO settings (key, value) VALUES ('server_password_hash', ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value""")) {
            ps.setString(1, hash);
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
