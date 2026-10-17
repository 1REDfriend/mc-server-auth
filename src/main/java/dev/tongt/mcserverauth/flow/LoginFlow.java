package dev.tongt.mcserverauth.flow;

import dev.tongt.mcserverauth.config.AuthConfig;
import dev.tongt.mcserverauth.config.Messages;
import dev.tongt.mcserverauth.email.CodeService;
import dev.tongt.mcserverauth.email.CodeService.Purpose;
import dev.tongt.mcserverauth.email.EmailAddress;
import dev.tongt.mcserverauth.email.MailSender;
import dev.tongt.mcserverauth.flow.DialogWaiter.Response;
import dev.tongt.mcserverauth.security.AttemptLimiter;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the whole login during the configuration phase, before the player enters the world.
 * Each handler call blocks Paper's async configuration thread until the player passes or is disconnected.
 */
public final class LoginFlow implements Listener {
    private static final int MAX_REGISTER_SENDS_PER_CONNECTION = 3;
    private static final long MAIL_SEND_TIMEOUT_SECONDS = 30;

    private enum Step { DONE, BACK, CANCEL }

    private record StepResult(Step step, @Nullable Component message) {
        static StepResult done() { return new StepResult(Step.DONE, null); }
        static StepResult back(@Nullable Component message) { return new StepResult(Step.BACK, message); }
        static StepResult cancel() { return new StepResult(Step.CANCEL, null); }
    }

    /** Per-connection state. */
    private static final class Session {
        final PlayerConfigurationConnection connection;
        final UUID id;
        final String name;
        final String key;
        final InetAddress ip;
        Instant deadline;
        int failures;
        int registerSends;

        Session(PlayerConfigurationConnection connection, UUID id, String name, InetAddress ip, Instant deadline) {
            this.connection = connection;
            this.id = id;
            this.name = name;
            this.key = AccountStore.key(name);
            this.ip = ip;
            this.deadline = deadline;
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

    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

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

    /** True while a connection with this name is going through the login dialogs. */
    public boolean isNameBusy(String name) {
        return inFlight.contains(AccountStore.key(name));
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
        if (authenticated.contains(id)) {
            return; // reconfiguration of a player who already logged in
        }
        String key = AccountStore.key(name);
        if (!inFlight.add(key)) {
            connection.disconnect(messages.get("kick.name-in-use"));
            return;
        }
        try {
            Session session = new Session(connection, id, name, connection.getClientAddress().getAddress(),
                    clock.instant().plus(config.loginTimeout()));
            Component denial = run(session);
            if (denial != null) {
                connection.disconnect(denial);
            } else {
                authenticated.add(id);
                connection.getAudience().closeDialog();
            }
        } catch (TimeoutException e) {
            connection.disconnect(messages.get("kick.timeout"));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error while logging in " + name, e);
            connection.disconnect(messages.get("kick.unavailable"));
        } finally {
            inFlight.remove(key);
            waiter.cancel(id);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        authenticated.remove(event.getPlayer().getUniqueId());
    }

    /** Returns null when the player may enter, otherwise the disconnect message. */
    private @Nullable Component run(Session s) throws TimeoutException, SQLException {
        if (sessions.isRemembered(s.key, s.ip)) {
            store.recordLogin(s.name, clock.millis(), s.ip.getHostAddress());
            return null;
        }
        String serverHash = store.serverPasswordHash()
                .orElseThrow(() -> new SQLException("Server password is not set"));

        Component error = null;
        while (true) {
            Response r = prompt(s, dialogs.serverPassword(error));
            if (!r.is(DialogFactory.SUBMIT)) {
                return messages.get("kick.cancelled");
            }
            if (hasher.verify(r.text("password"), serverHash)) {
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
        if (denial == null) {
            store.recordLogin(s.name, clock.millis(), s.ip.getHostAddress());
            sessions.remember(s.key, s.ip);
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
            String password = r.text("password");
            Optional<String> problem = rules.check(password, r.text("confirm"), pw -> hasher.verify(pw, serverHash));
            if (problem.isPresent()) {
                error = messages.get(problem.get(), rules.placeholders());
                continue;
            }
            Optional<EmailAddress> email = EmailAddress.parse(r.text("email"));
            if (email.isEmpty()) {
                error = messages.get("error.email-invalid");
                continue;
            }
            if (store.countByEmail(email.get().value()) >= config.maxAccountsPerEmail()) {
                error = messages.get("error.email-limit");
                continue;
            }
            String passwordHash = hasher.hash(password);
            Component sendError = sendCode(s, Purpose.REGISTER, email.get());
            if (sendError != null) {
                error = sendError;
                continue;
            }
            Step step = verifyCode(s, Purpose.REGISTER, email.get());
            if (step == Step.CANCEL) {
                return messages.get("kick.cancelled");
            }
            if (step == Step.BACK) {
                error = null;
                continue;
            }
            store.create(s.name, passwordHash, email.get().value(), clock.millis());
            return null;
        }
    }

    private @Nullable Component login(Session s, Account account, String serverHash) throws TimeoutException, SQLException {
        Component error = null;
        while (true) {
            Response r = prompt(s, dialogs.login(error));
            if (r.is(DialogFactory.FORGOT)) {
                StepResult result = forgot(s, account, serverHash);
                if (result.step() == Step.DONE) {
                    return null;
                }
                if (result.step() == Step.CANCEL) {
                    return messages.get("kick.cancelled");
                }
                error = result.message();
                continue;
            }
            if (!r.is(DialogFactory.SUBMIT)) {
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
        while (true) {
            Response r = prompt(s, dialogs.forgotMenu(email.masked(), menuError));
            if (r.is(DialogFactory.BACK)) {
                return StepResult.back(null);
            }
            if (r.is(DialogFactory.SEND_CODE)) {
                if (!mail.available()) {
                    return StepResult.back(messages.get("error.reset-unavailable"));
                }
                Component sendError = sendCode(s, Purpose.RESET, email);
                if (sendError != null) {
                    menuError = sendError;
                    continue;
                }
            } else if (r.is(DialogFactory.HAVE_CODE)) {
                if (!codes.hasLiveCode(Purpose.RESET, s.key)) {
                    menuError = messages.get("error.no-live-code");
                    continue;
                }
            } else {
                return StepResult.cancel();
            }
            break;
        }

        Step step = verifyCode(s, Purpose.RESET, email);
        if (step == Step.BACK) {
            return StepResult.back(null);
        }
        if (step == Step.CANCEL) {
            return StepResult.cancel();
        }

        Component error = null;
        while (true) {
            Response r = prompt(s, dialogs.newPassword("dialog.reset-password.title", error, false));
            if (!r.is(DialogFactory.SUBMIT)) {
                return StepResult.cancel();
            }
            String password = r.text("password");
            Optional<String> problem = rules.check(password, r.text("confirm"), pw -> hasher.verify(pw, serverHash));
            if (problem.isPresent()) {
                error = messages.get(problem.get(), rules.placeholders());
                continue;
            }
            store.updatePassword(s.name, hasher.hash(password));
            sessions.forget(s.key);
            return StepResult.done();
        }
    }

    /** Code entry loop. DONE = correct code, BACK = player went back, CANCEL = closed or cancelled. */
    private Step verifyCode(Session s, Purpose purpose, EmailAddress email) throws TimeoutException {
        boolean reset = purpose == Purpose.RESET;
        Component error = null;
        while (true) {
            Response r = promptCode(s, dialogs.code(reset, email.masked(), error));
            if (r.is(DialogFactory.RESEND)) {
                Component sendError = sendCode(s, purpose, email);
                error = sendError != null ? sendError : messages.get("info.code-resent");
                continue;
            }
            if (r.is(DialogFactory.BACK)) {
                return Step.BACK;
            }
            if (!r.is(DialogFactory.SUBMIT)) {
                return Step.CANCEL;
            }
            switch (codes.verify(purpose, s.key, r.text("code").trim())) {
                case OK -> {
                    return Step.DONE;
                }
                case WRONG -> error = messages.get("error.code-wrong");
                case BURNED, NONE -> error = messages.get("error.code-expired");
            }
        }
    }

    /** Issues a code and emails it. Returns null on success, otherwise the error to show. */
    private @Nullable Component sendCode(Session s, Purpose purpose, EmailAddress email) {
        if (purpose == Purpose.REGISTER && s.registerSends >= MAX_REGISTER_SENDS_PER_CONNECTION) {
            return messages.get("error.too-many-codes");
        }
        String ip = s.ip.getHostAddress();
        CodeService.Issue issue = codes.issue(purpose, s.key, ip);
        if (issue instanceof CodeService.CoolingDown wait) {
            long seconds = Math.max(1, (wait.remaining().toMillis() + 999) / 1000);
            return messages.get("error.code-cooldown", Placeholder.unparsed("seconds", Long.toString(seconds)));
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
        try {
            mail.send(email.value(), subject, body).get(MAIL_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            logger.log(Level.WARNING, "Could not send " + purpose + " email for " + s.name, e);
            codes.cancelIssue(purpose, s.key, ip, issued);
            return messages.get("error.email-send-failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            codes.cancelIssue(purpose, s.key, ip, issued);
            return messages.get("error.email-send-failed");
        }
        if (register) {
            s.registerSends++;
        }
        return null;
    }

    /** Counts one wrong password. Returns the disconnect message once a limit is reached. */
    private @Nullable Component recordFailure(Session s) {
        limiter.recordFailure(s.ip);
        s.failures++;
        Optional<Duration> ban = limiter.banRemaining(s.ip);
        if (ban.isPresent()) {
            long minutes = Math.max(1, (ban.get().toSeconds() + 59) / 60);
            return messages.get("kick.ip-banned", Placeholder.unparsed("minutes", Long.toString(minutes)));
        }
        if (s.failures >= config.maxAttemptsPerConnection()) {
            return messages.get("kick.too-many-attempts");
        }
        return null;
    }

    private Response prompt(Session s, Dialog dialog) throws TimeoutException {
        Duration left = Duration.between(clock.instant(), s.deadline);
        if (left.isNegative() || left.isZero()) {
            throw new TimeoutException();
        }
        return waiter.await(s.id, () -> s.connection.getAudience().showDialog(dialog), left);
    }

    /** Code dialogs get the longer email wait; afterwards the player gets a fresh login timeout. */
    private Response promptCode(Session s, Dialog dialog) throws TimeoutException {
        Response response = waiter.await(s.id, () -> s.connection.getAudience().showDialog(dialog), config.codeWaitTimeout());
        s.deadline = clock.instant().plus(config.loginTimeout());
        return response;
    }
}
