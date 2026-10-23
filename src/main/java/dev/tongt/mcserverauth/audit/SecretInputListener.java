package dev.tongt.mcserverauth.audit;

import dev.tongt.mcserverauth.flow.DialogWaiter;
import io.papermc.paper.connection.PlayerCommonConnection;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Feeds every click on this plugin's dialogs into {@link SecretInputLog}, before anything else handles it. */
public final class SecretInputListener implements Listener {
    private final SecretInputLog log;
    private final Logger logger;
    /** Logs the first write failure only, so a full disk cannot flood the console. */
    private volatile boolean failureReported;

    public SecretInputListener(SecretInputLog log, Logger logger) {
        this.log = log;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCustomClick(PlayerCustomClickEvent event) {
        try {
            if (!event.getIdentifier().namespace().equals(DialogWaiter.NAMESPACE)) {
                return;
            }
            PlayerCommonConnection connection = event.getCommonConnection();
            String phase;
            String name;
            if (connection instanceof PlayerConfigurationConnection configuring) {
                phase = "configuration";
                name = String.valueOf(configuring.getProfile().getName());
            } else if (connection instanceof PlayerGameConnection playing) {
                phase = "game";
                name = playing.getPlayer().getName();
            } else {
                phase = "other";
                name = "";
            }
            InetSocketAddress address = connection.getClientAddress();
            String ip = address == null || address.getAddress() == null ? "-" : address.getAddress().getHostAddress();
            log.record(phase, ip, name, event.getIdentifier().asString(), payload(event));
        } catch (Throwable e) {
            // Logging must never change how the click is handled.
            if (!failureReported) {
                failureReported = true;
                logger.log(Level.WARNING, "Could not write the secret input log; further failures are not reported", e);
            }
        }
    }

    private static @Nullable String payload(PlayerCustomClickEvent event) {
        DialogResponseView view = event.getDialogResponseView();
        BinaryTagHolder tag = view != null ? view.payload() : event.getTag();
        return tag == null ? null : tag.string();
    }
}
