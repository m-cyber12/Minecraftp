package io.craftrealm.api.model;

import org.bukkit.Material;

/**
 * A price snapshot for one material on the dynamic market.
 *
 * @param material    the item
 * @param basePrice   configured anchor price
 * @param price       current price after supply/demand pressure
 * @param totalSold   lifetime units sold by players into the market
 * @param totalBought lifetime units bought from the market
 * @param pressure    signed sentiment, negative = flooded, positive = scarce
 * @param updatedAt   epoch millis
 */
public record MarketQuote(Material material, double basePrice, double price,
                          long totalSold, long totalBought, double pressure, long updatedAt) {

    /** Price relative to the anchor: {@code 0.8} means 20% below base. */
    public double ratio() {
        return basePrice <= 0 ? 1.0 : price / basePrice;
    }
}
