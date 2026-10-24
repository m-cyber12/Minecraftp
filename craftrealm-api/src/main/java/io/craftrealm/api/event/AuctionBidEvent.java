package io.craftrealm.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Fired before a bid is escrowed. Cancelling blocks the bid. */
public class AuctionBidEvent extends CraftRealmEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final long listingId;
    private final UUID bidder;
    private final double amount;
    private boolean cancelled;

    public AuctionBidEvent(long listingId, @NotNull UUID bidder, double amount) {
        this.listingId = listingId;
        this.bidder = bidder;
        this.amount = amount;
    }

    public long listingId() {
        return listingId;
    }

    public @NotNull UUID bidder() {
        return bidder;
    }

    public double amount() {
        return amount;
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
