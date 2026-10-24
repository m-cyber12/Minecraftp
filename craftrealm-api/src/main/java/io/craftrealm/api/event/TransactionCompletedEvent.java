package io.craftrealm.api.event;

import io.craftrealm.api.model.Transaction;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired after a ledger entry has been committed. Read-only. */
public class TransactionCompletedEvent extends CraftRealmEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Transaction transaction;

    public TransactionCompletedEvent(@NotNull Transaction transaction) {
        this.transaction = transaction;
    }

    public @NotNull Transaction transaction() {
        return transaction;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
