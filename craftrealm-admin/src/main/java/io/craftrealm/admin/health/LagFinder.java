package io.craftrealm.admin.health;

import io.craftrealm.api.model.ChunkHotspot;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the chunks that are actually hurting the server.
 *
 * <p>Only <b>already loaded</b> chunks around players are inspected — scanning
 * every chunk in the world would itself cause the lag we are trying to fix. The
 * result is sorted by entity count so the Control Room can offer a one-click
 * cleanup of the worst offenders.</p>
 */
public final class LagFinder {

    private final int entityThreshold;
    private final int radiusChunks;
    private final int maxChunksToClean;

    public LagFinder(int entityThreshold, int radiusChunks, int maxChunksToClean) {
        this.entityThreshold = Math.max(1, entityThreshold);
        this.radiusChunks = Math.max(1, radiusChunks);
        this.maxChunksToClean = Math.max(1, maxChunksToClean);
    }

    /** Must be called on the main thread. */
    public List<ChunkHotspot> hotspots(int limit) {
        List<ChunkHotspot> hotspots = new ArrayList<>();
        Map<String, ChunkHotspot> unique = new HashMap<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            int centerX = player.getLocation().getBlockX() >> 4;
            int centerZ = player.getLocation().getBlockZ() >> 4;
            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                    int chunkX = centerX + dx;
                    int chunkZ = centerZ + dz;
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        continue;
                    }
                    String key = world.getName() + ":" + chunkX + ":" + chunkZ;
                    if (unique.containsKey(key)) {
                        continue;
                    }
                    Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                    Entity[] entities = chunk.getEntities();
                    if (entities.length < entityThreshold) {
                        continue;
                    }
                    ChunkHotspot hotspot = new ChunkHotspot(world.getName(), chunkX, chunkZ,
                            entities.length, dominantType(entities));
                    unique.put(key, hotspot);
                    hotspots.add(hotspot);
                }
            }
        }

        return hotspots.stream()
                .sorted(Comparator.comparingInt(ChunkHotspot::entities).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    /**
     * Removes the worst offenders in a chunk.
     *
     * @param includeMobs keep hostile mobs alive (safer default for survival
     *                    servers, where entity spam is usually dropped items)
     * @return how many entities were removed
     */
    public int cleanup(ChunkHotspot hotspot, boolean includeMobs) {
        World world = Bukkit.getWorld(hotspot.world());
        if (world == null || !world.isChunkLoaded(hotspot.chunkX(), hotspot.chunkZ())) {
            return 0;
        }
        Chunk chunk = world.getChunkAt(hotspot.chunkX(), hotspot.chunkZ());
        int removed = 0;
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Player) {
                continue;
            }
            boolean hostile = entity instanceof org.bukkit.entity.Monster
                    || entity instanceof org.bukkit.entity.Slime
                    || entity instanceof org.bukkit.entity.Ghast
                    || entity instanceof org.bukkit.entity.Phantom;
            if (hostile && !includeMobs) {
                continue;
            }
            if (entity.getCustomName() != null && !includeMobs) {
                continue; // named pets and shopkeepers are never swept
            }
            entity.remove();
            removed++;
        }
        return removed;
    }

    /** How many chunks a single cleanup pass may touch. */
    public int maxChunksToClean() {
        return maxChunksToClean;
    }

    public int entityThreshold() {
        return entityThreshold;
    }

    private static String dominantType(Entity[] entities) {
        Map<String, Integer> counts = new HashMap<>();
        for (Entity entity : entities) {
            counts.merge(entity.getType().name(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("UNKNOWN");
    }
}
