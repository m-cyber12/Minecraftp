package io.craftrealm.api.model;

/** What arrived in a player's mailbox. */
public enum MailboxKind {
    /** The auctioned item itself (expired listing, or a win the inventory could not take). */
    ITEM,
    /** Crowns owed to the player (sale proceeds, outbid refund, fees). */
    MONEY
}
