package io.craftrealm.api.service;

import io.craftrealm.api.model.AuctionListing;
import io.craftrealm.api.model.CollectedEntry;
import io.craftrealm.api.model.EconomyResult;
import io.craftrealm.api.model.MailboxEntry;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The auction house: bidding, buy-now, listing fees, expiry and mail-style
 * collection.
 *
 * <p>Money is escrowed the moment a bid is placed, so a bidder can never win
 * with funds they no longer have. Everything a player is owed arrives in the
 * mailbox and is collected with an idempotent operation id.</p>
 */
public interface AuctionService {

    /** Fee charged up-front for a listing of this value. */
    double listingFee(double price);

    /** Total cost of listing an item (fee) — shown in the GUI. */
    double listingCost(ItemStack item, double price);

    CompletableFuture<AuctionListing> create(UUID seller, ItemStack item, double startingBid,
                                             double buyNow, long durationMillis);

    CompletableFuture<AuctionListing> listing(long id);

    CompletableFuture<List<AuctionListing>> active(int offset, int limit);

    CompletableFuture<List<AuctionListing>> bySeller(UUID seller);

    CompletableFuture<EconomyResult> bid(UUID bidder, long listingId, double amount);

    CompletableFuture<EconomyResult> buyNow(UUID buyer, long listingId);

    CompletableFuture<EconomyResult> cancel(UUID seller, long listingId);

    CompletableFuture<List<MailboxEntry>> mailbox(UUID owner);

    /**
     * Delivers one mailbox entry.
     *
     * <p>Idempotent: the entry is claimed with a conditional update and money is
     * credited under the operation id {@code collect:<entryId>}, so a double click
     * (or a retried web call) can only ever pay once.</p>
     *
     * @param inventoryFree whether the caller has verified there is room for an
     *                      item entry — item entries are never claimed otherwise
     */
    CompletableFuture<CollectedEntry> collect(UUID owner, long entryId, boolean inventoryFree);

    /** Number of unclaimed entries. */
    CompletableFuture<Long> unclaimed(UUID owner);

    /** Scheduled: closes expired listings, pays sellers and moves items to mail. */
    CompletableFuture<Integer> expireDue();
}
