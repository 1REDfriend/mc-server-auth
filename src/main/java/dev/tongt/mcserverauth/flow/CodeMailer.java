package dev.tongt.mcserverauth.flow;

import dev.tongt.mcserverauth.config.AuthConfig;
import dev.tongt.mcserverauth.config.Messages;
import dev.tongt.mcserverauth.email.CodeService;
import dev.tongt.mcserverauth.email.CodeService.Purpose;
import dev.tongt.mcserverauth.email.EmailAddress;
import dev.tongt.mcserverauth.email.MailSender;
import dev.tongt.mcserverauth.email.MailSender.Sending;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Issues a code and emails it, shared by the login flow and /email. Blocks the caller for at most 30 s. */
public final class CodeMailer {
    private static final long MAIL_SEND_TIMEOUT_SECONDS = 30;

    /**
     * Result of one send. {@code sent} is true when a code was issued and is, or may still be, on its way; then
     * {@code message} is an optional notice for the code dialog. When false, {@code message} is the error.
     */
    public record SendResult(boolean sent, @Nullable Component message) {
        static SendResult ok() { return new SendResult(true, null); }
        static SendResult slow(Component notice) { return new SendResult(true, notice); }
        static SendResult failed(Component error) { return new SendResult(false, error); }
    }

    private final AuthConfig config;
    private final Messages messages;
    private final CodeService codes;
    private final MailSender mail;
    private final Logger logger;

    public CodeMailer(AuthConfig config, Messages messages, CodeService codes, MailSender mail, Logger logger) {
        this.config = config;
        this.messages = messages;
        this.codes = codes;
        this.mail = mail;
        this.logger = logger;
    }

    /** True when SMTP is configured, so codes can be sent at all. */
    public boolean available() {
        return mail.available();
    }

    /** Why a code cannot be sent right now, or null if it can. Issues nothing. */
    public @Nullable Component cooldown(Purpose purpose, String subject, String ipKey) {
        return codes.cooldownRemaining(purpose, subject, ipKey).map(this::cooldownMessage).orElse(null);
    }

    /**
     * Issues a code for {@code subject} (the lowercase account name) and emails it. A send that surely failed is
     * withdrawn and its code refunded, so it costs the player no cooldown.
     */
    public SendResult send(Purpose purpose, String subject, String displayName, String ipKey, EmailAddress email) {
        CodeService.Issue issue = codes.issue(purpose, subject, ipKey);
        if (issue instanceof CodeService.CoolingDown wait) {
            return SendResult.failed(cooldownMessage(wait.remaining()));
        }
        CodeService.Issued issued = (CodeService.Issued) issue;
        Map<String, String> vars = Map.of(
                "name", displayName,
                "code", issued.code(),
                "minutes", Long.toString(config.codeValidity().toMinutes()));
        // Registration and /email both confirm an address, so they share the verification email.
        boolean reset = purpose == Purpose.RESET;
        String subjectLine = messages.plain(reset ? "email.reset.subject" : "email.register.subject", vars);
        String body = messages.plain(reset ? "email.reset.body" : "email.register.body", vars);
        Sending sending = mail.send(email.value(), subjectLine, body);
        try {
            sending.done().get(MAIL_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            if (sending.withdraw().getAsBoolean()) {
                // Still waiting for a mail thread: it will never go out now, so treat it as a failure and refund.
                logger.warning("The " + purpose + " email for " + displayName + " was still queued after "
                        + MAIL_SEND_TIMEOUT_SECONDS + " s; withdrawn");
                codes.cancelIssue(purpose, subject, ipKey, issued);
                return SendResult.failed(messages.get("error.email-send-failed"));
            }
            // Already handed to the SMTP client, which may still deliver it: keep the code.
            logger.warning("Sending the " + purpose + " email for " + displayName + " is taking longer than "
                    + MAIL_SEND_TIMEOUT_SECONDS + " s; the code stays valid");
            return SendResult.slow(messages.get("error.email-slow"));
        } catch (ExecutionException e) {
            logger.log(Level.WARNING, "Could not send " + purpose + " email for " + displayName, e);
            codes.cancelIssue(purpose, subject, ipKey, issued);
            return SendResult.failed(messages.get("error.email-send-failed"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!sending.withdraw().getAsBoolean()) {
                // It may still arrive; keep the code, like the slow path.
                return SendResult.slow(messages.get("error.email-slow"));
            }
            codes.cancelIssue(purpose, subject, ipKey, issued);
            return SendResult.failed(messages.get("error.email-send-failed"));
        }
        return SendResult.ok();
    }

    private Component cooldownMessage(Duration remaining) {
        long seconds = Math.max(1, (remaining.toMillis() + 999) / 1000);
        return messages.get("error.code-cooldown", Placeholder.unparsed("seconds", Long.toString(seconds)));
    }
}
