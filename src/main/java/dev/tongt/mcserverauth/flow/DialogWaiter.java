package dev.tongt.mcserverauth.flow;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import io.papermc.paper.connection.PlayerCommonConnection;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Turns dialog button clicks into a blocking call: show a dialog, wait for the player's answer.
 *
 * <p>Configuration-phase waits are keyed by the connection object itself, so a second connection with the same
 * name (and therefore the same offline UUID) can neither answer nor cancel another connection's dialog. In-game
 * waits are keyed by the player's UUID; only one in-world player can hold a UUID.
 *
 * <p>Every displayed dialog gets a fresh nonce in its click ids; a click whose nonce is not the one of the dialog
 * currently awaited for that key (for example a late click on a dialog that was already replaced) is ignored.
 */
public final class DialogWaiter implements Listener {
    public static final String NAMESPACE = "mcserverauth";

    /** How often a configuration-phase wait checks that its connection is still open. */
    private static final long LIVENESS_CHECK_MILLIS = 1000;

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

    /** Wait key for one configuration-phase connection, compared by object identity. */
    private static final class ConnectionKey {
        final PlayerConfigurationConnection connection;

        ConnectionKey(PlayerConfigurationConnection connection) {
            this.connection = connection;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ConnectionKey key && key.connection == connection;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(connection);
        }
    }

    /** One awaited dialog: the nonce in its click ids and the future its answer completes. */
    private record Waiting(String nonce, CompletableFuture<Response> future) {}

    private final Map<Object, Waiting> pending = new ConcurrentHashMap<>();
    private final AtomicLong nonces = new AtomicLong();

    private final Set<UUID> claimed = ConcurrentHashMap.newKeySet();

    /** Marks a player as having a command dialog open. Returns false if one already is. */
    public boolean claim(UUID playerId) {
        return claimed.add(playerId);
    }

    /** Releases a {@link #claim(UUID)}; call it in a finally block. */
    public void release(UUID playerId) {
        claimed.remove(playerId);
    }

    /** Wait key for a dialog shown during the configuration phase. */
    public static Object keyOf(PlayerConfigurationConnection connection) {
        return new ConnectionKey(connection);
    }

    /** Wait key for a dialog shown to a player in the world. */
    public static Object keyOf(Player player) {
        return player.getUniqueId();
    }

    /** Key of the connection a click came from, or null if it is neither phase. */
    private static @Nullable Object keyOf(PlayerCommonConnection connection) {
        return switch (connection) {
            case PlayerConfigurationConnection configuring -> keyOf(configuring);
            case PlayerGameConnection playing -> keyOf(playing.getPlayer());
            default -> null;
        };
    }

    /**
     * Shows a dialog and blocks the calling thread until the player answers, the wait is cancelled
     * ({@link Response#CLOSED}), or the timeout passes. Never call from the main server thread.
     *
     * @param key    from {@link #keyOf(PlayerConfigurationConnection)} or {@link #keyOf(Player)}
     * @param dialog template from {@link DialogFactory}, given this display's nonce
     * @param show   shows the built dialog to the player
     */
    public Response await(Object key, Function<String, Dialog> dialog, Consumer<Dialog> show, Duration timeout)
            throws TimeoutException {
        String nonce = Long.toString(nonces.incrementAndGet());
        CompletableFuture<Response> future = new CompletableFuture<>();
        Waiting waiting = new Waiting(nonce, future);
        Waiting previous = pending.put(key, waiting);
        if (previous != null) {
            previous.future().complete(Response.CLOSED);
        }
        try {
            show.accept(dialog.apply(nonce));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeout.toMillis()));
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new TimeoutException();
                }
                try {
                    return future.get(Math.min(left, TimeUnit.MILLISECONDS.toNanos(LIVENESS_CHECK_MILLIS)),
                            TimeUnit.NANOSECONDS);
                } catch (TimeoutException slice) {
                    if (key instanceof ConnectionKey c && !c.connection.isConnected()) {
                        return Response.CLOSED;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.CLOSED;
        } catch (ExecutionException e) {
            return Response.CLOSED;
        } finally {
            pending.remove(key, waiting);
        }
    }

    /** Completes every pending wait with {@link Response#CLOSED}. Used when the plugin is disabled. */
    public void cancelAll() {
        for (Object key : pending.keySet()) {
            complete(key, Response.CLOSED);
        }
    }

    private void complete(Object key, Response response) {
        Waiting waiting = pending.remove(key);
        if (waiting != null) {
            waiting.future().complete(response);
        }
    }

    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        Key id = event.getIdentifier();
        if (!id.namespace().equals(NAMESPACE)) {
            return;
        }
        Object key = keyOf(event.getCommonConnection());
        if (key == null) {
            return;
        }
        // Click ids are <action>.<nonce>; see DialogFactory.clickId.
        String value = id.value();
        int dot = value.lastIndexOf('.');
        if (dot < 0) {
            return;
        }
        Waiting waiting = pending.get(key);
        if (waiting != null && waiting.nonce().equals(value.substring(dot + 1))) {
            waiting.future().complete(new Response(value.substring(0, dot), event.getDialogResponseView()));
        }
    }

    /**
     * The event only carries a UUID, which a refused duplicate connection shares with the real player, so it is
     * used as a trigger to check which configuration-phase waits really lost their connection. In-game waits are
     * cancelled by {@link #onQuit}; this event may fire off the main thread, where the Bukkit player API is unsafe.
     */
    @EventHandler
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        for (Object key : pending.keySet()) {
            if (key instanceof ConnectionKey c && !c.connection.isConnected()) {
                complete(key, Response.CLOSED);
            }
        }
    }

    /** An in-world player left; only that player can own a UUID-keyed wait. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        complete(keyOf(event.getPlayer()), Response.CLOSED);
    }
}
