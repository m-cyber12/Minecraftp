package io.craftrealm.admin.gui;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.api.model.StaffAction;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.UUID;

/**
 * Read-only view of a player's inventory or ender chest.
 *
 * <p>Slots are copied into a mirror inventory, so staff can inspect items but can
 * never move them. The viewer can click a stack to copy its details into chat for a
 * report; nothing is ever taken.</p>
 */
public final class InventoryViewGui extends Gui {

    private final AdminServices services;
    private final UUID target;
    private final boolean enderChest;

    public InventoryViewGui(AdminServices services, Player staff, UUID target, boolean enderChest) {
        super(staff, services.context.theme, 6, services.context.messages.get(
                enderChest ? "admin.inventory.ender-title" : "admin.inventory.title",
                "player", io.craftrealm.core.CraftRealmCore.nameOf(target)));
        this.services = services;
        this.target = target;
        this.enderChest = enderChest;
    }

    @Override
    public void render() {
        Player online = Bukkit.getPlayer(target);
        if (online == null) {
            set(22, ItemBuilder.of(Material.BARRIER)
                    .name(services.context.messages.raw("admin.player.offline"))
                    .build());
            closeButton();
            fillEmpty();
            return;
        }

        ItemStack[] contents;
        if (enderChest) {
            Inventory ender = online.getEnderChest();
            contents = ender.getContents();
        } else {
            PlayerInventory inventory = online.getInventory();
            contents = inventory.getContents();
        }

        for (int index = 0; index < contents.length && index < 45; index++) {
            ItemStack item = contents[index];
            if (item != null && !item.getType().isAir()) {
                final ItemStack copy = item.clone();
                set(index, copy, click -> {
                    services.context.messages.send(viewer, "admin.inventory.inspect",
                            "item", copy.getType().name(),
                            "amount", String.valueOf(copy.getAmount()));
                    theme.play(viewer, GuiTheme.Click.BUTTON);
                });
            }
        }

        // Show armour and offhand in the bottom row of the mirror.
        if (!enderChest) {
            PlayerInventory inventory = online.getInventory();
            int slot = 45;
            for (ItemStack armour : inventory.getArmorContents()) {
                if (armour != null && !armour.getType().isAir()) {
                    set(slot++, armour.clone());
                }
            }
            ItemStack offhand = inventory.getItemInOffHand();
            if (offhand.getType() != Material.AIR) {
                set(53, offhand.clone());
            }
        }

        services.audit.record(viewer.getUniqueId(), StaffAction.INVENTORY_VIEW, target.toString(),
                enderChest ? "ender chest" : "inventory");
        backButton(() -> new PlayerDetailGui(services, viewer, target,
                io.craftrealm.core.CraftRealmCore.nameOf(target)).open());
        closeButton();
        fillEmpty();
    }
}
