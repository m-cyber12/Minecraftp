package io.craftrealm.core.util;

import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

import java.util.concurrent.CompletableFuture;

/**
 * Bridges Bukkit's synchronous event bus into CompletableFuture chains.
 *
 * <p>Economy mutations run on the I/O pool but must ask permission on the tick
 * thread (listeners frequently touch players and worlds). {@link #callCancellable}
 * hops to the main thread, calls the event and completes with "may we proceed?".
 * If the server is shutting down the task cannot be scheduled and the operation
 * is allowed through so no future is ever left dangling.</p>
 */
public final class Events {

    private Events() {
    }

    /** Fires a cancellable event on the main thread; result = not cancelled. */
    public static CompletableFuture<Boolean> callCancellable(Event event) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        boolean scheduled = Scheduler.trySync(() -> {
            try {
                Bukkit.getPluginManager().callEvent(event);
                future.complete(!(event instanceof Cancellable cancellable) || !cancellable.isCancelled());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        if (!scheduled) {
            future.complete(true);
        }
        return future;
    }

    /** Fire-and-forget notification event (never cancelled, never throws to caller). */
    public static void call(Event event) {
        Scheduler.trySync(() -> {
            try {
                Bukkit.getPluginManager().callEvent(event);
            } catch (Throwable throwable) {
                io.craftrealm.core.CraftRealmCore.logger()
                        .warning("Listener error while firing " + event.getEventName() + ": " + throwable);
            }
        });
    }
}
