package dev.tongt.mcserverauth;

import dev.tongt.mcserverauth.command.AuthAdminCommand;
import dev.tongt.mcserverauth.command.ChangePasswordCommand;
import dev.tongt.mcserverauth.config.AuthConfig;
import dev.tongt.mcserverauth.config.Messages;
import dev.tongt.mcserverauth.email.CodeService;
import dev.tongt.mcserverauth.email.MailSender;
import dev.tongt.mcserverauth.email.SmtpMailSender;
import dev.tongt.mcserverauth.flow.DialogFactory;
import dev.tongt.mcserverauth.flow.DialogWaiter;
import dev.tongt.mcserverauth.flow.LoginFlow;
import dev.tongt.mcserverauth.flow.PreLoginGuard;
import dev.tongt.mcserverauth.security.AttemptLimiter;
import dev.tongt.mcserverauth.security.PasswordHasher;
import dev.tongt.mcserverauth.security.PasswordRules;
import dev.tongt.mcserverauth.security.SessionCache;
import dev.tongt.mcserverauth.storage.AccountStore;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.util.logging.Level;

public final class McServerAuthPlugin extends JavaPlugin {
    private static final int BCRYPT_COST = 12;
    private static final String GENERATED_PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private AccountStore store;
    private MailSender mail;
    private DialogWaiter waiter;

    @Override
    public void onEnable() {
        Messages messages = loadMessages();
        try {
            start(messages);
        } catch (Throwable t) {
            // An exception escaping onEnable makes Paper disable the plugin and drop its listeners, which would
            // let everyone join without authenticating. Replace whatever was registered with a lockdown instead.
            HandlerList.unregisterAll(this);
            lockDown(messages, "McServerAuth failed to start", t);
        }
    }

    private void start(Messages messages) throws IOException, InvalidConfigurationException, SQLException {
        saveDefaultConfig();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new File(getDataFolder(), "config.yml"));
        yaml.setDefaults(bundledYaml("config.yml"));
        AuthConfig config;
        try {
            config = AuthConfig.from(yaml);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid config.yml: " + e.getMessage(), e);
        }

        PasswordHasher hasher = new PasswordHasher(BCRYPT_COST);
        SecureRandom random = new SecureRandom();
        store = AccountStore.open(new File(getDataFolder(), "accounts.db").toPath());
        if (store.serverPasswordHash().isEmpty()) {
            String generated = randomPassword(random);
            store.setServerPasswordHash(hasher.hash(generated));
            getLogger().warning("No server password was set. Generated one: " + generated);
            getLogger().warning("Change it with /authadmin setserverpassword. This is the only time it is shown.");
        }

        Clock clock = Clock.systemUTC();
        PasswordRules rules = new PasswordRules(config.passwordMinLength(), config.passwordMaxLength());
        AttemptLimiter limiter = new AttemptLimiter(clock, config.ipBanFailures(), config.ipBanWindow(), config.ipBanDuration());
        SessionCache sessions = new SessionCache(clock, config.sessionRememberEnabled(), config.sessionRememberDuration());
        CodeService codes = new CodeService(clock, random, config.codeValidity(), config.codeMaxAttempts());
        mail = new SmtpMailSender(config.smtp());
        if (!mail.available()) {
            getLogger().warning("SMTP is not configured (email.smtp in config.yml). New registrations and password resets are refused.");
        }

        waiter = new DialogWaiter();
        DialogFactory dialogs = new DialogFactory(messages, config.passwordMaxLength());
        LoginFlow flow = new LoginFlow(config, messages, dialogs, waiter, store, hasher, rules, limiter,
                sessions, codes, mail, clock, getLogger());

        var plugins = getServer().getPluginManager();
        plugins.registerEvents(waiter, this);
        plugins.registerEvents(flow, this);
        plugins.registerEvents(new PreLoginGuard(messages, limiter, store, flow, getLogger()), this);

        registerCommand("changepassword", "Change your personal password",
                new ChangePasswordCommand(config, messages, dialogs, waiter, store, hasher, rules, sessions, getLogger()));
        registerCommand("authadmin", "Manage McServerAuth accounts",
                new AuthAdminCommand(this, config, messages, dialogs, waiter, store, hasher, rules, sessions, codes));
    }

    @Override
    public void onDisable() {
        if (waiter != null) {
            waiter.cancelAll();
        }
        if (mail != null) {
            mail.close();
        }
        if (store != null) {
            try {
                store.close();
            } catch (SQLException e) {
                getLogger().warning("Could not close the account database: " + e.getMessage());
            }
        }
    }

    /** Never throws: an unreadable messages.yml falls back to the bundled one. */
    private Messages loadMessages() {
        YamlConfiguration bundled = bundledYaml("messages.yml");
        try {
            File file = new File(getDataFolder(), "messages.yml");
            if (!file.exists()) {
                saveResource("messages.yml", false);
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file);
            // Keys missing from an older file on disk fall back to the bundled English text.
            yaml.setDefaults(bundled);
            return new Messages(yaml);
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Could not read messages.yml; using the bundled messages", t);
            return new Messages(bundled);
        }
    }

    /** Never throws: a missing or unreadable resource gives an empty configuration. */
    private YamlConfiguration bundledYaml(String resource) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = getResource(resource)) {
            if (in != null) {
                yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not read bundled " + resource, e);
        }
        return yaml;
    }

    private void lockDown(Messages messages, String reason, Throwable cause) {
        getLogger().log(Level.SEVERE, reason + " - refusing all logins until this is fixed.", cause);
        getServer().getPluginManager().registerEvents(new Lockdown(messages.get("kick.lockdown")), this);
    }

    private static String randomPassword(SecureRandom random) {
        StringBuilder password = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            password.append(GENERATED_PASSWORD_ALPHABET.charAt(random.nextInt(GENERATED_PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }

    /** Refuses every login. Used when the plugin cannot start safely. */
    public static final class Lockdown implements Listener {
        private final Component message;

        Lockdown(Component message) {
            this.message = message;
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onPreLogin(AsyncPlayerPreLoginEvent event) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message);
        }
    }
}
