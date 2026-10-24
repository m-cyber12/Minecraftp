package io.craftrealm.economy.listener;

import io.craftrealm.api.model.JobType;
import io.craftrealm.api.service.JobService;
import io.craftrealm.core.config.Settings;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pays jobs for real work.
 *
 * <p>Anti-exploit rules baked in:</p>
 * <ul>
 *   <li>Creative mode pays nothing.</li>
 *   <li>Blocks the player placed themselves are remembered for a few minutes and pay
 *       no miner income, which kills the place-and-break farm. The memory is
 *       bounded (20 000 positions, five minute TTL) so it cannot leak.</li>
 *   <li>Spawner-placed mobs are ignored for the hunter job.</li>
 * </ul>
 */
public final class JobListener implements Listener {

    private static final long PLACED_TTL = 5 * 60_000L;
    private static final int PLACED_MAX = 20_000;

    private final JobService jobs;
    private final Settings.JobSettings settings;
    private final Map<String, Long> placed = new ConcurrentHashMap<>();

    public JobListener(JobService jobs, Settings.JobSettings settings) {
        this.jobs = jobs;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Block block = event.getBlock();
        Material material = block.getType();

        // Farming is checked first: a mature crop is both a "mine" and a "harvest".
        double farmer = settings.payout(JobType.FARMER, material);
        if (farmer > 0) {
            jobs.award(player.getUniqueId(), JobType.FARMER, farmer, material.name());
            return;
        }
        if (wasPlayerPlaced(block)) {
            placed.remove(blockKey(block));
            return;
        }
        double miner = settings.payout(JobType.MINER, material);
        if (miner > 0) {
            jobs.award(player.getUniqueId(), JobType.MINER, miner, material.name());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (placed.size() > PLACED_MAX) {
            prune();
        }
        placed.put(blockKey(event.getBlock()), System.currentTimeMillis());

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        double builder = settings.payout(JobType.BUILDER, event.getBlock().getType());
        if (builder > 0) {
            jobs.award(player.getUniqueId(), JobType.BUILDER, builder, event.getBlock().getType().name());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || killer.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason =
                event.getEntity().getEntitySpawnReason();
        if (reason == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER
                || reason == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) {
            return;
        }
        double hunter = settings.hunterPayout(event.getEntityType());
        if (hunter > 0) {
            jobs.award(killer.getUniqueId(), JobType.HUNTER, hunter, event.getEntityType().name());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        if (!(event.getCaught() instanceof org.bukkit.entity.Item caught)) {
            return;
        }
        Material catchType = caught.getItemStack().getType();
        double payout = settings.payout(JobType.FISHER, catchType);
        if (payout > 0) {
            jobs.award(event.getPlayer().getUniqueId(), JobType.FISHER, payout, catchType.name());
        }
    }

    private boolean wasPlayerPlaced(Block block) {
        Long stamp = placed.get(blockKey(block));
        if (stamp == null) {
            return false;
        }
        if (System.currentTimeMillis() - stamp > PLACED_TTL) {
            placed.remove(blockKey(block));
            return false;
        }
        return true;
    }

    private void prune() {
        long cutoff = System.currentTimeMillis() - PLACED_TTL;
        placed.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private static String blockKey(Block block) {
        return block.getWorld().getName() + ':' + block.getX() + ':' + block.getY() + ':' + block.getZ();
    }
}
