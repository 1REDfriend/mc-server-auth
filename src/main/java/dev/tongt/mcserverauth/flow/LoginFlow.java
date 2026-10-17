package dev.tongt.mcserverauth.flow;

import dev.tongt.mcserverauth.config.AuthConfig;
import dev.tongt.mcserverauth.config.Messages;
import dev.tongt.mcserverauth.email.CodeService;
import dev.tongt.mcserverauth.email.CodeService.Purpose;
import dev.tongt.mcserverauth.email.EmailAddress;
import dev.tongt.mcserverauth.email.MailSender;
import dev.tongt.mcserverauth.email.MailSender.Sending;
import dev.tongt.mcserverauth.flow.DialogWaiter.Response;
import dev.tongt.mcserverauth.security.AttemptLimiter;
import dev.tongt.mcserverauth.security.IpKey;
import dev.tongt.mcserverauth.security.PasswordHasher;
import dev.tongt.mcserverauth.security.PasswordRules;
import dev.tongt.mcserverauth.security.SessionCache;
import dev.tongt.mcserverauth.storage.Account;
import dev.tongt.mcserverauth.storage.AccountStore;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the whole login during the configuration phase, before the player enters the world.
 * Each handler call blocks Paper's async configuration thread until the player passes or is disconnected.
 */
public final class LoginFlow implements Listener {
    private static final int MAX_REGISTER_SENDS_PER_CONNECTION = 3;
    private static final long MAIL_SEND_TIMEOUT_SECONDS = 30;
    /** The server-password dialog waits at most this long, so idling on it cannot hold a name for long. */
    private static final Duration SERVER_PASSWORD_TIMEOUT = Duration.ofSeconds(30);
    /** Connections from one IP that may be in the login flow at the same time. */
    private static final int MAX_CONNECTIONS_PER_IP = 2;
    /** Submits allowed in one dialog loop (register, new password) per connection; each can cost bcrypt work. */
    private static final int MAX_SUBMITS_PER_LOOP = 10;
    /** How long a connection that passed the dialogs may take to reach PlayerJoinEvent. */
    private static final Duration PASSED_TOKEN_LIFETIME = Duration.ofSeconds(30);

    private enum Step { DONE, BACK, CANCEL }

    private record StepResult(Step step, @Nullable Component message) {
        static StepResult done() { return new StepResult(Step.DONE, null); }
        static StepResult back(@Nullable Component message) { return new StepResult(Step.BACK, message); }
        static StepResult cancel() { return new StepResult(Step.CANCEL, null); }
        /** Ends the flow with this disconnect message. */
        static StepResult deny(Component message) { return new StepResult(Step.CANCEL, message); }
    }

    /** Per-connection state. */
    private static final class Session {
        final PlayerConfigurationConnection connection;
        final Object waitKey;
        final String name;
        final String key;
        final InetAddress ip;
        /** Key for IP-based limits: IPv6 grouped by /64. */
        final String ipKey;
        /** Soft deadline for normal dialogs; refreshed after each code dialog. */
        Instant deadline;
        /** Absolute end of the whole flow for this connection; never extended. */
        final Instant hardDeadline;
        /** Wrong server or personal passwords on this connection. */
        int failures;
        int registerSends;
        int registerSubmits;
        int resetSubmits;

        Session(PlayerConfigurationConnection connection, String name, InetAddress ip, Instant start,
                Duration loginTimeout, Duration codeWaitTimeout) {
            this.connection = connection;
            this.waitKey = DialogWaiter.keyOf(connection);
            this.name = name;
            this.key = AccountStore.key(name);
            this.ip = ip;
            this.ipKey = IpKey.of(ip);
            this.deadline = start.plus(loginTimeout);
            this.hardDeadline = start.plus(codeWaitTimeout).plus(loginTimeout.multipliedBy(2));
        }
    }

    private final AuthConfig config;
    private final Messages messages;
    private final DialogFactory dialogs;
    private final DialogWaiter waiter;
    private final AccountStore store;
    private final PasswordHasher hasher;
    private final PasswordRules rules;
    private final AttemptLimiter limiter;
    private final SessionCache sessions;
    private final CodeService codes;
    private final MailSender mail;
    private final Clock clock;
    private final Logger logger;

    /** Proof that a connection passed the dialogs; consumed on PlayerJoinEvent. */
    private record PassedToken(String nameKey, Instant expires) {}

    /** Players who passed the flow and are in the world. Added on join, removed on quit. */
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PassedToken> passed = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** Number of connections per IP currently in the configuration-phase flow. */
    private final Map<String, Integer> inFlightPerIp = new ConcurrentHashMap<>();
    /** Lowercase names of players in the world, readable from any thread. */
    private final Set<String> online = ConcurrentHashMap.newKeySet();

    public LoginFlow(AuthConfig config, Messages messages, DialogFactory dialogs, DialogWaiter waiter,
                     AccountStore store, PasswordHasher hasher, PasswordRules rules, AttemptLimiter limiter,
                     SessionCache sessions, CodeService codes, MailSender mail, Clock clock, Logger logger) {
        this.config = config;
        this.messages = messages;
        this.dialogs = dialogs;
        this.waiter = waiter;
        this.store = store;
        this.hasher = hasher;
        this.rules = rules;
        this.limiter = limiter;
        this.sessions = sessions;
        this.codes = codes;
        this.mail = mail;
        this.clock = clock;
        this.logger = logger;
    }

    /**
     * True while a player with this name is in the world, is going through the login dialogs, or has just passed
     * them and is on the way into the world. Thread-safe.
     */
    public boolean isNameBusy(String name) {
        String key = AccountStore.key(name);
        if (inFlight.contains(key) || online.contains(key)) {
            return true;
        }
        Instant now = clock.instant();
        return passed.values().stream().anyMatch(t -> t.nameKey().equals(key) && now.isBefore(t.expires()));
    }

    /** True when this IP already has the maximum number of connections in the login flow. Thread-safe. */
    public boolean tooManyConnections(InetAddress ip) {
        return inFlightPerIp.getOrDefault(IpKey.of(ip), 0) >= MAX_CONNECTIONS_PER_IP;
    }

    /** Counts one more connection from this IP unless it is already at the limit. Atomic. */
    private boolean enterIp(String ip) {
        boolean[] entered = {false};
        inFlightPerIp.compute(ip, (k, count) -> {
            int current = count == null ? 0 : count;
            if (current >= MAX_CONNECTIONS_PER_IP) {
                return count;
            }
            entered[0] = true;
            return current + 1;
        });
        return entered[0];
    }

    private void leaveIp(String ip) {
        inFlightPerIp.computeIfPresent(ip, (k, count) -> count <= 1 ? null : count - 1);
    }

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection connection = event.getConnection();
        UUID id = connection.getProfile().getId();
        String name = connection.getProfile().getName();
        if (id == null || name == null) {
            connection.disconnect(messages.get("kick.unavailable"));
            return;
        }
        String key = AccountStore.key(name);
        if (authenticated.contains(id) && online.contains(key)) {
            return; // reconfiguration of a player who already logged in and is in the world
        }
        InetAddress ip = connection.getClientAddress().getAddress();
        String ipKey = IpKey.of(ip);
        // PreLoginGuard refuses early; this is the atomic check.
        if (!enterIp(ipKey)) {
            connection.disconnect(messages.get("kick.too-many-connections"));
            return;
        }
        if (!inFlight.add(key)) {
            leaveIp(ipKey);
            connection.disconnect(messages.get("kick.name-in-use"));
            return;
        }
        try {
            Session session = new Session(connection, name, ip, clock.instant(), config.loginTimeout(),
                    config.codeWaitTimeout());
            Component denial = run(session);
            if (denial != null) {
                connection.disconnect(denial);
            } else {
                Instant now = clock.instant();
                passed.values().removeIf(t -> !now.isBefore(t.expires()));
                passed.put(id, new PassedToken(key, now.plus(PASSED_TOKEN_LIFETIME)));
                connection.getAudience().closeDialog();
            }
        } catch (TimeoutException e) {
            connection.disconnect(messages.get("kick.timeout"));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error while logging in " + name, e);
            connection.disconnect(messages.get("kick.unavailable"));
        } catch (Throwable e) {
            // Includes Errors: never let an exception end the handler, which would let the player continue.
            logger.log(Level.SEVERE, "Unexpected error while logging in " + name, e);
            passed.remove(id);
            try {
                connection.disconnect(messages.get("kick.unavailable"));
            } catch (Throwable disconnectError) {
                logger.log(Level.SEVERE, "Could not disconnect " + name + " after an error", disconnectError);
            }
        } finally {
            inFlight.remove(key);
            leaveIp(ipKey);
        }
    }

    /** Safety net: nobody reaches the world without a fresh pass from the configuration-phase flow. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        String key = AccountStore.key(player.getName());
        try {
            // Mark the name busy before the token goes, so isNameBusy never sees a gap between the two.
            online.add(key);
            PassedToken token = passed.remove(id);
            if (token == null || !clock.instant().isBefore(token.expires())) {
                logger.warning("Kicking " + player.getName() + ": joined without passing the login flow");
                online.remove(key);
                player.kick(messages.get("kick.unavailable"));
                return;
            }
            authenticated.add(id);
        } catch (Throwable e) {
            logger.log(Level.SEVERE, "Unexpected error while " + player.getName() + " joined", e);
            authenticated.remove(id);
            player.kick(messages.get("kick.unavailable"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        authenticated.remove(event.getPlayer().getUniqueId());
        online.remove(AccountStore.key(event.getPlayer().getName()));
    }

    /** Returns null when the player may enter, otherwise the disconnect message. */
    private @Nullable Component run(Session s) throws TimeoutException, SQLException {
        if (sessions.isRemembered(s.key, s.ip)) {
            store.recordLogin(s.name, clock.millis(), s.ip.getHostAddress());
            return null;
        }
        String serverHash = store.serverPasswordHash()
                .orElseThrow(() -> new SQLException("Server password is not set"));
        // Captured once the server password is known good; a later clear() makes remember() a no-op.
        long generation = -1;

        Component error = null;
        while (true) {
            Response r;
            try {
                r = promptServerPassword(s, dialogs.serverPassword(error));
            } catch (TimeoutException e) {
                // Idling on the first dialog keeps the name busy for others; make it cost this IP.
                limiter.recordFailure(s.ipKey);
                throw e;
            }
            if (!r.is(DialogFactory.SUBMIT)) {
                limiter.recordFailure(s.ipKey);
                return messages.get("kick.cancelled");
            }
            if (hasher.verify(r.text("password"), serverHash)) {
                generation = sessions.generation();
                break;
            }
            Component denial = recordFailure(s);
            if (denial != null) {
                return denial;
            }
            error = messages.get("error.wrong-server-password");
        }

        Optional<Account> account = store.find(s.name);
        Component denial = account.isEmpty() ? register(s, serverHash) : login(s, account.get(), serverHash);
        if (denial == null && account.isPresent() && store.find(s.name).isEmpty()) {
            denial = messages.get("kick.account-reset"); // deleted by an admin while the dialogs were open
        }
        if (denial == null) {
            store.recordLogin(s.name, clock.millis(), s.ip.getHostAddress());
            sessions.remember(s.key, s.ip, generation);
        }
        return denial;
    }

    private @Nullable Component register(Session s, String serverHash) throws TimeoutException, SQLException {
        if (!mail.available()) {
            return messages.get("kick.registration-unavailable");
        }
        Component error = null;
        while (true) {
            Response r = prompt(s, dialogs.register(error));
            if (!r.is(DialogFactory.SUBMIT)) {
                return messages.get("kick.cancelled");
            }
            if (++s.registerSubmits > MAX_SUBMITS_PER_LOOP) {
                return messages.get("kick.too-many-attempts");
            }
            // Cheap checks first: bcrypt work only for a submit that can lead to a code being sent.
            String password = r.text("password");
            Optional<String> problem = rules.check(password, r.text("confirm"), pw -> false);
            if (problem.isPresent()) {
                error = messages.get(problem.get(), rules.placeholders());
                continue;
            }
            Optional<EmailAddress> email = EmailAddress.parse(r.text("email"));
            if (email.isEmpty()) {
                error = messages.get("error.email-invalid");
                continue;
            }
            // Early check so nobody waits for a code that cannot be used; re-checked atomically on insert.
            if (store.countByEmail(email.get().value()) >= config.maxAccountsPerEmail()) {
                error = messages.get("error.email-limit");
                continue;
            }
            Component blocked = sendBlocked(s, Purpose.REGISTER);
            if (blocked != null) {
                error = blocked;
                continue;
            }
            if (hasher.verify(password, serverHash)) {
                error = messages.get("error.password-same-as-server");
                continue;
            }
            SendResult send = sendCode(s, Purpose.REGISTER, email.get());
            if (!send.sent()) {
                error = send.message();
                continue;
            }
            String passwordHash = hasher.hash(password);
            StepResult verified = verifyCode(s, Purpose.REGISTER, email.get(), send.message());
            if (verified.step() == Step.CANCEL) {
                return verified.message() != null ? verified.message() : messages.get("kick.cancelled");
            }
            if (verified.step() == Step.BACK) {
                error = verified.message();
                continue;
            }
            if (!store.createIfEmailBelow(s.name, passwordHash, email.get().value(), clock.millis(),
                    config.maxAccountsPerEmail())) {
                error = messages.get("error.email-limit");
                continue;
            }
            return null;
        }
    }

    private @Nullable Component login(Session s, Account account, String serverHash) throws TimeoutException, SQLException {
        Component error = null;
        while (true) {
            Response r = promptHolding(s, dialogs.login(error));
            if (r.is(DialogFactory.FORGOT)) {
                StepResult result = forgot(s, account, serverHash);
                if (result.step() == Step.DONE) {
                    return null;
                }
                if (result.step() == Step.CANCEL) {
                    return result.message() != null ? result.message() : messages.get("kick.cancelled");
                }
                error = result.message();
                continue;
            }
            if (!r.is(DialogFactory.SUBMIT)) {
                limiter.recordFailure(s.ipKey);
                return messages.get("kick.cancelled");
            }
            if (hasher.verify(r.text("password"), account.passwordHash())) {
                return null;
            }
            Component denial = recordFailure(s);
            if (denial != null) {
                return denial;
            }
            error = messages.get("error.wrong-password");
        }
    }

    private StepResult forgot(Session s, Account account, String serverHash) throws TimeoutException, SQLException {
        EmailAddress email = new EmailAddress(account.email());
        Component menuError = null;
        Component codeNotice = null;
        while (true) {
            Response r = promptHolding(s, dialogs.forgotMenu(email.masked(), menuError));
            if (r.is(DialogFactory.BACK)) {
                return StepResult.back(null);
            }
            if (r.is(DialogFactory.SEND_CODE)) {
                if (!mail.available()) {
                    return StepResult.back(messages.get("error.reset-unavailable"));
                }
                SendResult send = sendCode(s, Purpose.RESET, email);
                if (!send.sent()) {
                    menuError = send.message(); // includes the reset cooldown
                    continue;
                }
                codeNotice = send.message();
            } else if (r.is(DialogFactory.HAVE_CODE)) {
                if (!codes.hasLiveCode(Purpose.RESET, s.key)) {
                    menuError = messages.get("error.no-live-code");
                    continue;
                }
            } else {
                limiter.recordFailure(s.ipKey);
                return StepResult.cancel();
            }
            break;
        }

        StepResult verified = verifyCode(s, Purpose.RESET, email, codeNotice);
        if (verified.step() != Step.DONE) {
            return verified; // BACK returns to dialog 2b, with error.code-expired if the code is gone
        }

        Component error = null;
        while (true) {
            Response r = prompt(s, dialogs.newPassword("dialog.reset-password.title", error, false));
            if (!r.is(DialogFactory.SUBMIT)) {
                return StepResult.cancel();
            }
            if (++s.resetSubmits > MAX_SUBMITS_PER_LOOP) {
                return StepResult.deny(messages.get("kick.too-many-attempts"));
            }
            String password = r.text("password");
            Optional<String> problem = rules.check(password, r.text("confirm"), pw -> false);
            if (problem.isPresent()) {
                error = messages.get(problem.get(), rules.placeholders());
                continue;
            }
            // The only bcrypt compare, after the cheap rules.
            if (hasher.verify(password, serverHash)) {
                error = messages.get("error.password-same-as-server");
                continue;
            }
            if (!store.updatePassword(s.name, hasher.hash(password))) {
                return StepResult.deny(messages.get("kick.account-reset")); // deleted by an admin meanwhile
            }
            sessions.forget(s.key);
            return StepResult.done();
        }
    }

    /**
     * Code entry loop. DONE = correct code; BACK = player went back, or the code is burned or expired (then the
     * message is error.code-expired, to show on the previous dialog); CANCEL = closed or cancelled, or (with a
     * message) the IP was banned for wrong codes.
     */
    private StepResult verifyCode(Session s, Purpose purpose, EmailAddress email, @Nullable Component notice)
            throws TimeoutException {
        boolean reset = purpose == Purpose.RESET;
        Component error = notice;
        while (true) {
            Response r = promptCode(s, dialogs.code(reset, email.masked(), error));
            if (r.is(DialogFactory.RESEND)) {
                SendResult send = sendCode(s, purpose, email);
                error = send.message() != null ? send.message() : messages.get("info.code-resent");
                continue;
            }
            if (r.is(DialogFactory.BACK)) {
                return StepResult.back(null);
            }
            if (!r.is(DialogFactory.SUBMIT)) {
                return StepResult.cancel();
            }
            CodeService.Check check = codes.verify(purpose, s.key, r.text("code").trim(), s.ipKey);
            if (check == CodeService.Check.WRONG || check == CodeService.Check.BURNED) {
                // Wrong codes count against the IP like wrong passwords.
                limiter.recordFailure(s.ipKey);
                Component ban = banMessage(s);
                if (ban != null) {
                    return StepResult.deny(ban);
                }
            }
            switch (check) {
                case OK -> {
                    return StepResult.done();
                }
                case WRONG -> error = messages.get("error.code-wrong");
                case BURNED, NONE -> {
                    return StepResult.back(messages.get("error.code-expired"));
                }
            }
        }
    }

    /**
     * Result of one send. {@code sent} is true when a code was issued and is, or may still be, on its way; then
     * {@code message} is an optional notice for the code dialog. When false, {@code message} is the error.
     */
    private record SendResult(boolean sent, @Nullable Component message) {
        static SendResult ok() { return new SendResult(true, null); }
        static SendResult slow(Component notice) { return new SendResult(true, notice); }
        static SendResult failed(Component error) { return new SendResult(false, error); }
    }

    /** Why a code cannot be sent right now, or null if it can. Issues nothing. */
    private @Nullable Component sendBlocked(Session s, Purpose purpose) {
        if (purpose == Purpose.REGISTER && s.registerSends >= MAX_REGISTER_SENDS_PER_CONNECTION) {
            return messages.get("error.too-many-codes");
        }
        return codes.cooldownRemaining(purpose, s.key, s.ipKey).map(this::cooldownMessage).orElse(null);
    }

    private Component cooldownMessage(Duration remaining) {
        long seconds = Math.max(1, (remaining.toMillis() + 999) / 1000);
        return messages.get("error.code-cooldown", Placeholder.unparsed("seconds", Long.toString(seconds)));
    }

    /** Issues a code and emails it. */
    private SendResult sendCode(Session s, Purpose purpose, EmailAddress email) {
        if (purpose == Purpose.REGISTER && s.registerSends >= MAX_REGISTER_SENDS_PER_CONNECTION) {
            return SendResult.failed(messages.get("error.too-many-codes"));
        }
        String ip = s.ipKey;
        CodeService.Issue issue = codes.issue(purpose, s.key, ip);
        if (issue instanceof CodeService.CoolingDown wait) {
            return SendResult.failed(cooldownMessage(wait.remaining()));
        }
        CodeService.Issued issued = (CodeService.Issued) issue;
        String code = issued.code();
        Map<String, String> vars = Map.of(
                "name", s.name,
                "code", code,
                "minutes", Long.toString(config.codeValidity().toMinutes()));
        boolean register = purpose == Purpose.REGISTER;
        String subject = messages.plain(register ? "email.register.subject" : "email.reset.subject", vars);
        String body = messages.plain(register ? "email.register.body" : "email.reset.body", vars);
        Sending sending = mail.send(email.value(), subject, body);
        try {
            sending.done().get(MAIL_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            if (sending.withdraw().getAsBoolean()) {
                // Still waiting for a mail thread: it will never go out now, so treat it as a failure and refund.
                logger.warning("The " + purpose + " email for " + s.name + " was still queued after "
                        + MAIL_SEND_TIMEOUT_SECONDS + " s; withdrawn");
                codes.cancelIssue(purpose, s.key, ip, issued);
                return SendResult.failed(messages.get("error.email-send-failed"));
            }
            // Already handed to the SMTP client, which may still deliver it: keep the code and count the send.
            logger.warning("Sending the " + purpose + " email for " + s.name + " is taking longer than "
                    + MAIL_SEND_TIMEOUT_SECONDS + " s; the code stays valid");
            countSend(s, purpose);
            return SendResult.slow(messages.get("error.email-slow"));
        } catch (ExecutionException e) {
            logger.log(Level.WARNING, "Could not send " + purpose + " email for " + s.name, e);
            codes.cancelIssue(purpose, s.key, ip, issued);
            return SendResult.failed(messages.get("error.email-send-failed"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!sending.withdraw().getAsBoolean()) {
                // It may still arrive; keep the code, like the slow path.
                countSend(s, purpose);
                return SendResult.slow(messages.get("error.email-slow"));
            }
            codes.cancelIssue(purpose, s.key, ip, issued);
            return SendResult.failed(messages.get("error.email-send-failed"));
        }
        countSend(s, purpose);
        return SendResult.ok();
    }

    private static void countSend(Session s, Purpose purpose) {
        if (purpose == Purpose.REGISTER) {
            s.registerSends++;
        }
    }

    /** Counts one wrong password. Returns the disconnect message once a limit is reached. */
    private @Nullable Component recordFailure(Session s) {
        limiter.recordFailure(s.ipKey);
        s.failures++;
        Component ban = banMessage(s);
        if (ban != null) {
            return ban;
        }
        if (s.failures >= config.maxAttemptsPerConnection()) {
            return messages.get("kick.too-many-attempts");
        }
        return null;
    }

    /** The disconnect message if this connection's IP is banned right now, else null. */
    private @Nullable Component banMessage(Session s) {
        Optional<Duration> ban = limiter.banRemaining(s.ipKey);
        if (ban.isEmpty()) {
            return null;
        }
        long minutes = Math.max(1, (ban.get().toSeconds() + 59) / 60);
        return messages.get("kick.ip-banned", Placeholder.unparsed("minutes", Long.toString(minutes)));
    }

    /**
     * Like {@link #prompt} for dialogs that hold the name after the server password passed: idling until the
     * timeout costs this IP a failure, so it cannot park a name for free.
     */
    private Response promptHolding(Session s, Function<String, Dialog> dialog) throws TimeoutException {
        try {
            return prompt(s, dialog);
        } catch (TimeoutException e) {
            limiter.recordFailure(s.ipKey);
            throw e;
        }
    }

    private Response prompt(Session s, Function<String, Dialog> dialog) throws TimeoutException {
        return show(s, dialog, Duration.between(clock.instant(), s.deadline));
    }

    /** The server-password dialog also waits at most min(login timeout, 30 s) per display. */
    private Response promptServerPassword(Session s, Function<String, Dialog> dialog) throws TimeoutException {
        Duration own = min(config.loginTimeout(), SERVER_PASSWORD_TIMEOUT);
        return show(s, dialog, min(own, Duration.between(clock.instant(), s.deadline)));
    }

    /**
     * Code dialogs get the longer email wait; afterwards the player gets a fresh login timeout. Both are capped by
     * the session's hard deadline, so resend loops cannot keep a connection open forever.
     */
    private Response promptCode(Session s, Function<String, Dialog> dialog) throws TimeoutException {
        Response response = show(s, dialog, config.codeWaitTimeout());
        s.deadline = clock.instant().plus(config.loginTimeout());
        return response;
    }

    private Response show(Session s, Function<String, Dialog> dialog, Duration ownTimeout) throws TimeoutException {
        Duration left = min(ownTimeout, Duration.between(clock.instant(), s.hardDeadline));
        if (left.isNegative() || left.isZero()) {
            throw new TimeoutException();
        }
        return waiter.await(s.waitKey, dialog, d -> s.connection.getAudience().showDialog(d), left);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
