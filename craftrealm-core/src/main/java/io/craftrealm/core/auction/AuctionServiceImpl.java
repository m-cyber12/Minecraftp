package io.craftrealm.core.auction;

import io.craftrealm.api.event.AuctionBidEvent;
import io.craftrealm.api.model.AuctionListing;
import io.craftrealm.api.model.CollectedEntry;
import io.craftrealm.api.model.EconomyResult;
import io.craftrealm.api.model.ListingStatus;
import io.craftrealm.api.model.MailboxEntry;
import io.craftrealm.api.model.MailboxKind;
import io.craftrealm.api.model.TransactionType;
import io.craftrealm.api.service.AuctionService;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.core.config.AuctionSettings;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.repo.AuctionRepository;
import io.craftrealm.core.util.Events;
import io.craftrealm.core.util.Money;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The auction house.
 *
 * <p>Design rules that keep items and money safe:</p>
 * <ol>
 *   <li>The item leaves the seller's inventory in the GUI (main thread) and is
 *       serialised before any database work — the seller can never keep the item
 *       while also owning a listing.</li>
 *   <li>Bids are escrowed immediately; the previous leader is refunded in the same
 *       code path, and the conditional {@code placeBid} update decides the winner.</li>
 *   <li>Delivery is always a mailbox row, so an offline player or a full inventory
 *       never destroys an item.</li>
 *   <li>Collection uses {@code collect:<id>} operation ids, so nothing pays twice.</li>
 * </ol>
 */
public final class AuctionServiceImpl implements AuctionService {

    private static final long HOUR = 3_600_000L;

    private final Database database;
    private final AuctionRepository repository;
    private final EconomyService economy;
    private final AuctionFeeCalculator fees;
    private final AuctionSettings settings;

    public AuctionServiceImpl(Database database, EconomyService economy, AuctionSettings settings) {
        this.database = database;
        this.repository = new AuctionRepository(database);
        this.economy = economy;
        this.settings = settings;
        this.fees = new AuctionFeeCalculator(settings);
    }

    @Override
    public double listingFee(double price) {
        return fees.listingFee(price);
    }

    @Override
    public double listingCost(ItemStack item, double price) {
        return fees.listingFee(price);
    }

    // ------------------------------------------------------------------ create

    @Override
    public CompletableFuture<AuctionListing> create(UUID seller, ItemStack item, double startingBid,
                                                    double buyNow, long durationMillis) {
        if (item == null || item.getType().isAir()) {
            return CompletableFuture.completedFuture(null);
        }
        double start = Money.round(startingBid);
        if (start < settings.minPrice() || start > settings.maxPrice()) {
            return CompletableFuture.completedFuture(null);
        }
        double instant = Money.round(buyNow);
        if (instant != 0 && (instant < start || instant > settings.maxPrice())) {
            return CompletableFuture.completedFuture(null);
        }
        long duration = Math.max(HOUR, Math.min(settings.maxDuration(),
                durationMillis <= 0 ? settings.defaultDuration() : durationMillis));
        ItemStack stored = item.clone();
        byte[] bytes = stored.serializeAsBytes();
        double fee = fees.listingFee(start);

        return repository.activeCount(seller).thenCompose(active -> {
            if (active >= settings.maxListings()) {
                return CompletableFuture.completedFuture(null);
            }
            return economy.withdraw(seller, fee, TransactionType.AUCTION_LIST,
                            "auction listing fee").thenCompose(result -> {
                if (!result.success()) {
                    return CompletableFuture.completedFuture(null);
                }
                return repository.create(seller, nameOf(seller), bytes, start, instant,
                                System.currentTimeMillis() + duration, fee)
                        .exceptionally(throwable -> {
                            // Refund the fee if the row could not be written.
                            economy.credit(seller, fee, TransactionType.AUCTION_REFUND,
                                    "listing fee refund", null);
                            return null;
                        });
            });
        });
    }

    @Override
    public CompletableFuture<AuctionListing> listing(long id) {
        return repository.find(id).thenApply(optional -> optional.orElse(null));
    }

    @Override
    public CompletableFuture<List<AuctionListing>> active(int offset, int limit) {
        return repository.open(System.currentTimeMillis(), offset, limit);
    }

    @Override
    public CompletableFuture<List<AuctionListing>> bySeller(UUID seller) {
        return repository.bySeller(seller);
    }

    // --------------------------------------------------------------------- bid

    @Override
    public CompletableFuture<EconomyResult> bid(UUID bidder, long listingId, double amount) {
        double value = Money.round(amount);
        return repository.find(listingId).thenCompose(optional -> {
            if (optional.isEmpty()) {
                return fail(EconomyResult.Reason.NO_ACCOUNT);
            }
            AuctionListing listing = optional.get();
            if (!listing.isActive()) {
                return fail(EconomyResult.Reason.ERROR);
            }
            if (listing.seller().equals(bidder)) {
                return fail(EconomyResult.Reason.SELF_TRANSFER);
            }
            double minimum = fees.minimumBid(listing.topBid(), listing.startingBid());
            if (value < minimum) {
                return fail(EconomyResult.Reason.INVALID_AMOUNT);
            }
            return Events.callCancellable(new AuctionBidEvent(listingId, bidder, value))
                    .thenCompose(allowed -> {
                        if (!allowed) {
                            return fail(EconomyResult.Reason.ERROR);
                        }
                        return economy.withdraw(bidder, value, TransactionType.AUCTION_BID,
                                        "bid on listing #" + listingId)
                                .thenCompose(payment -> {
                                    if (!payment.success()) {
                                        return CompletableFuture.completedFuture(payment);
                                    }
                                    return repository.placeBid(listingId, bidder, value)
                                            .thenApply(placed -> {
                                                if (!placed) {
                                                    economy.credit(bidder, value, TransactionType.AUCTION_REFUND,
                                                            "bid rejected", null);
                                                    return EconomyResult.fail(EconomyResult.Reason.ERROR,
                                                            payment.balanceAfter());
                                                }
                                                refundOutbid(listing, bidder);
                                                return payment;
                                            });
                                });
                    });
        });
    }

    /** Returns the previous leader's escrow. Fire-and-forget by design. */
    private void refundOutbid(AuctionListing listing, UUID newBidder) {
        if (listing.topBidder() != null && listing.topBid() > 0
                && !listing.topBidder().equals(newBidder)) {
            economy.credit(listing.topBidder(), listing.topBid(), TransactionType.AUCTION_REFUND,
                    "outbid on listing #" + listing.id(), null);
        }
    }

    // ------------------------------------------------------------------ buy now

    @Override
    public CompletableFuture<EconomyResult> buyNow(UUID buyer, long listingId) {
        return repository.find(listingId).thenCompose(optional -> {
            if (optional.isEmpty()) {
                return fail(EconomyResult.Reason.NO_ACCOUNT);
            }
            AuctionListing listing = optional.get();
            if (!listing.isActive() || listing.buyNow() <= 0) {
                return fail(EconomyResult.Reason.ERROR);
            }
            if (listing.seller().equals(buyer)) {
                return fail(EconomyResult.Reason.SELF_TRANSFER);
            }
            double price = listing.buyNow();
            return economy.withdraw(buyer, price, TransactionType.AUCTION_SALE,
                            "buy-now listing #" + listingId)
                    .thenCompose(payment -> {
                        if (!payment.success()) {
                            return CompletableFuture.completedFuture(payment);
                        }
                        return repository.markSold(listingId, buyer).thenCompose(sold -> {
                            if (!sold) {
                                economy.credit(buyer, price, TransactionType.AUCTION_REFUND,
                                        "listing already sold", null);
                                return fail(EconomyResult.Reason.ERROR);
                            }
                            return settle(listing, buyer, price).thenApply(ignored -> payment);
                        });
                    });
        });
    }

    /** Pays the seller and posts the item to the buyer's mailbox. */
    private CompletableFuture<Void> settle(AuctionListing listing, UUID buyer, double price) {
        double proceeds = fees.sellerProceeds(price);
        CompletableFuture<Long> money = repository.addMailbox(listing.seller(), MailboxKind.MONEY, null,
                proceeds, "Sale of listing #" + listing.id());
        CompletableFuture<Long> item = repository.addMailbox(buyer, MailboxKind.ITEM, listing.item(),
                0.0, "Won listing #" + listing.id());
        return CompletableFuture.allOf(money, item);
    }

    @Override
    public CompletableFuture<EconomyResult> cancel(UUID seller, long listingId) {
        return repository.find(listingId).thenCompose(optional -> {
            if (optional.isEmpty()) {
                return fail(EconomyResult.Reason.NO_ACCOUNT);
            }
            AuctionListing listing = optional.get();
            if (!listing.seller().equals(seller) || !listing.isActive()) {
                return fail(EconomyResult.Reason.ERROR);
            }
            if (listing.topBidder() != null) {
                // Bids are escrowed; cancelling would steal from the bidder.
                return fail(EconomyResult.Reason.ERROR);
            }
            return repository.markStatus(listingId, ListingStatus.CANCELLED).thenCompose(cancelled -> {
                if (!cancelled) {
                    return fail(EconomyResult.Reason.ERROR);
                }
                return repository.addMailbox(seller, MailboxKind.ITEM, listing.item(), 0.0,
                                "Cancelled listing #" + listingId)
                        .thenApply(ignored -> EconomyResult.ok(economy.cachedBalance(seller)));
            });
        });
    }

    // ----------------------------------------------------------------- mailbox

    @Override
    public CompletableFuture<List<MailboxEntry>> mailbox(UUID owner) {
        return repository.mailbox(owner, 64);
    }

    @Override
    public CompletableFuture<Long> unclaimed(UUID owner) {
        return repository.unclaimedCount(owner);
    }

    @Override
    public CompletableFuture<CollectedEntry> collect(UUID owner, long entryId, boolean inventoryFree) {
        return repository.mailboxEntry(entryId).thenCompose(optional -> {
            if (optional.isEmpty() || !optional.get().owner().equals(owner)) {
                return CompletableFuture.completedFuture(
                        new CollectedEntry(EconomyResult.fail(EconomyResult.Reason.NO_ACCOUNT), null, null, 0));
            }
            MailboxEntry entry = optional.get();
            if (entry.claimed()) {
                return CompletableFuture.completedFuture(
                        new CollectedEntry(EconomyResult.fail(EconomyResult.Reason.DUPLICATE_OPERATION), null, null, 0));
            }
            if (entry.kind() == MailboxKind.ITEM && !inventoryFree) {
                // Never claim an item we cannot hand over.
                return CompletableFuture.completedFuture(
                        new CollectedEntry(EconomyResult.fail(EconomyResult.Reason.LIMIT_EXCEEDED), null, null, 0));
            }
            return repository.claimMailbox(entryId, owner).thenCompose(claimed -> {
                if (!claimed) {
                    return CompletableFuture.completedFuture(new CollectedEntry(
                            EconomyResult.fail(EconomyResult.Reason.DUPLICATE_OPERATION), null, null, 0));
                }
                if (entry.kind() == MailboxKind.ITEM) {
                    ItemStack item = deserialize(entry.item());
                    return CompletableFuture.completedFuture(new CollectedEntry(
                            EconomyResult.ok(economy.cachedBalance(owner)), MailboxKind.ITEM, item, 0.0));
                }
                return economy.credit(owner, entry.amount(), TransactionType.MAILBOX_COLLECT,
                                entry.note() == null ? "mailbox" : entry.note(), "collect:" + entryId)
                        .thenCompose(result -> {
                            if (!result.success() && result.reason() != EconomyResult.Reason.DUPLICATE_OPERATION) {
                                repository.unclaim(entryId);
                            }
                            return CompletableFuture.completedFuture(new CollectedEntry(
                                    result, MailboxKind.MONEY, null, entry.amount()));
                        });
            });
        });
    }

    // ------------------------------------------------------------------ expiry

    @Override
    public CompletableFuture<Integer> expireDue() {
        long now = System.currentTimeMillis();
        return repository.due(now).thenCompose(due -> {
            CompletableFuture<?>[] work = due.stream().map(listing -> {
                if (listing.topBidder() != null && listing.topBid() > 0) {
                    return repository.markSold(listing.id(), listing.topBidder())
                            .thenCompose(sold -> sold
                                    ? settle(listing, listing.topBidder(), listing.topBid())
                                    : CompletableFuture.completedFuture(null));
                }
                return repository.markStatus(listing.id(), ListingStatus.EXPIRED)
                        .thenCompose(expired -> expired
                                ? repository.addMailbox(listing.seller(), MailboxKind.ITEM, listing.item(),
                                0.0, "Expired listing #" + listing.id()).thenApply(id -> null)
                                : CompletableFuture.completedFuture(null));
            }).toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(work).thenApply(ignored -> due.size());
        });
    }

    // ----------------------------------------------------------------- helpers

    /** Safe item deserialisation: a corrupt blob yields {@code null}, never a crash. */
    private static ItemStack deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (RuntimeException exception) {
            io.craftrealm.core.CraftRealmCore.logger()
                    .warning("Could not deserialize an auction item: " + exception.getMessage());
            return null;
        }
    }

    private String nameOf(UUID uuid) {
        return io.craftrealm.core.CraftRealmCore.nameCache().getOrDefault(uuid, uuid.toString().substring(0, 8));
    }

    private static CompletableFuture<EconomyResult> fail(EconomyResult.Reason reason) {
        return CompletableFuture.completedFuture(EconomyResult.fail(reason));
    }
}
