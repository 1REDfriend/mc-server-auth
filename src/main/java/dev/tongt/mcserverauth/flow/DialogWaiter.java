package dev.tongt.mcserverauth.flow;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.key.Key;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Turns dialog button clicks into a blocking call: show a dialog, wait for the player's answer. */
public final class DialogWaiter implements Listener {
    public static final String NAMESPACE = "mcserverauth";

    public record Response(String action, @Nullable DialogResponseView view) {
        public static final Response CLOSED = new Response("closed", null);

        public boolean is(String expected) {
            return action.equals(expected);
        }

        public String text(String inputKey) {
            if (view == null) {
                return "";
            }
            String value = view.getText(inputKey);
            return value == null ? "" : value;
        }
    }

    private final Map<UUID, CompletableFuture<Response>> pending = new ConcurrentHashMap<>();

    /** Blocks the calling thread. Never call from the main server thread. */
    public Response await(UUID playerId, Runnable showDialog, Duration timeout) throws TimeoutException {
        CompletableFuture<Response> future = new CompletableFuture<>();
        CompletableFuture<Response> previous = pending.put(playerId, future);
        if (previous != null) {
            previous.complete(Response.CLOSED);
        }
        try {
            showDialog.run();
            return future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.CLOSED;
        } catch (ExecutionException e) {
            return Response.CLOSED;
        } finally {
            pending.remove(playerId, future);
        }
    }

    public void cancel(UUID playerId) {
        CompletableFuture<Response> future = pending.remove(playerId);
        if (future != null) {
            future.complete(Response.CLOSED);
        }
    }

    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        Key id = event.getIdentifier();
        if (!id.namespace().equals(NAMESPACE)) {
            return;
        }
        UUID playerId = switch (event.getCommonConnection()) {
            case PlayerConfigurationConnection configuring -> configuring.getProfile().getId();
            case PlayerGameConnection playing -> playing.getPlayer().getUniqueId();
            default -> null;
        };
        if (playerId == null) {
            return;
        }
        CompletableFuture<Response> future = pending.get(playerId);
        if (future != null) {
            future.complete(new Response(id.value(), event.getDialogResponseView()));
        }
    }

    @EventHandler
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        cancel(event.getPlayerUniqueId());
    }
}
