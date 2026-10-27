package io.craftrealm.forge.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;

/** Opens a native server-synchronized menu and dispatches safe built-in commands. */
public final class ForgeMenuService {
    private static final Map<Integer, MenuEntry> ENTRIES = Map.of(
            11, new MenuEntry(Items.WRITABLE_BOOK, "craftrealm.menu.wallet", "balance"),
            12, new MenuEntry(Items.GOLD_INGOT, "craftrealm.menu.bank", "bank"),
            13, new MenuEntry(Items.SHIELD, "craftrealm.menu.claims", "claims"),
            14, new MenuEntry(Items.IRON_PICKAXE, "craftrealm.menu.jobs", "jobs"),
            15, new MenuEntry(Items.BOOK, "craftrealm.menu.quests", "quests"),
            16, new MenuEntry(Items.EMERALD, "craftrealm.menu.market", "market"),
            20, new MenuEntry(Items.CLOCK, "craftrealm.menu.season", "season top"),
            21, new MenuEntry(Items.FIREWORK_STAR, "craftrealm.menu.cosmetics", "cosmetic"),
            22, new MenuEntry(Items.PAPER, "craftrealm.menu.help", "craftrealm")
    );

    public void open(ServerPlayer player) {
        SimpleContainer contents = new SimpleContainer(27);
        ENTRIES.forEach((slot, entry) -> {
            ItemStack icon = new ItemStack(entry.item());
            icon.setHoverName(Component.translatable(entry.title()));
            contents.setItem(slot, icon);
        });
        MenuProvider provider = new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable("craftrealm.menu.title");
            }

            @Override
            public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
                    int containerId, net.minecraft.world.entity.player.Inventory inventory, Player menuPlayer) {
                return new CraftRealmMenu(containerId, inventory, contents, ForgeMenuService.this::activate);
            }
        };
        player.openMenu(provider);
    }

    private void activate(Player player, int slot) {
        MenuEntry entry = ENTRIES.get(slot);
        if (!(player instanceof ServerPlayer serverPlayer) || entry == null) return;
        serverPlayer.closeContainer();
        serverPlayer.getServer().getCommands().performPrefixedCommand(
                serverPlayer.createCommandSourceStack(), entry.command());
    }

    private record MenuEntry(Item item, String title, String command) { }
}
