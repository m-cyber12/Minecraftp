package io.craftrealm.api.model;

/**
 * The five careers a player can hold at the same time. Each one has its own
 * level curve and income multiplier.
 */
public enum JobType {
    MINER("miner", "blocks mined"),
    FARMER("farmer", "crops harvested"),
    HUNTER("hunter", "mobs slain"),
    FISHER("fisher", "fish caught"),
    BUILDER("builder", "blocks placed");

    private final String key;
    private final String unit;

    JobType(String key, String unit) {
        this.key = key;
        this.unit = unit;
    }

    /** Config/i18n key used by {@code jobs.<key>.*}. */
    public String key() {
        return key;
    }

    /** Human readable unit for progress messages. */
    public String unit() {
        return unit;
    }

    /** Lenient parse used by commands and the web API. */
    public static JobType parse(String raw) {
        if (raw == null) {
            return null;
        }
        for (JobType type : values()) {
            if (type.key.equalsIgnoreCase(raw) || type.name().equalsIgnoreCase(raw)) {
                return type;
            }
        }
        return null;
    }
}
