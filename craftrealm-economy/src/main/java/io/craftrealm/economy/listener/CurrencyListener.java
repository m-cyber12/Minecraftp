package io.craftrealm.economy.listener;

import io.craftrealm.economy.items.CurrencyService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Right-clicking a coin or a banknote cashes it in.
 *
 * <p>Runs at {@code LOW} and cancels the interaction so the item cannot also be
 * used as a block placement (a coin is a gold nugget, a note is paper).</p>
 */
public final class CurrencyListener implements Listener {

    private final CurrencyService currency;

    public CurrencyListener(CurrencyService currency) {
        this.currency = currency;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getItem() == null) {
            return;
        }
        if (!currency.isCurrency(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        currency.redeemHand(event.getPlayer());
    }
}
