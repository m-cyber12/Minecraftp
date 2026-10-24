package io.craftrealm.core.ui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Routes Bukkit inventory events to {@link Gui} instances and, more importantly,
 * makes CraftRealm menus read-only: clicks, drags and shift-clicks are cancelled
 * before they can move an item.
 */
public final class GuiListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof Gui gui)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean topInventory = event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getInventory());
        if (gui.allowsItemMovement()) {
            // The sell menu lets players place items; only the action row is ours.
            if (topInventory && event.getSlot() >= gui.size() - 9) {
                event.setCancelled(true);
                gui.handleClick(new GuiClick(player, gui, event.getSlot(), event.getClick(),
                        event.getCurrentItem(), event));
            } else {
                gui.handleClick(new GuiClick(player, gui, topInventory ? event.getSlot() : -1,
                        event.getClick(), event.getCurrentItem(), event));
            }
            return;
        }
        // Cancel first: even unhandled slots must not be modifiable.
        event.setCancelled(true);
        if (!topInventory) {
            return; // clicks in the player's own inventory
        }
        int slot = event.getSlot();
        gui.handleClick(new GuiClick(player, gui, slot, event.getClick(),
                event.getCurrentItem(), event));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Gui) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Gui gui) {
            gui.onClose();
        }
    }
}
