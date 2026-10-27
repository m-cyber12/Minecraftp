package io.craftrealm.forge.gui;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;

import java.util.function.BiConsumer;

/** Click-safe, built-in 3-row chest menu used as the CraftRealm feature hub. */
public final class CraftRealmMenu extends ChestMenu {
    private final BiConsumer<Player, Integer> action;

    public CraftRealmMenu(int containerId, Inventory inventory, Container contents,
                          BiConsumer<Player, Integer> action) {
        super(MenuType.GENERIC_9x3, containerId, inventory, contents, 3);
        this.action = action;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (slotId >= 0 && slotId < 27) {
            if (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE) {
                action.accept(player, slotId);
            }
            return;
        }
        if (slotId >= 27 && clickType == ClickType.QUICK_MOVE) return;
        if (slotId >= 27) super.clicked(slotId, button, clickType, player);
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, net.minecraft.world.inventory.Slot slot) {
        return false;
    }
}
