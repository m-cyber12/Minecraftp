package io.craftrealm.api.event;

import io.craftrealm.api.model.TransactionType;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Fired before a balance changes. Cancelling it aborts the database write, which
 * makes it the hook of choice for shop taxes, region rules or per-rank limits.
 */
public class BalanceChangeEvent extends CraftRealmEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID account;
    private final double oldBalance;
    private final double newBalance;
    private final double delta;
    private final TransactionType type;
    private final String reason;
    private final UUID actor;
    private boolean cancelled;

    public BalanceChangeEvent(@NotNull UUID account, double oldBalance, double newBalance,
                              double delta, @NotNull TransactionType type, @Nullable String reason,
                              @Nullable UUID actor) {
        this.account = account;
        this.oldBalance = oldBalance;
        this.newBalance = newBalance;
        this.delta = delta;
        this.type = type;
        this.reason = reason == null ? "" : reason;
        this.actor = actor;
    }

    public @NotNull UUID account() {
        return account;
    }

    /** Alias kept for readability in third-party code. */
    public @NotNull UUID player() {
        return account;
    }

    public double oldBalance() {
        return oldBalance;
    }

    public double newBalance() {
        return newBalance;
    }

    /** Signed change: negative for withdrawals. */
    public double delta() {
        return delta;
    }

    public boolean isDeposit() {
        return delta > 0;
    }

    public @NotNull TransactionType type() {
        return type;
    }

    public @NotNull String reason() {
        return reason;
    }

    /** Staff member responsible, or {@code null} for system changes. */
    public @Nullable UUID actor() {
        return actor;
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
