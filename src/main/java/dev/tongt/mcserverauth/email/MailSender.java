package dev.tongt.mcserverauth.email;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public interface MailSender extends AutoCloseable {
    /**
     * One email on its way.
     *
     * @param done     completes when the email was handed to the SMTP server; fails if delivery fails
     * @param withdraw returns true if the email had not started sending yet and now never will (then {@code done}
     *                 fails); false if it already started or finished, so it may still arrive
     */
    record Sending(CompletableFuture<Void> done, BooleanSupplier withdraw) {
        public static Sending failed(Throwable cause) {
            return new Sending(CompletableFuture.failedFuture(cause), () -> true);
        }
    }

    /** False when SMTP is not configured; registration and reset are then refused. */
    boolean available();

    /** Sends one plain-text email in the background. */
    Sending send(String to, String subject, String body);

    @Override
    void close();
}
