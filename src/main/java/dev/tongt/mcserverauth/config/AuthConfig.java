package dev.tongt.mcserverauth.config;

import dev.tongt.mcserverauth.email.EmailAddress;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.Locale;

public record AuthConfig(
        int maxAttemptsPerConnection,
        int ipBanFailures,
        Duration ipBanWindow,
        Duration ipBanDuration,
        Duration loginTimeout,
        Duration codeWaitTimeout,
        int passwordMinLength,
        int passwordMaxLength,
        boolean sessionRememberEnabled,
        Duration sessionRememberDuration,
        int maxAccountsPerEmail,
        Duration codeValidity,
        int codeMaxAttempts,
        Smtp smtp,
        boolean secretInputLogEnabled,
        int secretInputLogKeepDays) {

    public enum Security { STARTTLS, SSL, NONE }

    public record Smtp(String host, int port, Security security, String username, String password, String from) {
        public boolean configured() {
            return !host.isBlank() && !from.isBlank();
        }
    }

    public static AuthConfig from(ConfigurationSection c) {
        int minLength = positive(c, "password.min-length");
        int maxLength = positive(c, "password.max-length");
        if (minLength > maxLength) {
            throw new IllegalArgumentException("password.min-length must not be greater than password.max-length");
        }
        if (maxLength > 64) {
            throw new IllegalArgumentException("password.max-length must be 64 or less");
        }
        int smtpPort = positive(c, "email.smtp.port");
        if (smtpPort > 65535) {
            throw new IllegalArgumentException("email.smtp.port must be 65535 or less");
        }
        boolean rememberEnabled = flag(c, "session-remember.enabled");
        Smtp smtp = new Smtp(
                c.getString("email.smtp.host", "").trim(),
                smtpPort,
                security(c.getString("email.smtp.security", "starttls")),
                c.getString("email.smtp.username", ""),
                c.getString("email.smtp.password", ""),
                c.getString("email.smtp.from", "").trim());
        if (!smtp.from().isEmpty()) {
            checkFrom(smtp.from());
        }
        return new AuthConfig(
                atMost(c, "max-attempts-per-connection", 20),
                positive(c, "ip-ban.failures"),
                minutes(c, "ip-ban.window-minutes"),
                minutes(c, "ip-ban.ban-minutes"),
                Duration.ofSeconds(positive(c, "login-timeout-seconds")),
                minutes(c, "code-wait-timeout-minutes"),
                minLength,
                maxLength,
                rememberEnabled,
                minutes(c, "session-remember.minutes"),
                positive(c, "email.max-accounts-per-email"),
                minutes(c, "email.code-valid-minutes"),
                atMost(c, "email.code-max-attempts", 10),
                smtp,
                flag(c, "secret-input-log.enabled"),
                positive(c, "secret-input-log.keep-days"));
    }

    /** An optional switch that is off when missing. */
    private static boolean flag(ConfigurationSection c, String path) {
        if (!c.contains(path)) {
            return false;
        }
        if (!c.isBoolean(path)) {
            throw new IllegalArgumentException(path + " must be true or false");
        }
        return c.getBoolean(path);
    }

    /** The sender must hold a real address, either bare or as "Name <address>"; a name alone fails every send. */
    private static void checkFrom(String from) {
        String address;
        try {
            address = new InternetAddress(from, true).getAddress();
        } catch (AddressException e) {
            address = null;
        }
        if (address == null || EmailAddress.parse(address).isEmpty()) {
            throw new IllegalArgumentException(
                    "email.smtp.from must be an email address, e.g. \"My Server <noreply@example.com>\"");
        }
    }

    private static Security security(String raw) {
        try {
            return Security.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("email.smtp.security must be starttls, ssl or none");
        }
    }

    private static Duration minutes(ConfigurationSection c, String path) {
        return Duration.ofMinutes(positive(c, path));
    }

    private static int atMost(ConfigurationSection c, String path, int max) {
        int value = positive(c, path);
        if (value > max) {
            throw new IllegalArgumentException(path + " must be " + max + " or less");
        }
        return value;
    }

    private static int positive(ConfigurationSection c, String path) {
        if (!c.isInt(path)) {
            throw new IllegalArgumentException(path + " must be a whole number");
        }
        int value = c.getInt(path);
        if (value <= 0) {
            throw new IllegalArgumentException(path + " must be greater than 0");
        }
        return value;
    }
}
