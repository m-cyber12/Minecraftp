package io.craftrealm.api.model;

import java.util.UUID;

/**
 * An auction house entry.
 *
 * <p>The item is stored as serialised bytes so the listing survives restarts and
 * can be validated (stack size, banned materials) before delivery.</p>
 *
 * @param id         listing id
 * @param seller     who listed it
 * @param sellerName cached display name (denormalised for sorting/GUI)
 * @param item       serialised {@code ItemStack}
 * @param startingBid minimum bid in Crowns
 * @param buyNow     instant purchase price, {@code 0} disables buy-now
 * @param topBidder  current leader, {@code null} if no bids
 * @param topBid     current highest bid, {@code 0} if no bids
 * @param createdAt  epoch millis
 * @param endsAt     epoch millis
 * @param status     lifecycle state
 * @param listingFee fee already charged when the listing was created
 */
public record AuctionListing(long id, UUID seller, String sellerName, byte[] item,
                             double startingBid, double buyNow, UUID topBidder, double topBid,
                             long createdAt, long endsAt, ListingStatus status, double listingFee) {

    public boolean isActive() {
        return status == ListingStatus.ACTIVE && System.currentTimeMillis() < endsAt;
    }

    public double currentPrice() {
        return topBid > 0 ? topBid : startingBid;
    }

    public long remainingMillis() {
        return Math.max(0L, endsAt - System.currentTimeMillis());
    }
}
