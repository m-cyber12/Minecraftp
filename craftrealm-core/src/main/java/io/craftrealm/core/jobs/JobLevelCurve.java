package io.craftrealm.core.jobs;

/**
 * Job progression maths. Pure functions — unit tested without a server.
 */
public final class JobLevelCurve {

    private JobLevelCurve() {
    }

    /**
     * XP required to go from {@code level} to {@code level + 1}.
     * Grows geometrically: {@code base * growth^(level-1)}.
     */
    public static long xpForLevel(int level, long base, double growth) {
        if (level < 1) {
            return 0L;
        }
        double value = base * Math.pow(Math.max(1.0, growth), level - 1);
        return Math.max(1L, Math.round(value));
    }

    /** Income multiplier for a level: level 1 = 1.0, +5% per extra level by default. */
    public static double multiplier(int level, double perLevel) {
        int effective = Math.max(1, level);
        return 1.0 + (effective - 1) * Math.max(0.0, perLevel);
    }

    /** Level that {@code xp} corresponds to, starting at 1. */
    public static int levelForXp(long xp, long base, double growth, int maxLevel) {
        int level = 1;
        long remaining = Math.max(0L, xp);
        while (level < maxLevel) {
            long needed = xpForLevel(level, base, growth);
            if (remaining < needed) {
                break;
            }
            remaining -= needed;
            level++;
        }
        return level;
    }

    /** XP still needed for the next level given current level and xp. */
    public static long xpToNext(int level, long xp, long base, double growth) {
        return Math.max(0L, xpForLevel(level, base, growth) - Math.max(0L, xp));
    }
}
