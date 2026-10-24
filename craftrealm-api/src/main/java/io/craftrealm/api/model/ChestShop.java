package io.craftrealm.api.model;

import java.util.UUID;

/**
 * A player-run chest shop: a chest with a sign, hologram and stock.
 *
 * @param item   serialised {@code ItemStack} being sold
 * @param amount units sold per purchase
 * @param price  Crowns per purchase
 * @param stock  remaining units, {@code -1} for unlimited (admin shops)
 */
public record ChestShop(long id, UUID owner, String world, int x, int y, int z,
                        byte[] item, int amount, double price, int stock) {
}
