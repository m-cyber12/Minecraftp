package io.craftrealm.api.event;

import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Fired after Shards were granted or spent. */
public class ShardChangeEvent extends CraftRealmEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID account;
    private final long oldBalance;
    private final long newBalance;
    private final String reason;

    public ShardChangeEvent(@NotNull UUID account, long oldBalance, long newBalance, @NotNull String reason) {
        this.account = account;
        this.oldBalance = oldBalance;
        this.newBalance = newBalance;
        this.reason = reason;
    }

    public @NotNull UUID account() {
        return account;
    }

    public long oldBalance() {
        return oldBalance;
    }

    public long newBalance() {
        return newBalance;
    }

    public long delta() {
        return newBalance - oldBalance;
    }

    public @NotNull String reason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
