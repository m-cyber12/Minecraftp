package io.craftrealm.api.model;

/**
 * Every movement of Crowns is classified so the economy can be audited and the
 * inflation graph can tell "new money printed" apart from "money swapped hands".
 *
 * <p>{@link Category#SOURCE} creates currency (jobs, interest, rewards),
 * {@link Category#SINK} destroys it (shop purchases, fees, upkeep),
 * {@link Category#TRANSFER} only moves it, and {@link Category#ADJUSTMENT}
 * covers staff corrections and rollbacks.</p>
 */
public enum TransactionType {

    // --- player to player ----------------------------------------------------
    PAY(Category.TRANSFER),

    // --- server sinks --------------------------------------------------------
    SHOP_BUY(Category.SINK),
    AUCTION_LIST(Category.SINK),
    CLAIM_PURCHASE(Category.SINK),
    CLAIM_UPKEEP(Category.SINK),
    LOAN_PENALTY(Category.SINK),
    COSMETIC_PURCHASE(Category.SINK),
    TAX(Category.SINK),
    NOTE_ISSUE(Category.SINK),

    // --- server sources ------------------------------------------------------
    SHOP_SELL(Category.SOURCE),
    CHEST_SHOP_SALE(Category.SOURCE),
    JOB_INCOME(Category.SOURCE),
    BANK_INTEREST(Category.SOURCE),
    LOAN_ISSUE(Category.SOURCE),
    QUEST_REWARD(Category.SOURCE),
    EVENT_REWARD(Category.SOURCE),
    SEASON_REWARD(Category.SOURCE),
    TREASURE_REWARD(Category.SOURCE),
    STREAK_REWARD(Category.SOURCE),
    NOTE_REDEEM(Category.SOURCE),
    COIN_REDEEM(Category.SOURCE),

    // --- escrow / internal transfers ----------------------------------------
    BANK_DEPOSIT(Category.TRANSFER),
    BANK_WITHDRAW(Category.TRANSFER),
    AUCTION_BID(Category.TRANSFER),
    AUCTION_SALE(Category.TRANSFER),
    AUCTION_REFUND(Category.TRANSFER),
    BOUNTY_PLACE(Category.TRANSFER),
    BOUNTY_CLAIM(Category.TRANSFER),
    MAILBOX_COLLECT(Category.TRANSFER),
    CHEST_SHOP_BUY(Category.TRANSFER),
    LOAN_REPAY(Category.TRANSFER),

    // --- staff / maintenance -------------------------------------------------
    ADMIN_SET(Category.ADJUSTMENT),
    ADMIN_GIVE(Category.ADJUSTMENT),
    ADMIN_TAKE(Category.ADJUSTMENT),
    ROLLBACK(Category.ADJUSTMENT);

    /** Broad accounting bucket used by the economy dashboard. */
    public enum Category { SOURCE, SINK, TRANSFER, ADJUSTMENT }

    private final Category category;

    TransactionType(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }

    /** True when this transaction generated currency out of nothing. */
    public boolean createsMoney() {
        return category == Category.SOURCE;
    }

    /** True when this transaction removed currency from the world. */
    public boolean destroysMoney() {
        return category == Category.SINK;
    }
}
