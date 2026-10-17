package dev.tongt.mcserverauth.email;

import dev.tongt.mcserverauth.config.AuthConfig;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Date;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class SmtpMailSender implements MailSender {
    private static final String TIMEOUT_MILLIS = "10000";
    private static final int MAIL_THREADS = 3;
    /** Emails waiting for a free thread; more than this are refused, which the caller treats as a failed send. */
    private static final int QUEUE_CAPACITY = 50;

    private static final int QUEUED = 0;
    private static final int STARTED = 1;
    private static final int WITHDRAWN = 2;

    /** Hands one message to the SMTP server. Replaceable in tests. */
    @FunctionalInterface
    interface Delivery {
        void deliver(String to, String subject, String body) throws Exception;
    }

    private final AuthConfig.Smtp smtp;
    private final ExecutorService executor;
    private final Delivery delivery;

    public SmtpMailSender(AuthConfig.Smtp smtp) {
        this(smtp, MAIL_THREADS, QUEUE_CAPACITY, null);
    }

    /** @param delivery null for real SMTP delivery */
    SmtpMailSender(AuthConfig.Smtp smtp, int threads, int queueCapacity, Delivery delivery) {
        this.smtp = smtp;
        this.delivery = delivery != null ? delivery : this::deliver;
        // Jakarta Mail finds its SMTP provider through the context class loader, which must be the plugin's.
        ClassLoader pluginLoader = SmtpMailSender.class.getClassLoader();
        this.executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, "McServerAuth-mail");
                    thread.setDaemon(true);
                    thread.setContextClassLoader(pluginLoader);
                    return thread;
                });
    }

    @Override
    public boolean available() {
        return smtp.configured();
    }

    @Override
    public Sending send(String to, String subject, String body) {
        if (!available()) {
            return Sending.failed(new IllegalStateException("SMTP is not configured"));
        }
        AtomicInteger state = new AtomicInteger(QUEUED);
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                if (!state.compareAndSet(QUEUED, STARTED)) {
                    done.completeExceptionally(new IllegalStateException("Withdrawn before sending"));
                    return;
                }
                try {
                    delivery.deliver(to, subject, body);
                    done.complete(null);
                } catch (Throwable e) {
                    done.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            return Sending.failed(e);
        }
        return new Sending(done, () -> state.compareAndSet(QUEUED, WITHDRAWN));
    }

    private void deliver(String to, String subject, String body) throws MessagingException {
        boolean auth = !smtp.username().isBlank();
        Properties props = new Properties();
        props.put("mail.smtp.host", smtp.host());
        props.put("mail.smtp.port", Integer.toString(smtp.port()));
        props.put("mail.smtp.auth", Boolean.toString(auth));
        props.put("mail.smtp.connectiontimeout", TIMEOUT_MILLIS);
        props.put("mail.smtp.timeout", TIMEOUT_MILLIS);
        props.put("mail.smtp.writetimeout", TIMEOUT_MILLIS);
        switch (smtp.security()) {
            case STARTTLS -> {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
                props.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case SSL -> {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case NONE -> {
            }
        }
        MimeMessage message = new MimeMessage(Session.getInstance(props));
        message.setFrom(new InternetAddress(smtp.from()));
        message.setRecipient(Message.RecipientType.TO, bareAddress(to));
        message.setSubject(subject, "UTF-8");
        message.setText(body, "UTF-8");
        message.setSentDate(new Date());
        if (auth) {
            Transport.send(message, smtp.username(), smtp.password());
        } else {
            Transport.send(message);
        }
    }

    /** The recipient must be exactly one plain address: no display name, group or list. */
    private static InternetAddress bareAddress(String to) throws AddressException {
        InternetAddress address = new InternetAddress(to, true);
        address.validate();
        if (address.getPersonal() != null || address.isGroup() || !to.equals(address.getAddress())) {
            throw new AddressException("Recipient must be a single plain address", to);
        }
        return address;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
