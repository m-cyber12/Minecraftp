package io.craftrealm.core.auction;

import io.craftrealm.core.config.AuctionSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionFeeCalculatorTest {

    private final AuctionFeeCalculator fees = new AuctionFeeCalculator(
            new AuctionSettings(5.0, 5.0, 8, 1.0, 5_000_000.0, 48 * 3_600_000L,
                    72 * 3_600_000L, 5.0, 2.0));

    @Test
    void feeIsAPercentageWithAMinimum() {
        assertEquals(5.0, fees.listingFee(20), 0.0001);   // 5% = 1 → minimum 5
        assertEquals(50.0, fees.listingFee(1000), 0.0001);
    }

    @Test
    void taxIsTakenFromTheSale() {
        assertEquals(20.0, fees.tax(1000), 0.0001);
        assertEquals(980.0, fees.sellerProceeds(1000), 0.0001);
    }

    @Test
    void minimumBidUsesTheStartingPriceWhenThereAreNoBids() {
        assertEquals(100.0, fees.minimumBid(0, 100), 0.0001);
    }

    @Test
    void minimumBidMustBeatTheCurrentByTheIncrement() {
        assertEquals(105.0, fees.minimumBid(100, 50), 0.0001);
        assertTrue(fees.minimumBid(100, 50) > 100);
    }
}
