package dev.tongt.mcserverauth.email;

import java.util.concurrent.CompletableFuture;

public interface MailSender extends AutoCloseable {
    /** False when SMTP is not configured; registration and reset are then refused. */
    boolean available();

    /** Sends one plain-text email in the background. The future fails if delivery fails. */
    CompletableFuture<Void> send(String to, String subject, String body);

    @Override
    void close();
}
