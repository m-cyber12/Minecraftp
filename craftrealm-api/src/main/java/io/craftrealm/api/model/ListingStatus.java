package io.craftrealm.api.model;

/** Lifecycle of an auction listing. */
public enum ListingStatus {
    /** Accepting bids. */
    ACTIVE,
    /** Bought or won; awaiting delivery. */
    SOLD,
    /** Ran out of time without a buyer. */
    EXPIRED,
    /** Withdrawn by the seller. */
    CANCELLED
}
