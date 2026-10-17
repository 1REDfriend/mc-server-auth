package dev.tongt.mcserverauth.command;

import dev.tongt.mcserverauth.config.AuthConfig;
import dev.tongt.mcserverauth.config.Messages;
import dev.tongt.mcserverauth.email.CodeService;
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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AuthAdminCommand implements BasicCommand {
    private static final List<String> SUBCOMMANDS = List.of("reset", "setserverpassword", "info");

    private final Plugin plugin;
    private final AuthConfig config;
    private final Messages messages;
    private final DialogFactory dialogs;
    private final DialogWaiter waiter;
    private final AccountStore store;
    private final PasswordHasher hasher;
    private final PasswordRules rules;
    private final SessionCache sessions;
    private final CodeService codes;
    private final Logger logger;

    public AuthAdminCommand(Plugin plugin, AuthConfig config, Messages messages, DialogFactory dialogs,
                            DialogWaiter waiter, AccountStore store, PasswordHasher hasher, PasswordRules rules,
                            SessionCache sessions, CodeService codes) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.dialogs = dialogs;
        this.waiter = waiter;
        this.store = store;
        this.hasher = hasher;
        this.rules = rules;
        this.sessions = sessions;
        this.codes = codes;
        this.logger = plugin.getLogger();
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reset" -> {
                if (args.length != 2) {
                    sender.sendMessage(messages.get("command.usage"));
                    return;
                }
                async(sender, () -> reset(sender, args[1]));
            }
            case "info" -> {
                if (args.length != 2) {
                    sender.sendMessage(messages.get("command.usage"));
                    return;
                }
                async(sender, () -> info(sender, args[1]));
            }
            case "setserverpassword" -> {
                if (sender instanceof Player player) {
                    // A player never gets the argument form: whatever they typed is already in the server log.
                    if (args.length >= 2) {
                        player.sendMessage(messages.get("command.password-arg-ignored"));
                    }
                    startServerPasswordDialog(player);
                } else if (args.length >= 2) {
                    String password = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    sender.sendMessage(messages.get("command.console-password-logged"));
                    async(sender, () -> setServerPassword(sender, password, password));
                } else {
                    sender.sendMessage(messages.get("command.usage"));
                }
            }
            default -> sender.sendMessage(messages.get("command.usage"));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("info"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "mcserverauth.admin";
    }

    private void async(CommandSender sender, Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Unexpected error in /authadmin", e);
                sender.sendMessage(messages.get("command.error"));
            }
        });
    }

    private void reset(CommandSender sender, String name) {
        TagResolver namePlaceholder = Placeholder.unparsed("name", name);
        try {
            boolean deleted = store.delete(name);
            String key = AccountStore.key(name);
            codes.invalidate(key);
            sessions.forget(key);
            if (!deleted) {
                sender.sendMessage(messages.get("command.no-such-account", namePlaceholder));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayerExact(name);
                if (online != null) {
                    online.kick(messages.get("kick.account-reset"));
                }
            });
            sender.sendMessage(messages.get("command.reset-done", namePlaceholder));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error in /authadmin reset " + name, e);
            sender.sendMessage(messages.get("command.error"));
        }
    }

    private void info(CommandSender sender, String name) {
        try {
            Optional<Account> found = store.find(name);
            if (found.isEmpty()) {
                sender.sendMessage(messages.get("command.no-such-account", Placeholder.unparsed("name", name)));
                return;
            }
            Account a = found.get();
            sender.sendMessage(messages.get("command.info",
                    Placeholder.unparsed("name", a.displayName()),
                    Placeholder.unparsed("email", a.email()),
                    Placeholder.unparsed("created", Instant.ofEpochMilli(a.createdAt()).toString()),
                    Placeholder.unparsed("last_login", a.lastLoginAt() == null ? "-" : Instant.ofEpochMilli(a.lastLoginAt()).toString()),
                    Placeholder.unparsed("last_ip", a.lastIp() == null ? "-" : a.lastIp())));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error in /authadmin info " + name, e);
            sender.sendMessage(messages.get("command.error"));
        }
    }

    /** Returns the problem to show, or null when the password was stored. */
    private Component setServerPassword(CommandSender sender, String password, String confirm) {
        Optional<String> problem = rules.check(password, confirm, pw -> false);
        if (problem.isPresent()) {
            Component error = messages.get(problem.get(), rules.placeholders());
            sender.sendMessage(error);
            return error;
        }
        try {
            store.setServerPasswordHash(hasher.hash(password));
            // A remembered session skips the server password too; make everyone prove the new one.
            sessions.clear();
            sender.sendMessage(messages.get("command.server-password-set"));
            return null;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error in /authadmin setserverpassword", e);
            Component error = messages.get("command.error");
            sender.sendMessage(error);
            return error;
        }
    }

    private void startServerPasswordDialog(Player player) {
        if (!waiter.claim(player.getUniqueId())) {
            player.sendMessage(messages.get("command.dialog-open"));
            return;
        }
        try {
            Thread.ofVirtual().name("McServerAuth-setserverpassword").start(() -> {
                try {
                    serverPasswordDialog(player);
                } catch (RuntimeException e) {
                    logger.log(Level.SEVERE, "Unexpected error in /authadmin setserverpassword", e);
                    player.closeDialog();
                    player.sendMessage(messages.get("command.error"));
                } finally {
                    waiter.release(player.getUniqueId());
                }
            });
        } catch (RuntimeException e) {
            waiter.release(player.getUniqueId());
            throw e;
        }
    }

    private void serverPasswordDialog(Player player) {
        Component error = null;
        try {
            while (true) {
                Response r = waiter.await(DialogWaiter.keyOf(player),
                        dialogs.newPassword("dialog.set-server-password.title", error, true), player::showDialog,
                        config.loginTimeout());
                if (!r.is(DialogFactory.SUBMIT)) {
                    player.closeDialog();
                    return;
                }
                error = setServerPassword(player, r.text("password"), r.text("confirm"));
                if (error == null) {
                    player.closeDialog();
                    return;
                }
            }
        } catch (TimeoutException e) {
            player.closeDialog();
        }
    }
}
