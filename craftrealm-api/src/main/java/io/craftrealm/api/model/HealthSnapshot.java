package io.craftrealm.api.model;

import java.util.List;

/**
 * Point-in-time server health, assembled on the main thread and safe to hand to
 * the web layer or the Control Room.
 *
 * @param tps        1, 5 and 15 minute TPS averages
 * @param usedRamMb  heap in use
 * @param maxRamMb   heap committed maximum
 * @param uptimeMs   server uptime
 * @param worlds     per-world counters
 * @param hotspots   worst chunks by entity count, already sorted
 */
public record HealthSnapshot(double[] tps, long usedRamMb, long maxRamMb, long uptimeMs,
                             int onlinePlayers, List<WorldHealth> worlds, List<ChunkHotspot> hotspots) {

    /** The 1-minute TPS average; a record cannot have two {@code tps()} accessors. */
    public double currentTps() {
        return tps.length > 0 ? tps[0] : 20.0;
    }

    public double ramPercent() {
        return maxRamMb <= 0 ? 0 : (double) usedRamMb / (double) maxRamMb;
    }
}
