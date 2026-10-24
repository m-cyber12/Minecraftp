package io.craftrealm.api.model;

/** Lifecycle of a bank loan. */
public enum LoanStatus {
    /** Money borrowed, not fully repaid, not yet past the due date. */
    ACTIVE,
    /** Fully repaid. */
    REPAID,
    /** Passed the due date with an outstanding balance. */
    DEFAULTED
}
