package io.craftrealm.forge.api;

/** Transaction category exposed to other Forge mods. */
public enum ForgeTransactionType {
    PAY,
    BANK_DEPOSIT,
    BANK_WITHDRAW,
    BANK_INTEREST,
    LOAN_ISSUE,
    LOAN_REPAY,
    LOAN_PENALTY,
    SHOP_BUY,
    SHOP_SELL,
    AUCTION_BID,
    AUCTION_SALE,
    ADMIN_ADJUSTMENT
}
