package io.craftrealm.api.model;

/**
 * Outcome of an economy mutation.
 *
 * <p>Commands and GUIs translate the {@link Reason} into a message key
 * ({@code economy.result.<reason>}) so every failure gets a proper, localised
 * explanation instead of a bare {@code false}.</p>
 *
 * @param success      whether the money moved
 * @param reason       machine readable cause
 * @param balanceAfter balance of the acting account after the attempt
 */
public record EconomyResult(boolean success, Reason reason, double balanceAfter) {

    public enum Reason {
        OK,
        INVALID_AMOUNT,
        INSUFFICIENT_FUNDS,
        LIMIT_EXCEEDED,
        RATE_LIMITED,
        SELF_TRANSFER,
        DUPLICATE_OPERATION,
        ACCOUNT_FROZEN,
        NO_ACCOUNT,
        ERROR
    }

    public static EconomyResult ok(double balanceAfter) {
        return new EconomyResult(true, Reason.OK, balanceAfter);
    }

    public static EconomyResult fail(Reason reason, double balanceAfter) {
        return new EconomyResult(false, reason, balanceAfter);
    }

    public static EconomyResult fail(Reason reason) {
        return new EconomyResult(false, reason, 0.0);
    }

    /** i18n key describing the failure, e.g. {@code economy.result.insufficient_funds}. */
    public String messageKey() {
        return "economy.result." + reason.name().toLowerCase(java.util.Locale.ROOT);
    }
}
