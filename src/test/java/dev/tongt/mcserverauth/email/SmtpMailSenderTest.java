package dev.tongt.mcserverauth.email;

import dev.tongt.mcserverauth.config.AuthConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SmtpMailSenderTest {

    @Test
    void unconfiguredSenderIsUnavailableAndFails() {
        var smtp = new AuthConfig.Smtp("", 587, AuthConfig.Security.STARTTLS, "", "", "");
        try (SmtpMailSender sender = new SmtpMailSender(smtp)) {
            assertFalse(sender.available());
            var future = sender.send("a@example.com", "s", "b");
            assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void unreachableServerFailsTheFuture() {
        // Port 1 on loopback is closed; the send must fail, not hang.
        var smtp = new AuthConfig.Smtp("127.0.0.1", 1, AuthConfig.Security.NONE, "", "", "Test <t@example.test>");
        try (SmtpMailSender sender = new SmtpMailSender(smtp)) {
            assertTrue(sender.available());
            var future = sender.send("a@example.com", "s", "b");
            assertThrows(ExecutionException.class, () -> future.get(30, TimeUnit.SECONDS));
        }
    }
}
