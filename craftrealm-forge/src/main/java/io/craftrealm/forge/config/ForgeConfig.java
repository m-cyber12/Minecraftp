package io.craftrealm.forge.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** Forge's common config, safe to read from both server and common mod code. */
public final class ForgeConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.DoubleValue STARTING_BALANCE;
    public static final ForgeConfigSpec.DoubleValue MAX_TRANSACTION;
    public static final ForgeConfigSpec.DoubleValue MAX_BALANCE;
    public static final ForgeConfigSpec.ConfigValue<String> CURRENCY_NAME;
    public static final ForgeConfigSpec.ConfigValue<String> CURRENCY_SYMBOL;
    public static final ForgeConfigSpec.IntValue DATABASE_THREADS;
    public static final ForgeConfigSpec SPEC;

    static {
        BUILDER.push("economy");
        CURRENCY_NAME = BUILDER.comment("Display name for the primary currency.")
                .define("currency-name", "Crowns");
        CURRENCY_SYMBOL = BUILDER.comment("Short currency symbol shown in chat.")
                .define("currency-symbol", "C");
        STARTING_BALANCE = BUILDER.defineInRange("starting-balance", 100.0, 0.0, 1.0e12);
        MAX_TRANSACTION = BUILDER.defineInRange("max-transaction", 1_000_000.0, 0.01, 1.0e12);
        MAX_BALANCE = BUILDER.defineInRange("max-balance", 1.0e12, 0.01, 1.0e15);
        DATABASE_THREADS = BUILDER.defineInRange("database-threads", 2, 1, 8);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private ForgeConfig() {
    }
}
