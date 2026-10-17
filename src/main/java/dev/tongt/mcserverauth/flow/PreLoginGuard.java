package dev.tongt.mcserverauth.flow;

import dev.tongt.mcserverauth.config.Messages;
import dev.tongt.mcserverauth.security.AttemptLimiter;
import dev.tongt.mcserverauth.security.IpKey;
import dev.tongt.mcserverauth.storage.Account;
import dev.tongt.mcserverauth.storage.AccountStore;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Checks that run before the configuration phase: IP ban, connections per IP, duplicate name, wrong letter case. */
public final class PreLoginGuard implements Listener {
    private final Messages messages;
    private final AttemptLimiter limiter;
    private final AccountStore store;
    private final LoginFlow flow;
    private final Logger logger;

    public PreLoginGuard(Messages messages, AttemptLimiter limiter, AccountStore store, LoginFlow flow, Logger logger) {
        this.messages = messages;
        this.limiter = limiter;
        this.store = store;
        this.flow = flow;
        this.logger = logger;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        String name = event.getName();

        Optional<Duration> ban = limiter.banRemaining(IpKey.of(event.getAddress()));
        if (ban.isPresent()) {
            long minutes = Math.max(1, (ban.get().toSeconds() + 59) / 60);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    messages.get("kick.ip-banned", Placeholder.unparsed("minutes", Long.toString(minutes))));
            return;
        }

        // Each connection in the flow holds a name; cap them per IP so one host cannot hold many names.
        if (flow.tooManyConnections(event.getAddress())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get("kick.too-many-connections"));
            return;
        }

        // In offline mode a second login with the same name would kick the real player. Refuse the newcomer.
        // isNameBusy covers players in the world too; it is thread-safe, unlike Bukkit.getPlayerExact here.
        if (flow.isNameBusy(name)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get("kick.name-in-use"));
            return;
        }

        try {
            Optional<Account> account = store.find(name);
            if (account.isPresent() && !account.get().displayName().equals(name)) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        messages.get("kick.wrong-case", Placeholder.unparsed("name", account.get().displayName())));
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error in pre-login check for " + name, e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get("kick.unavailable"));
        }
    }
}
