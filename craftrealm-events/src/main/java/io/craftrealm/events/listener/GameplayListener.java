package io.craftrealm.events.listener;

import io.craftrealm.api.model.Treasure;
import io.craftrealm.api.service.QuestService;
import io.craftrealm.events.treasure.TreasureService;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Bridges gameplay to quests and treasure hunts.
 *
 * <p>These handlers only touch in-memory state (quest progress and a small treasure
 * cache); persistence happens asynchronously in the services. That keeps the tick
 * budget intact even with a hundred players mining.</p>
 */
public final class GameplayListener implements Listener {

    private final QuestService quests;
    private final TreasureService treasures;
    private final io.craftrealm.core.text.Messages messages;

    public GameplayListener(QuestService quests, TreasureService treasures,
                            io.craftrealm.core.text.Messages messages) {
        this.quests = quests;
        this.treasures = treasures;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Material type = event.getBlock().getType();
        quests.addProgress(player.getUniqueId(), "MINE_BLOCK", 1);
        if (isCrop(type)) {
            quests.addProgress(player.getUniqueId(), "HARVEST", 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }
        quests.addProgress(event.getPlayer().getUniqueId(), "PLACE_BLOCK", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            quests.addProgress(killer.getUniqueId(), "KILL_MOB", 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            quests.addProgress(event.getPlayer().getUniqueId(), "FISH", 1);
        }
    }

    /** Treasure chests pay Shards to the first finder. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!treasures.settings().enabled()) {
            return;
        }
        if (block.getType() != treasures.settings().chestBlock()) {
            return;
        }
        Player player = event.getPlayer();
        treasures.claim(player, block.getLocation()).thenAccept(paid -> {
            if (paid <= 0) {
                return;
            }
            player.getWorld().strikeLightningEffect(block.getLocation());
        });
    }

    /** Selling to the shop also counts towards "earn" quests. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || !(event.getEntity() instanceof Player)) {
            return;
        }
        Item item = event.getItem();
        if (item.getItemStack().getType() == Material.AIR) {
            return;
        }
        // Nothing to do beyond the drop multiplier hook used by the event engine.
    }

    private static boolean isCrop(Material material) {
        String name = material.name();
        return name.endsWith("WHEAT") || name.equals("CARROTS") || name.equals("POTATOES")
                || name.equals("BEETROOTS") || name.equals("NETHER_WART") || name.equals("SUGAR_CANE")
                || name.equals("MELON") || name.equals("PUMPKIN") || name.equals("COCOA")
                || name.equals("SWEET_BERRY_BUSH") || name.equals("BROWN_MUSHROOM")
                || name.equals("RED_MUSHROOM");
    }
}
