package io.craftrealm.core.config;

/**
 * Everything the economy core needs, loaded from {@code config.yml}.
 *
 * @param currencyName      singular/plural display name ("Crowns")
 * @param currencySymbol    suffix glyph ("⛃")
 * @param decimals          visible decimal places
 * @param startingBalance   granted once when an account is first created
 * @param maxTransaction    hard ceiling per single transaction (anti-fat-finger)
 * @param minTransfer       smallest allowed /pay
 * @param transactionsPerSecond per-player anti-spam ceiling
 * @param alertThreshold    transfers above this raise a staff alert
 * @param serverId          written to the ledger for multi-node servers
 */
public record EconomySettings(String currencyName, String currencySymbol, int decimals,
                              double startingBalance, double maxTransaction, double minTransfer,
                              int transactionsPerSecond, double alertThreshold, String serverId) {

    public static EconomySettings defaults() {
        return new EconomySettings("Crowns", "⛃", 2, 100.0, 1_000_000_000.0, 0.01,
                20, 100_000.0, "local");
    }
}
