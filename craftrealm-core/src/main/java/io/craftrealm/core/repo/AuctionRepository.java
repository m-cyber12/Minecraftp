package io.craftrealm.core.repo;

import io.craftrealm.api.model.AuctionListing;
import io.craftrealm.api.model.ListingStatus;
import io.craftrealm.api.model.MailboxEntry;
import io.craftrealm.api.model.MailboxKind;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.db.Jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Auction listings, bid history and the mailbox.
 *
 * <p>Every state transition is a conditional update keyed on the previous state:
 * {@code WHERE status = 'ACTIVE'} for a sale, {@code WHERE top_bid < ?} for a bid.
 * Two players clicking "buy" at the same tick therefore produce exactly one
 * purchase.</p>
 */
public final class AuctionRepository {

    private final Database database;

    public AuctionRepository(Database database) {
        this.database = database;
    }

    public CompletableFuture<AuctionListing> create(UUID seller, String sellerName, byte[] item,
                                                    double startingBid, double buyNow, long endsAt,
                                                    double listingFee) {
        return database.supply(connection -> {
            long now = System.currentTimeMillis();
            long id = Jdbc.insert(connection,
                    "INSERT INTO cr_auctions (seller, seller_name, item, starting_bid, buy_now, top_bidder,"
                            + " top_bid, created_at, ends_at, status, listing_fee)"
                            + " VALUES (?, ?, ?, ?, ?, NULL, 0, ?, ?, ?, ?)",
                    seller, sellerName, item, startingBid, buyNow, now, endsAt, ListingStatus.ACTIVE,
                    listingFee);
            return new AuctionListing(id, seller, sellerName, item, startingBid, buyNow, null, 0.0,
                    now, endsAt, ListingStatus.ACTIVE, listingFee);
        });
    }

    public CompletableFuture<Optional<AuctionListing>> find(long id) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT * FROM cr_auctions WHERE id = ?", AuctionRepository::map, id));
    }

    public CompletableFuture<List<AuctionListing>> open(long now, int offset, int limit) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_auctions WHERE status = ? AND ends_at > ? "
                        + "ORDER BY ends_at ASC LIMIT ? OFFSET ?",
                AuctionRepository::map, ListingStatus.ACTIVE, now, Math.max(1, limit), Math.max(0, offset)));
    }

    public CompletableFuture<Long> countOpen(long now) {
        return database.supply(connection -> Jdbc.scalar(connection,
                "SELECT COUNT(*) FROM cr_auctions WHERE status = ? AND ends_at > ?",
                ListingStatus.ACTIVE, now));
    }

    public CompletableFuture<List<AuctionListing>> bySeller(UUID seller) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_auctions WHERE seller = ? ORDER BY id DESC LIMIT 64",
                AuctionRepository::map, seller));
    }

    public CompletableFuture<Long> activeCount(UUID seller) {
        return database.supply(connection -> Jdbc.scalar(connection,
                "SELECT COUNT(*) FROM cr_auctions WHERE seller = ? AND status = ?",
                seller, ListingStatus.ACTIVE));
    }

    /** Conditional bid: only succeeds when it beats the current top bid. */
    public CompletableFuture<Boolean> placeBid(long id, UUID bidder, double amount) {
        return database.inTransaction(connection -> {
            long now = System.currentTimeMillis();
            int updated = Jdbc.update(connection,
                    "UPDATE cr_auctions SET top_bidder = ?, top_bid = ? "
                            + "WHERE id = ? AND status = ? AND ends_at > ?"
                            + " AND top_bid < ? AND seller <> ?",
                    bidder, amount, id, ListingStatus.ACTIVE, now, amount, bidder);
            if (updated == 0) {
                return false;
            }
            Jdbc.update(connection,
                    "INSERT INTO cr_auction_bids (auction_id, bidder, amount, created_at) VALUES (?, ?, ?, ?)",
                    id, bidder, amount, now);
            return true;
        });
    }

    /** Conditional sale; returns {@code false} if someone else already bought it. */
    public CompletableFuture<Boolean> markSold(long id, UUID buyer) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_auctions SET status = ?, top_bidder = ? WHERE id = ? AND status = ?",
                ListingStatus.SOLD, buyer, id, ListingStatus.ACTIVE) > 0);
    }

    public CompletableFuture<Boolean> markStatus(long id, ListingStatus status) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_auctions SET status = ? WHERE id = ? AND status = ?",
                status, id, ListingStatus.ACTIVE) > 0);
    }

    /** Listings whose timer ran out; the caller refunds/transfers and marks them. */
    public CompletableFuture<List<AuctionListing>> due(long now) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_auctions WHERE status = ? AND ends_at <= ?",
                AuctionRepository::map, ListingStatus.ACTIVE, now));
    }

    // ----------------------------------------------------------------- mailbox

    public CompletableFuture<Long> addMailbox(UUID owner, MailboxKind kind, byte[] item, double amount, String note) {
        return database.supply(connection -> Jdbc.insert(connection,
                "INSERT INTO cr_mailbox (owner, kind, item, amount, note, claimed, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 0, ?)",
                owner, kind, item, amount, note, System.currentTimeMillis()));
    }

    public CompletableFuture<List<MailboxEntry>> mailbox(UUID owner, int limit) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_mailbox WHERE owner = ? AND claimed = 0 ORDER BY id DESC LIMIT ?",
                AuctionRepository::mapMail, owner, Math.max(1, limit)));
    }

    public CompletableFuture<Optional<MailboxEntry>> mailboxEntry(long id) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT * FROM cr_mailbox WHERE id = ?", AuctionRepository::mapMail, id));
    }

    /**
     * Atomically claims a mailbox entry. The single-row update is what makes
     * collection idempotent: whoever wins the update is the one who gets paid.
     */
    public CompletableFuture<Boolean> claimMailbox(long id, UUID owner) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_mailbox SET claimed = 1 WHERE id = ? AND owner = ? AND claimed = 0",
                id, owner) > 0);
    }

    /** Releases a claim when the payout could not be completed, so it can be retried. */
    public CompletableFuture<Void> unclaim(long id) {
        return database.run(connection -> Jdbc.update(connection,
                "UPDATE cr_mailbox SET claimed = 0 WHERE id = ?", id));
    }

    public CompletableFuture<Long> unclaimedCount(UUID owner) {
        return database.supply(connection -> Jdbc.scalar(connection,
                "SELECT COUNT(*) FROM cr_mailbox WHERE owner = ? AND claimed = 0", owner));
    }

    private static AuctionListing map(ResultSet row) throws SQLException {
        return new AuctionListing(
                row.getLong("id"),
                UUID.fromString(row.getString("seller")),
                row.getString("seller_name"),
                row.getBytes("item"),
                row.getDouble("starting_bid"),
                row.getDouble("buy_now"),
                Jdbc.uuid(row, "top_bidder"),
                row.getDouble("top_bid"),
                row.getLong("created_at"),
                row.getLong("ends_at"),
                ListingStatus.valueOf(row.getString("status")),
                row.getDouble("listing_fee"));
    }

    private static MailboxEntry mapMail(ResultSet row) throws SQLException {
        return new MailboxEntry(
                row.getLong("id"),
                UUID.fromString(row.getString("owner")),
                MailboxKind.valueOf(row.getString("kind")),
                row.getBytes("item"),
                row.getDouble("amount"),
                row.getString("note"),
                row.getInt("claimed") != 0,
                row.getLong("created_at"));
    }
}
