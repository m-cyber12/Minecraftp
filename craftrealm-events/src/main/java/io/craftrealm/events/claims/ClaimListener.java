package io.craftrealm.events.claims;

import io.craftrealm.core.text.Messages;
import io.craftrealm.api.service.ClaimService;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Enforces claims.
 *
 * <p>Only container and door interactions are blocked — plain right clicks (hoe on
 * dirt, flint on TNT) would be annoying to cancel, and griefing with them is
 * already covered by the break/place checks.</p>
 */
public final class ClaimListener implements Listener {

    private static final String BYPASS = "craftrealm.claims.bypass";

    private final ClaimService claims;
    private final Messages messages;

    public ClaimListener(ClaimService claims, Messages messages) {
        this.claims = claims;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (denied(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (denied(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!isContainer(block.getType())) {
            return;
        }
        if (denied(event.getPlayer(), block)) {
            event.setCancelled(true);
        }
    }

    private boolean denied(Player player, Block block) {
        if (player.hasPermission(BYPASS)) {
            return false;
        }
        Location location = block.getLocation();
        boolean allowed = claims.canBuild(player.getUniqueId(), block.getWorld().getName(),
                location.getBlockX(), location.getBlockZ());
        if (!allowed) {
            messages.send(player, "claims.protected");
        }
        return !allowed;
    }

    private static boolean isContainer(org.bukkit.Material material) {
        String name = material.name();
        return name.endsWith("CHEST") || name.endsWith("SHULKER_BOX") || name.contains("BARREL")
                || name.contains("FURNACE") || name.contains("HOPPER") || name.contains("DISPENSER")
                || name.contains("DROPPER") || name.contains("BREWING") || name.contains("BEACON")
                || name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR") || name.endsWith("_FENCE_GATE")
                || name.endsWith("_BED") || name.contains("ANVIL") || name.contains("ENCHANTING")
                || name.contains("SMITHING") || name.contains("CARTOGRAPHY") || name.contains("LECTERN");
    }
}
