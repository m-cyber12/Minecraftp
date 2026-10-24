package io.craftrealm.economy.items;

import io.craftrealm.api.model.Note;
import io.craftrealm.api.model.TransactionType;
import io.craftrealm.core.repo.ProgressionRepository;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.economy.EconomyContext;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Minting and redeeming physical currency.
 *
 * <p>Both paths are idempotent: coins redeem under {@code coin:<id>} and notes
 * under {@code note:<id>}, and notes are additionally marked redeemed in the
 * database. The item is removed from the player's hand before the credit is
 * attempted and handed back if the credit failed, so nothing is ever lost or
 * created twice.</p>
 */
public final class CurrencyService {

    private final EconomyContext context;
    private final ProgressionRepository notes;

    public CurrencyService(EconomyContext context, ProgressionRepository notes) {
        this.context = context;
        this.notes = notes;
    }

    /** Withdraws Crowns and hands over a physical coin. */
    public void mintCoin(Player player, double amount) {
        if (amount <= 0) {
            context.messages.send(player, "command.invalid-amount", "input", String.valueOf(amount));
            return;
        }
        context.economy.withdraw(player.getUniqueId(), amount, TransactionType.NOTE_ISSUE, "minted coin")
                .thenAccept(result -> Scheduler.sync(() -> {
                    if (!result.success()) {
                        context.messages.send(player, result.messageKey());
                        context.theme.play(player, GuiTheme.Click.DENY);
                        return;
                    }
                    ItemStack coin = context.items.coin(amount, context.money(amount));
                    var leftover = player.getInventory().addItem(coin);
                    leftover.values().forEach(overflow ->
                            player.getWorld().dropItemNaturally(player.getLocation(), overflow));
                    context.messages.send(player, "coins.minted-coin", "amount", context.money(amount));
                    context.theme.play(player, GuiTheme.Click.PURCHASE);
                }));
    }

    /** Withdraws Crowns and hands over a signed banknote. */
    public void mintNote(Player player, double amount) {
        if (amount <= 0) {
            context.messages.send(player, "command.invalid-amount", "input", String.valueOf(amount));
            return;
        }
        String id = CurrencyItems.newNoteId();
        context.economy.withdraw(player.getUniqueId(), amount, TransactionType.NOTE_ISSUE, "minted note")
                .thenCompose(result -> {
                    if (!result.success()) {
                        Scheduler.sync(() -> context.messages.send(player, result.messageKey()));
                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                    }
                    return notes.issueNote(new Note(id, player.getUniqueId(), amount, System.currentTimeMillis()))
                            .thenAccept(note -> Scheduler.sync(() -> {
                                ItemStack item = context.items.note(id, amount, context.money(amount),
                                        player.getName());
                                var leftover = player.getInventory().addItem(item);
                                leftover.values().forEach(overflow ->
                                        player.getWorld().dropItemNaturally(player.getLocation(), overflow));
                                context.messages.send(player, "coins.minted-note",
                                        "amount", context.money(amount));
                                context.theme.play(player, GuiTheme.Click.PURCHASE);
                            }));
                });
    }

    /** True when the stack is a CraftRealm coin or banknote. */
    public boolean isCurrency(org.bukkit.inventory.ItemStack item) {
        return context.items.isCoin(item) || context.items.isNote(item);
    }

    /** Right-click handler: redeems whichever currency item is in hand. */
    public void redeemHand(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (context.items.isCoin(held)) {
            redeemCoin(player, held);
        } else if (context.items.isNote(held)) {
            redeemNote(player, held);
        }
    }

    private void redeemCoin(Player player, ItemStack item) {
        String coinId = context.items.coinId(item);
        double value = context.items.coinValue(item);
        if (coinId == null || value <= 0) {
            context.messages.send(player, "coins.invalid");
            context.theme.play(player, GuiTheme.Click.DENY);
            return;
        }
        consumeHeld(player);
        context.economy.credit(player.getUniqueId(), value, TransactionType.COIN_REDEEM, "coin redeem",
                        "coin:" + coinId)
                .thenAccept(result -> Scheduler.sync(() -> {
                    if (!result.success()) {
                        // Duplicate operation = the coin was already cashed in.
                        if (result.reason() == io.craftrealm.api.model.EconomyResult.Reason.DUPLICATE_OPERATION) {
                            context.messages.send(player, "coins.already-redeemed");
                            context.theme.play(player, GuiTheme.Click.DENY);
                            return;
                        }
                        restore(player, item);
                        context.messages.send(player, result.messageKey());
                        return;
                    }
                    context.messages.send(player, "coins.redeemed",
                            "amount", context.money(value),
                            "balance", context.money(result.balanceAfter()));
                    context.theme.play(player, GuiTheme.Click.SUCCESS);
                }));
    }

    private void redeemNote(Player player, ItemStack item) {
        if (!context.items.signatureValid(item)) {
            consumeHeld(player);
            context.messages.send(player, "coins.forged");
            context.theme.play(player, GuiTheme.Click.DENY);
            return;
        }
        String noteId = context.items.noteIdOf(item).orElse(null);
        double value = context.items.noteAmount(item);
        if (noteId == null || value <= 0) {
            context.messages.send(player, "coins.invalid");
            return;
        }
        UUID uuid = player.getUniqueId();
        notes.redeemNote(noteId, uuid).thenCompose(claimed -> {
            if (!claimed) {
                Scheduler.sync(() -> {
                    consumeHeld(player);
                    context.messages.send(player, "coins.already-redeemed");
                    context.theme.play(player, GuiTheme.Click.DENY);
                });
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            return context.economy.credit(uuid, value, TransactionType.NOTE_REDEEM, "banknote",
                            "note:" + noteId)
                    .thenAccept(result -> Scheduler.sync(() -> {
                        if (!result.success()) {
                            notes.unredeemNote(noteId);
                            if (result.reason() == io.craftrealm.api.model.EconomyResult.Reason.DUPLICATE_OPERATION) {
                                consumeHeld(player);
                                context.messages.send(player, "coins.already-redeemed");
                                return;
                            }
                            context.messages.send(player, result.messageKey());
                            return;
                        }
                        consumeHeld(player);
                        context.messages.send(player, "coins.redeemed",
                                "amount", context.money(value),
                                "balance", context.money(result.balanceAfter()));
                        context.theme.play(player, GuiTheme.Click.SUCCESS);
                    }));
        });
    }

    private void consumeHeld(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            held.setAmount(held.getAmount() - 1);
        }
    }

    private void restore(Player player, ItemStack item) {
        var leftover = player.getInventory().addItem(item);
        leftover.values().forEach(overflow ->
                player.getWorld().dropItemNaturally(player.getLocation(), overflow));
    }
}
