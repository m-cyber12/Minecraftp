package io.craftrealm.core.auction;

import io.craftrealm.core.config.AuctionSettings;
import io.craftrealm.core.util.Money;

/**
 * Auction fees and taxes. Pure functions, unit tested.
 *
 * <p>The listing fee is charged up-front and is <b>not</b> refunded, which is what
 * keeps the auction house from being spammed with 1-Crown listings. The sales tax
 * is deducted from the seller's proceeds.</p>
 */
public final class AuctionFeeCalculator {

    private final AuctionSettings settings;

    public AuctionFeeCalculator(AuctionSettings settings) {
        this.settings = settings;
    }

    /** Up-front cost of creating a listing at {@code startingPrice}. */
    public double listingFee(double startingPrice) {
        double percent = Math.max(0.0, startingPrice) * settings.feePercent() / 100.0;
        return Money.round(Math.max(settings.minFee(), percent));
    }

    /** Tax withheld from a completed sale. */
    public double tax(double salePrice) {
        return Money.round(Math.max(0.0, salePrice) * settings.taxPercent() / 100.0);
    }

    /** What the seller actually receives. */
    public double sellerProceeds(double salePrice) {
        return Money.round(Math.max(0.0, salePrice) - tax(salePrice));
    }

    /**
     * Smallest acceptable next bid: the current top bid plus the configured
     * increment, or the starting bid when nothing has been bid yet.
     */
    public double minimumBid(double currentTop, double startingBid) {
        if (currentTop <= 0) {
            return Money.round(startingBid);
        }
        return Money.round(currentTop * (1.0 + settings.minBidIncrementPct() / 100.0));
    }

    public AuctionSettings settings() {
        return settings;
    }
}
