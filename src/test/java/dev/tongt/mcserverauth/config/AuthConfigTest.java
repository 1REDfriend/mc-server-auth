package dev.tongt.mcserverauth.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class AuthConfigTest {

    private static YamlConfiguration defaults() {
        var stream = Objects.requireNonNull(AuthConfigTest.class.getResourceAsStream("/config.yml"));
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    @Test
    void parsesBundledDefaults() {
        AuthConfig config = AuthConfig.from(defaults());

        assertEquals(3, config.maxAttemptsPerConnection());
        assertEquals(5, config.ipBanFailures());
        assertEquals(Duration.ofMinutes(10), config.ipBanWindow());
        assertEquals(Duration.ofMinutes(10), config.ipBanDuration());
        assertEquals(3, config.maxAccountsPerEmail());
        assertEquals(5, config.codeMaxAttempts());
        assertEquals(587, config.smtp().port());
        assertEquals(Duration.ofMinutes(5), config.sessionRememberDuration());
        assertEquals(Duration.ofSeconds(60), config.loginTimeout());
        assertEquals(Duration.ofMinutes(10), config.codeWaitTimeout());
        assertEquals(6, config.passwordMinLength());
        assertEquals(64, config.passwordMaxLength());
        assertFalse(config.sessionRememberEnabled());
        assertEquals(Duration.ofMinutes(15), config.codeValidity());
        assertEquals(AuthConfig.Security.STARTTLS, config.smtp().security());
        assertFalse(config.smtp().configured());
        assertFalse(config.secretInputLogEnabled());
        assertEquals(7, config.secretInputLogKeepDays());
    }

    @Test
    void secretInputLogEnabledMustBeABoolean() {
        YamlConfiguration yaml = defaults();
        yaml.set("secret-input-log.enabled", "yes");
        var e = assertThrows(IllegalArgumentException.class, () -> AuthConfig.from(yaml));
        assertTrue(e.getMessage().contains("secret-input-log.enabled"));
        yaml.set("secret-input-log.enabled", true);
        assertTrue(AuthConfig.from(yaml).secretInputLogEnabled());
    }

    @Test
    void secretInputLogKeepDaysMustBePositive() {
        YamlConfiguration yaml = defaults();
        yaml.set("secret-input-log.keep-days", 0);
        var e = assertThrows(IllegalArgumentException.class, () -> AuthConfig.from(yaml));
        assertTrue(e.getMessage().contains("secret-input-log.keep-days"));
    }

    @Test
    void rejectsMinLengthAboveMaxLength() {
        YamlConfiguration yaml = defaults();
        yaml.set("password.min-length", 70);
        yaml.set("password.max-length", 10);
        var e = assertThrows(IllegalArgumentException.class, () -> AuthConfig.from(yaml));
        assertTrue(e.getMessage().contains("password.min-length"));
    }

    @Test
    void rejectsZero() {
        YamlConfiguration yaml = defaults();
        yaml.set("max-attempts-per-connection", 0);
        var e = assertThrows(IllegalArgumentException.class, () -> AuthConfig.from(yaml));
        assertTrue(e.getMessage().contains("max-attempts-per-connection"));
    }

    @Test
    void rejectsNonBooleanSessionRememberEnabled() {
        YamlConfiguration yaml = defaults();
        yaml.set("session-remember.enabled", "yes please");
        var e = assertThrows(IllegalArgumentException.class, () -> AuthConfig.from(yaml));
        assertTrue(e.getMessage().contains("session-remember.enabled"));
    }

    @Test
    void acceptsBooleanSessionRememberEnabled() {
        YamlConfiguration yaml = defaults();
        yaml.set("session-remember.enabled", true);
        assertTrue(AuthConfig.from(yaml).sessionRememberEnabled());
    }

    @Test
    void rejectsSmtpPortAbove65535() {
        YamlConfiguration yaml = defaults();
        yaml.set("email.smtp.port", 65536);
        var e = assertThrows(IllegalArgumentException.class, () -> AuthConfig.from(yaml));
        assertTrue(e.getMessage().contains("email.smtp.port"));
        yaml.set("email.smtp.port", 65535);
        assertEquals(65535, AuthConfig.from(yaml).smtp().port());
    }

    @Test
    void rejectsUnknownSmtpSecurity() {
        YamlConfiguration yaml = defaults();
        yaml.set("email.smtp.security", "tls13");
        var e = assertThrows(IllegalArgumentException.class, () -> AuthConfig.from(yaml));
        assertTrue(e.getMessage().contains("email.smtp.security"));
    }

    @Test
    void smtpConfiguredNeedsHostAndFrom() {
        YamlConfiguration yaml = defaults();
        yaml.set("email.smtp.host", "127.0.0.1");
        assertFalse(AuthConfig.from(yaml).smtp().configured());
        yaml.set("email.smtp.from", "Test <noreply@example.test>");
        assertTrue(AuthConfig.from(yaml).smtp().configured());
    }

    @Test
    void rejectsFromWithoutAnAddress() {
        YamlConfiguration yaml = defaults();
        yaml.set("email.smtp.host", "127.0.0.1");
        for (String from : new String[] {"LAB306 - TOP CRAFT Server", "noreply", "My Server <noreply>"}) {
            yaml.set("email.smtp.from", from);
            var e = assertThrows(IllegalArgumentException.class, () -> AuthConfig.from(yaml), from);
            assertTrue(e.getMessage().contains("email.smtp.from"), from);
        }
    }

    @Test
    void acceptsPlainOrNamedFrom() {
        YamlConfiguration yaml = defaults();
        yaml.set("email.smtp.host", "127.0.0.1");
        yaml.set("email.smtp.from", "noreply@example.test");
        assertTrue(AuthConfig.from(yaml).smtp().configured());
        yaml.set("email.smtp.from", "LAB306 - TOP CRAFT Server <noreply@example.test>");
        assertTrue(AuthConfig.from(yaml).smtp().configured());
    }

    @Test
    void capsCodeMaxAttemptsAtTen() {
        YamlConfiguration yaml = defaults();
        yaml.set("email.code-max-attempts", 11);
        var e = assertThrows(IllegalArgumentException.class, () -> AuthConfig.from(yaml));
        assertTrue(e.getMessage().contains("email.code-max-attempts"));
        yaml.set("email.code-max-attempts", 10);
        assertEquals(10, AuthConfig.from(yaml).codeMaxAttempts());
    }

    @Test
    void capsMaxAttemptsPerConnectionAtTwenty() {
        YamlConfiguration yaml = defaults();
        yaml.set("max-attempts-per-connection", 21);
        var e = assertThrows(IllegalArgumentException.class, () -> AuthConfig.from(yaml));
        assertTrue(e.getMessage().contains("max-attempts-per-connection"));
        yaml.set("max-attempts-per-connection", 20);
        assertEquals(20, AuthConfig.from(yaml).maxAttemptsPerConnection());
    }
}
