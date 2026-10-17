package dev.tongt.mcserverauth.command;

import dev.tongt.mcserverauth.config.AuthConfig;
import dev.tongt.mcserverauth.config.Messages;
import dev.tongt.mcserverauth.flow.DialogFactory;
import dev.tongt.mcserverauth.flow.DialogWaiter;
import dev.tongt.mcserverauth.flow.DialogWaiter.Response;
import dev.tongt.mcserverauth.security.PasswordHasher;
import dev.tongt.mcserverauth.security.PasswordRules;
import dev.tongt.mcserverauth.security.SessionCache;
import dev.tongt.mcserverauth.storage.Account;
import dev.tongt.mcserverauth.storage.AccountStore;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ChangePasswordCommand implements BasicCommand {
    private final AuthConfig config;
    private final Messages messages;
    private final DialogFactory dialogs;
    private final DialogWaiter waiter;
    private final AccountStore store;
    private final PasswordHasher hasher;
    private final PasswordRules rules;
    private final SessionCache sessions;
    private final Logger logger;

    public ChangePasswordCommand(AuthConfig config, Messages messages, DialogFactory dialogs, DialogWaiter waiter,
                                 AccountStore store, PasswordHasher hasher, PasswordRules rules,
                                 SessionCache sessions, Logger logger) {
        this.config = config;
        this.messages = messages;
        this.dialogs = dialogs;
        this.waiter = waiter;
        this.store = store;
        this.hasher = hasher;
        this.rules = rules;
        this.sessions = sessions;
        this.logger = logger;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(messages.get("command.players-only"));
            return;
        }
        if (!waiter.claim(player.getUniqueId())) {
            player.sendMessage(messages.get("command.dialog-open"));
            return;
        }
        try {
            Thread.ofVirtual().name("McServerAuth-changepassword").start(() -> {
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
        return "mcserverauth.changepassword";
    }

    private void run(Player player) {
        Component error = null;
        int failures = 0;
        try {
            while (true) {
                Response r = waiter.await(DialogWaiter.keyOf(player), dialogs.changePassword(error), player::showDialog,
                        config.loginTimeout());
                if (!r.is(DialogFactory.SUBMIT)) {
                    player.closeDialog();
                    return;
                }
                Optional<Account> account = store.find(player.getName());
                if (account.isEmpty()) {
                    player.closeDialog();
                    player.sendMessage(messages.get("command.error"));
                    return;
                }
                if (!hasher.verify(r.text("current"), account.get().passwordHash())) {
                    failures++;
                    if (failures >= config.maxAttemptsPerConnection()) {
                        player.closeDialog();
                        player.sendMessage(messages.get("error.wrong-password"));
                        return;
                    }
                    error = messages.get("error.wrong-password");
                    continue;
                }
                String serverHash = store.serverPasswordHash().orElse(null);
                String password = r.text("password");
                Optional<String> problem = rules.check(password, r.text("confirm"),
                        pw -> serverHash != null && hasher.verify(pw, serverHash));
                if (problem.isPresent()) {
                    error = messages.get(problem.get(), rules.placeholders());
                    continue;
                }
                if (!store.updatePassword(player.getName(), hasher.hash(password))) {
                    // The account was deleted by an admin while the dialog was open.
                    player.closeDialog();
                    player.sendMessage(messages.get("command.error"));
                    return;
                }
                sessions.forget(AccountStore.key(player.getName()));
                player.closeDialog();
                player.sendMessage(messages.get("command.password-changed"));
                return;
            }
        } catch (TimeoutException e) {
            player.closeDialog();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error in /changepassword for " + player.getName(), e);
            player.closeDialog();
            player.sendMessage(messages.get("command.error"));
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Unexpected error in /changepassword for " + player.getName(), e);
            player.closeDialog();
            player.sendMessage(messages.get("command.error"));
        }
    }
}
