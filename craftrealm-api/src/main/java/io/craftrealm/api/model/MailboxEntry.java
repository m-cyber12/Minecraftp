package io.craftrealm.api.model;

import java.util.UUID;

/**
 * Mail-style delivery used by the auction house so nothing is ever lost when a
 * player is offline or their inventory is full.
 */
public record MailboxEntry(long id, UUID owner, MailboxKind kind, byte[] item, double amount,
                           String note, boolean claimed, long createdAt) {
}
