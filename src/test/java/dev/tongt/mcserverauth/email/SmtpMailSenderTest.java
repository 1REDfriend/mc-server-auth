package dev.tongt.mcserverauth.email;

import dev.tongt.mcserverauth.config.AuthConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SmtpMailSenderTest {

    @Test
    void unconfiguredSenderIsUnavailableAndFails() {
        var smtp = new AuthConfig.Smtp("", 587, AuthConfig.Security.STARTTLS, "", "", "");
        try (SmtpMailSender sender = new SmtpMailSender(smtp)) {
            assertFalse(sender.available());
            var future = sender.send("a@example.com", "s", "b").done();
            assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void refusesRecipientsThatAreNotOneBareAddress() {
        var smtp = new AuthConfig.Smtp("127.0.0.1", 1, AuthConfig.Security.NONE, "", "", "Test <t@example.test>");
        try (SmtpMailSender sender = new SmtpMailSender(smtp)) {
            for (String to : new String[] {"a@b.com,c@d.com", "\"x\"<v@evil.com>", "Name <a@b.com>", "g:a@b.com;"}) {
                var future = sender.send(to, "s", "b").done();
                var e = assertThrows(ExecutionException.class, () -> future.get(30, TimeUnit.SECONDS));
                assertInstanceOf(jakarta.mail.internet.AddressException.class, e.getCause(), to);
            }
        }
    }

    @Test
    void unreachableServerFailsTheFuture() {
        // Port 1 on loopback is closed; the send must fail, not hang.
        var smtp = new AuthConfig.Smtp("127.0.0.1", 1, AuthConfig.Security.NONE, "", "", "Test <t@example.test>");
        try (SmtpMailSender sender = new SmtpMailSender(smtp)) {
            assertTrue(sender.available());
            var future = sender.send("a@example.com", "s", "b").done();
            assertThrows(ExecutionException.class, () -> future.get(30, TimeUnit.SECONDS));
        }
    }

    private static final AuthConfig.Smtp CONFIGURED =
            new AuthConfig.Smtp("127.0.0.1", 1, AuthConfig.Security.NONE, "", "", "Test <t@example.test>");

    @Test
    void fullQueueRefusesTheSend() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        try (SmtpMailSender sender = new SmtpMailSender(CONFIGURED, 1, 1, (to, subject, body) -> {
            running.countDown();
            release.await();
        })) {
            var first = sender.send("a@example.com", "s", "b");
            assertTrue(running.await(5, TimeUnit.SECONDS));
            var queued = sender.send("a@example.com", "s", "b");
            var refused = sender.send("a@example.com", "s", "b");
            var e = assertThrows(ExecutionException.class, () -> refused.done().get(5, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, e.getCause());
            assertTrue(refused.withdraw().getAsBoolean());
            release.countDown();
            first.done().get(5, TimeUnit.SECONDS);
            queued.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void queuedSendCanBeWithdrawnAndNeverRuns() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        try (SmtpMailSender sender = new SmtpMailSender(CONFIGURED, 1, 5, (to, subject, body) -> {
            running.countDown();
            release.await();
            delivered.incrementAndGet();
        })) {
            var first = sender.send("a@example.com", "s", "b");
            assertTrue(running.await(5, TimeUnit.SECONDS));
            var queued = sender.send("a@example.com", "s", "b");
            assertFalse(first.withdraw().getAsBoolean(), "already started");
            assertTrue(queued.withdraw().getAsBoolean(), "still queued");
            release.countDown();
            first.done().get(5, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, () -> queued.done().get(5, TimeUnit.SECONDS));
            assertEquals(1, delivered.get());
        }
    }
}
