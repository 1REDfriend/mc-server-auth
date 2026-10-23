package dev.tongt.mcserverauth.command;

import dev.tongt.mcserverauth.config.AuthConfig;
import dev.tongt.mcserverauth.config.Messages;
import dev.tongt.mcserverauth.email.CodeService;
import dev.tongt.mcserverauth.email.CodeService.Purpose;
import dev.tongt.mcserverauth.email.EmailAddress;
import dev.tongt.mcserverauth.flow.CodeMailer;
import dev.tongt.mcserverauth.flow.CodeMailer.SendResult;
import dev.tongt.mcserverauth.flow.DialogFactory;
import dev.tongt.mcserverauth.flow.DialogWaiter;
import dev.tongt.mcserverauth.flow.DialogWaiter.Response;
import dev.tongt.mcserverauth.security.IpKey;
import dev.tongt.mcserverauth.security.PasswordHasher;
import dev.tongt.mcserverauth.storage.Account;
import dev.tongt.mcserverauth.storage.AccountStore;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.dialog.Dialog;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adds or changes the email of the player's own account. Asks for the current password, so someone at an
 * unattended computer cannot redirect password resets, then confirms the new address with an emailed code.
 */
public final class EmailCommand implements BasicCommand {
    private final AuthConfig config;
    private final Messages messages;
    private final DialogFactory dialogs;
    private final DialogWaiter waiter;
    private final AccountStore store;
    private final PasswordHasher hasher;
    private final CodeService codes;
    private final CodeMailer mailer;
    private final Logger logger;

    public EmailCommand(AuthConfig config, Messages messages, DialogFactory dialogs, DialogWaiter waiter,
                        AccountStore store, PasswordHasher hasher, CodeService codes, CodeMailer mailer,
                        Logger logger) {
        this.config = config;
        this.messages = messages;
        this.dialogs = dialogs;
        this.waiter = waiter;
        this.store = store;
        this.hasher = hasher;
        this.codes = codes;
        this.mailer = mailer;
        this.logger = logger;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(messages.get("command.players-only"));
            return;
        }
        if (!mailer.available()) {
            player.sendMessage(messages.get("command.email-unavailable"));
            return;
        }
        if (!waiter.claim(player.getUniqueId())) {
            player.sendMessage(messages.get("command.dialog-open"));
            return;
        }
        try {
            Thread.ofVirtual().name("McServerAuth-email").start(() -> {
                try {
                    run(player);
                } finally {
                    waiter.release(player.getUniqueId());
                }
            });
        } catch (RuntimeException e) {
            waiter.release(player.getUniqueId());
            throw e;
        }
    }

    @Override
    public String permission() {
        return "mcserverauth.email";
    }

    private void run(Player player) {
        try {
            Component done = flow(player);
            player.closeDialog();
            if (done != null) {
                player.sendMessage(done);
            }
        } catch (TimeoutException e) {
            player.closeDialog();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error in /email for " + player.getName(), e);
            player.closeDialog();
            player.sendMessage(messages.get("command.error"));
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Unexpected error in /email for " + player.getName(), e);
            player.closeDialog();
            player.sendMessage(messages.get("command.error"));
        }
    }

    /** Runs the dialogs. Returns the chat message to show once they close, or null for none. */
    private @Nullable Component flow(Player player) throws TimeoutException, SQLException {
        String key = AccountStore.key(player.getName());
        InetSocketAddress address = player.getAddress();
        if (address == null) {
            return messages.get("command.error");
        }
        String ipKey = IpKey.of(address.getAddress());
        Component error = null;
        int failures = 0;
        while (true) {
            Optional<Account> account = store.find(player.getName());
            if (account.isEmpty()) {
                return messages.get("command.error"); // deleted by an admin
            }
            String current = account.get().hasEmail() ? new EmailAddress(account.get().email()).masked() : null;
            Response r = await(player, dialogs.setEmail(current, error), false);
            if (!r.is(DialogFactory.SUBMIT)) {
                return null;
            }
            if (!hasher.verify(r.text("current"), account.get().passwordHash())) {
                if (++failures >= config.maxAttemptsPerConnection()) {
                    return messages.get("error.wrong-password");
                }
                error = messages.get("error.wrong-password");
                continue;
            }
            Optional<EmailAddress> email = EmailAddress.parse(r.text("email"));
            if (email.isEmpty()) {
                error = messages.get("error.email-invalid");
                continue;
            }
            if (email.get().value().equalsIgnoreCase(account.get().email())) {
                error = messages.get("error.email-same");
                continue;
            }
            // Early check so nobody waits for a code that cannot be used; re-checked atomically on update.
            if (store.countByEmail(email.get().value()) >= config.maxAccountsPerEmail()) {
                error = messages.get("error.email-limit");
                continue;
            }
            SendResult send = mailer.send(Purpose.CHANGE_EMAIL, key, player.getName(), ipKey, email.get());
            if (!send.sent()) {
                error = send.message();
                continue;
            }
            Component result = verify(player, key, ipKey, email.get(), send.message());
            if (result == null) {
                return null; // cancelled
            }
            if (result != BACK) {
                return result;
            }
            error = null;
        }
    }

    /** Marker from {@link #verify}: the player went back to the first dialog. */
    private static final Component BACK = Component.empty();

    /** Code entry. Returns the final chat message, {@link #BACK}, or null when cancelled. */
    private @Nullable Component verify(Player player, String key, String ipKey, EmailAddress email,
                                       @Nullable Component notice) throws TimeoutException, SQLException {
        Component error = notice;
        while (true) {
            Response r = await(player, dialogs.code(false, email.masked(), error), true);
            if (r.is(DialogFactory.BACK)) {
                return BACK;
            }
            if (r.is(DialogFactory.RESEND)) {
                SendResult send = mailer.send(Purpose.CHANGE_EMAIL, key, player.getName(), ipKey, email);
                error = send.message() != null ? send.message() : messages.get("info.code-resent");
                continue;
            }
            if (!r.is(DialogFactory.SUBMIT)) {
                return null;
            }
            switch (codes.verify(Purpose.CHANGE_EMAIL, key, r.text("code").trim(), ipKey)) {
                case OK -> {
                    return switch (store.updateEmailIfBelow(player.getName(), email.value(),
                            config.maxAccountsPerEmail())) {
                        case OK -> messages.get("command.email-set");
                        case LIMIT -> messages.get("error.email-limit");
                        case NO_ACCOUNT -> messages.get("command.error");
                    };
                }
                case WRONG -> error = messages.get("error.code-wrong");
                case BURNED, NONE -> {
                    return messages.get("error.code-expired");
                }
            }
        }
    }

    private Response await(Player player, Function<String, Dialog> dialog, boolean code) throws TimeoutException {
        return waiter.await(DialogWaiter.keyOf(player), dialog, player::showDialog,
                code ? config.codeWaitTimeout() : config.loginTimeout());
    }
}
