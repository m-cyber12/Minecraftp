package io.craftrealm.api.model;

import java.util.UUID;

/**
 * Immutable ledger entry. CraftRealm never mutates a completed transaction —
 * corrections are written as new {@link TransactionType#ROLLBACK} rows that
 * point back at the original through {@link #rollbackOf()}.
 *
 * @param id          monotonically increasing ledger id
 * @param type        why the money moved
 * @param from        payer, {@code null} when the server is the source
 * @param to          receiver, {@code null} when the server is the sink
 * @param amount      always positive
 * @param reason      short human readable note (never null, may be empty)
 * @param actor       staff member or system UUID that caused it, may be null
 * @param operationId idempotency key; unique in the ledger, may be null
 * @param createdAt   epoch millis
 * @param reverted    already rolled back?
 * @param rollbackOf  ledger id this row reverses, {@code 0} if none
 * @param server      server identifier for multi-node setups
 */
public record Transaction(
        long id,
        TransactionType type,
        UUID from,
        UUID to,
        double amount,
        String reason,
        UUID actor,
        String operationId,
        long createdAt,
        boolean reverted,
        long rollbackOf,
        String server
) {

    public Transaction {
        reason = reason == null ? "" : reason;
        server = server == null ? "local" : server;
    }

    public boolean isTransfer() {
        return from != null && to != null;
    }

    public boolean involves(UUID uuid) {
        return uuid.equals(from) || uuid.equals(to);
    }

    /** The single account affected by a server source/sink row. */
    public UUID account() {
        return from != null ? from : to;
    }

    /** True when the server paid this player (a SOURCE row). */
    public boolean deltaIsCredit() {
        return from == null && to != null;
    }

    /** True when money left this player towards the server (a SINK row). */
    public boolean deltaIsDebit() {
        return from != null && to == null;
    }

    /** The counterparty of {@code uuid}, or {@code null} for server transactions. */
    public UUID counterparty(UUID uuid) {
        return uuid.equals(from) ? to : from;
    }
}
