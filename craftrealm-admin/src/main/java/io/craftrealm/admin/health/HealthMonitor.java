package io.craftrealm.admin.health;

import io.craftrealm.api.model.HealthSnapshot;
import io.craftrealm.api.model.WorldHealth;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * TPS, heap, per-world counters and the worst chunks — assembled on the tick
 * thread because Bukkit world access is not thread safe.
 */
public final class HealthMonitor {

    private final LagFinder lagFinder;

    public HealthMonitor(LagFinder lagFinder) {
        this.lagFinder = lagFinder;
    }

    /** Must be called on the main thread. */
    public HealthSnapshot snapshot() {
        double[] tps = Bukkit.getServer().getTPS();
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long max = runtime.maxMemory() / (1024L * 1024L);
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();

        List<WorldHealth> worlds = new ArrayList<>();
        int totalChunks = 0;
        int totalEntities = 0;
        for (World world : Bukkit.getWorlds()) {
            int chunks = world.getLoadedChunks().length;
            int entities = world.getEntities().size();
            totalChunks += chunks;
            totalEntities += entities;
            worlds.add(new WorldHealth(world.getName(), chunks, entities, world.getPlayers().size()));
        }
        if (totalChunks > 200_000 || totalEntities > 500_000) {
            io.craftrealm.core.CraftRealmCore.logger().warning("Health snapshot is very large ("
                    + totalChunks + " chunks, " + totalEntities + " entities)");
        }
        return new HealthSnapshot(tps, used, max, uptime, Bukkit.getOnlinePlayers().size(),
                worlds, lagFinder.hotspots(10));
    }

    /** Human readable TPS, capped at 20. */
    public static String formatTps(double tps) {
        return String.format(java.util.Locale.ROOT, "%.1f", Math.min(20.0, tps));
    }

    /** A blocky bar like the hunger bar, used by the Control Room. */
    public static String bar(double ratio, int segments) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, ratio)) * segments);
        return "\u2589".repeat(filled) + "\u2581".repeat(Math.max(0, segments - filled));
    }
}
