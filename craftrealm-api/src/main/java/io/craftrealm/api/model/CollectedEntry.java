package io.craftrealm.api.model;

import org.bukkit.inventory.ItemStack;

/**
 * Result of collecting one mailbox entry.
 *
 * @param result economy outcome for money entries
 * @param kind   what was inside
 * @param item   the delivered item for {@link MailboxKind#ITEM}, otherwise {@code null}
 * @param amount Crowns paid for {@link MailboxKind#MONEY}, otherwise {@code 0}
 */
public record CollectedEntry(EconomyResult result, MailboxKind kind, ItemStack item, double amount) {

    public boolean success() {
        return result != null && result.success();
    }
}
