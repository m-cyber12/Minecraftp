package io.craftrealm.api.model;

import java.util.UUID;

/**
 * A rectangular chunk-aligned land claim purchased with Crowns.
 *
 * <p>Claims pay upkeep. When upkeep is missed the claim enters decay: build
 * rights are revoked first, then the claim is deleted and the land freed.</p>
 *
 * @param id         claim id
 * @param owner      owner
 * @param world      world name
 * @param minX       minimum block X (inclusive)
 * @param minZ       minimum block Z (inclusive)
 * @param maxX       maximum block X (inclusive)
 * @param maxZ       maximum block Z (inclusive)
 * @param createdAt  epoch millis
 * @param paidUntil  upkeep is paid up to this instant
 * @param lastTaxRun last time upkeep was attempted
 */
public record Claim(long id, UUID owner, String world, int minX, int minZ, int maxX, int maxZ,
                    long createdAt, long paidUntil, long lastTaxRun) {

    public int width() {
        return Math.abs(maxX - minX) + 1;
    }

    public int depth() {
        return Math.abs(maxZ - minZ) + 1;
    }

    public int area() {
        return width() * depth();
    }

    public boolean contains(String worldName, int x, int z) {
        return world.equalsIgnoreCase(worldName)
                && x >= minX && x <= maxX
                && z >= minZ && z <= maxZ;
    }

    public boolean overlaps(String worldName, int x1, int z1, int x2, int z2) {
        return world.equalsIgnoreCase(worldName)
                && minX <= x2 && maxX >= x1
                && minZ <= z2 && maxZ >= z1;
    }

    public boolean expired(long now) {
        return now > paidUntil;
    }
}
