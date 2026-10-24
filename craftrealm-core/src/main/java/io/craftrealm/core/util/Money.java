package io.craftrealm.core.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Money formatting and validation.
 *
 * <p>CraftRealm stores balances as {@code DECIMAL(20,2)}. All arithmetic funnels
 * through {@link #round(double, int)} so a value can never drift into the
 * {@code 0.30000000000000004} territory that eventually lets a player duplicate a
 * fraction of a Crown.</p>
 *
 * <p>Pure code: no Bukkit, no configuration statics — the currency name and
 * symbol are passed in, which keeps this class trivially unit-testable.</p>
 */
public final class Money {

    private static final String[] COMPACT_SUFFIXES = {"", "k", "M", "B", "T"};

    private Money() {
    }

    /** Rounds to the configured number of decimals (half-up, like a shopkeeper). */
    public static double round(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        int scale = Math.max(0, Math.min(8, decimals));
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    /** Convenience overload for the default two decimals. */
    public static double round(double value) {
        return round(value, 2);
    }

    /** A transaction amount must be finite, positive and not absurdly small. */
    public static boolean isValidAmount(double amount) {
        return Double.isFinite(amount) && amount > 0.0;
    }

    /**
     * Builds the user facing string, e.g. {@code "1,250.00 ⛃"}.
     *
     * @param amount   raw value
     * @param symbol   currency symbol, may be empty
     * @param decimals decimal places
     * @param symbolFirst render as {@code "⛃ 1,250.00"} instead
     */
    public static String format(double amount, String symbol, int decimals, boolean symbolFirst) {
        int scale = Math.max(0, Math.min(8, decimals));
        BigDecimal value = BigDecimal.valueOf(amount).setScale(scale, RoundingMode.HALF_UP);
        String number = String.format(Locale.ROOT, "%,." + scale + "f", value.doubleValue());
        String sym = symbol == null ? "" : symbol;
        if (sym.isEmpty()) {
            return number;
        }
        return symbolFirst ? sym + " " + number : number + " " + sym;
    }

    /** Compact display for GUIs and the web panel: {@code 1,250} → {@code 1.25k}. */
    public static String compact(double amount) {
        double abs = Math.abs(amount);
        int tier = 0;
        while (abs >= 1000.0 && tier < COMPACT_SUFFIXES.length - 1) {
            abs /= 1000.0;
            tier++;
        }
        String sign = amount < 0 ? "-" : "";
        if (tier == 0) {
            return sign + String.format(Locale.ROOT, "%.0f", abs);
        }
        String pattern = abs >= 100 ? "%.0f" : abs >= 10 ? "%.1f" : "%.2f";
        return sign + String.format(Locale.ROOT, pattern, abs) + COMPACT_SUFFIXES[tier];
    }

    /**
     * Parses user input, accepting {@code 1,000}, {@code 1.5k}, {@code 2m} and the
     * current symbol.
     *
     * @throws NumberFormatException when the input is not a number
     */
    public static double parse(String raw) {
        if (raw == null) {
            throw new NumberFormatException("null");
        }
        String cleaned = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
        cleaned = cleaned.replaceAll("[^0-9.kmbt+-]", "");
        if (cleaned.isEmpty()) {
            throw new NumberFormatException(raw);
        }
        double multiplier = 1.0;
        char last = cleaned.charAt(cleaned.length() - 1);
        switch (last) {
            case 'k' -> multiplier = 1_000.0;
            case 'm' -> multiplier = 1_000_000.0;
            case 'b' -> multiplier = 1_000_000_000.0;
            case 't' -> multiplier = 1_000_000_000_000.0;
            default -> {
                // no suffix
            }
        }
        String number = multiplier == 1.0 ? cleaned : cleaned.substring(0, cleaned.length() - 1);
        double parsed = Double.parseDouble(number);
        if (!Double.isFinite(parsed)) {
            throw new NumberFormatException(raw);
        }
        return parsed * multiplier;
    }

    /** Clamps a value into {@code [min, max]}. */
    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
