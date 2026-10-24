package io.craftrealm.core.ui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/**
 * What a GUI action receives. Exposing the raw event lets menus implement
 * right-click / shift-click variants without a mountain of subclasses.
 */
public record GuiClick(Player player, Gui gui, int slot, ClickType clickType,
                       ItemStack clicked, InventoryClickEvent event) {

    public boolean rightClick() {
        return clickType == ClickType.RIGHT || clickType == ClickType.SHIFT_RIGHT;
    }

    public boolean shiftClick() {
        return clickType.isShiftClick();
    }

    public boolean leftClick() {
        return clickType == ClickType.LEFT || clickType == ClickType.SHIFT_LEFT;
    }
}
