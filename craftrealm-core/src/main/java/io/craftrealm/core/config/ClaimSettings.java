package io.craftrealm.core.config;

/**
 * Land claim pricing and decay.
 *
 * @param enabled        master switch
 * @param pricePerBlock  purchase price per block
 * @param upkeepPerBlock daily upkeep per block
 * @param minSize        minimum side length
 * @param maxSize        maximum side length
 * @param maxClaims      claims per player
 * @param graceDays      days of missed upkeep before decay begins
 */
public record ClaimSettings(boolean enabled, double pricePerBlock, double upkeepPerBlock,
                            int minSize, int maxSize, int maxClaims, int graceDays) {

    public static ClaimSettings defaults() {
        return new ClaimSettings(true, 0.5, 0.02, 16, 64, 3, 3);
    }
}
