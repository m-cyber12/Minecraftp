package io.craftrealm.api.event;

import io.craftrealm.api.model.Claim;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired before a land claim is paid for and stored. */
public class ClaimCreateEvent extends CraftRealmEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Claim claim;
    private boolean cancelled;

    public ClaimCreateEvent(@NotNull Claim claim) {
        this.claim = claim;
    }

    public @NotNull Claim claim() {
        return claim;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
