package io.craftrealm.api.model;

/** Visual trend of a market price, rendered as an arrow in the shop GUI. */
public enum PriceTrend {
    RISING("↑", "emerald"),
    FALLING("↓", "redstone"),
    STABLE("→", "stone");

    private final String arrow;
    private final String colour;

    PriceTrend(String arrow, String colour) {
        this.arrow = arrow;
        this.colour = colour;
    }

    public String arrow() {
        return arrow;
    }

    /** Theme token name, resolved by the GUI layer. */
    public String colour() {
        return colour;
    }
}
