package dev.tongt.mcserverauth.config;

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
        Smtp smtp) {

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
        Smtp smtp = new Smtp(
                c.getString("email.smtp.host", "").trim(),
                positive(c, "email.smtp.port"),
                security(c.getString("email.smtp.security", "starttls")),
                c.getString("email.smtp.username", ""),
                c.getString("email.smtp.password", ""),
                c.getString("email.smtp.from", "").trim());
        return new AuthConfig(
                positive(c, "max-attempts-per-connection"),
                positive(c, "ip-ban.failures"),
                minutes(c, "ip-ban.window-minutes"),
                minutes(c, "ip-ban.ban-minutes"),
                Duration.ofSeconds(positive(c, "login-timeout-seconds")),
                minutes(c, "code-wait-timeout-minutes"),
                minLength,
                maxLength,
                c.getBoolean("session-remember.enabled", false),
                minutes(c, "session-remember.minutes"),
                positive(c, "email.max-accounts-per-email"),
                minutes(c, "email.code-valid-minutes"),
                positive(c, "email.code-max-attempts"),
                smtp);
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
