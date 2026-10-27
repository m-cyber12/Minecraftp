package io.craftrealm.forge.api;

import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;

import java.util.UUID;

/**
 * Posted on the main server thread before a wallet change is committed.
 * Cancelling prevents the transaction. Amounts are exact integer cents.
 */
@Cancelable
public final class ForgeBalanceChangeEvent extends Event {

    private final UUID account;
    private final long oldBalanceCents;
    private final long newBalanceCents;
    private final long deltaCents;
    private final ForgeTransactionType type;
    private final String reason;
    private final UUID actor;

    public ForgeBalanceChangeEvent(UUID account, long oldBalanceCents, long newBalanceCents,
                                   ForgeTransactionType type, String reason, UUID actor) {
        this.account = account;
        this.oldBalanceCents = oldBalanceCents;
        this.newBalanceCents = newBalanceCents;
        this.deltaCents = newBalanceCents - oldBalanceCents;
        this.type = type;
        this.reason = reason;
        this.actor = actor;
    }

    public UUID account() { return account; }
    public long oldBalanceCents() { return oldBalanceCents; }
    public long newBalanceCents() { return newBalanceCents; }
    public long deltaCents() { return deltaCents; }
    public ForgeTransactionType type() { return type; }
    public String reason() { return reason; }
    public UUID actor() { return actor; }
}
