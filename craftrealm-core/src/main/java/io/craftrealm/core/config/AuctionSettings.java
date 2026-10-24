package io.craftrealm.core.config;

/**
 * Auction house rules.
 *
 * @param feePercent          listing fee as a percentage of the starting price
 * @param minFee              fee floor
 * @param maxListings         concurrent listings per player
 * @param minPrice            smallest allowed starting price
 * @param maxPrice            largest allowed price
 * @param defaultDuration     default listing length in milliseconds
 * @param maxDuration         maximum listing length
 * @param minBidIncrementPct  a new bid must beat the previous one by this much
 * @param taxPercent          sales tax taken from the seller's proceeds
 */
public record AuctionSettings(double feePercent, double minFee, int maxListings, double minPrice,
                              double maxPrice, long defaultDuration, long maxDuration,
                              double minBidIncrementPct, double taxPercent) {

    public static AuctionSettings defaults() {
        return new AuctionSettings(5.0, 5.0, 8, 1.0, 5_000_000.0,
                48 * 3_600_000L, 72 * 3_600_000L, 5.0, 2.0);
    }
}
