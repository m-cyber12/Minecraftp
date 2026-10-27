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
    public static final ForgeConfigSpec.DoubleValue BANK_DAILY_INTEREST_PERCENT;
    public static final ForgeConfigSpec.DoubleValue BANK_MAX_LOAN;
    public static final ForgeConfigSpec.IntValue BANK_LOAN_TERM_DAYS;
    public static final ForgeConfigSpec.DoubleValue BANK_LOAN_PENALTY_PERCENT;
    public static final ForgeConfigSpec.DoubleValue CLAIM_COST;
    public static final ForgeConfigSpec.IntValue MAX_CLAIMS_PER_PLAYER;
    public static final ForgeConfigSpec.DoubleValue JOB_MINER_PAYOUT;
    public static final ForgeConfigSpec.DoubleValue JOB_FARMER_PAYOUT;
    public static final ForgeConfigSpec.DoubleValue JOB_HUNTER_PAYOUT;
    public static final ForgeConfigSpec.DoubleValue JOB_FISHER_PAYOUT;
    public static final ForgeConfigSpec.DoubleValue JOB_BUILDER_PAYOUT;
    public static final ForgeConfigSpec.DoubleValue JOB_DAILY_CAP;
    public static final ForgeConfigSpec.DoubleValue JOB_LEVEL_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue JOB_XP_PER_ACTION;
    public static final ForgeConfigSpec.IntValue JOB_MAX_LEVEL;
    public static final ForgeConfigSpec.IntValue QUEST_ACTIONS_REQUIRED;
    public static final ForgeConfigSpec.DoubleValue QUEST_REWARD;
    public static final ForgeConfigSpec.IntValue SEASON_DURATION_DAYS;
    public static final ForgeConfigSpec.BooleanValue WEB_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> WEB_HOST;
    public static final ForgeConfigSpec.IntValue WEB_PORT;
    public static final ForgeConfigSpec.ConfigValue<String> WEB_TOKEN;
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> MARKET_CATALOGUE;
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
        BUILDER.push("bank");
        BANK_DAILY_INTEREST_PERCENT = BUILDER.defineInRange("daily-interest-percent", 0.25, 0.0, 100.0);
        BANK_MAX_LOAN = BUILDER.defineInRange("maximum-loan", 5_000.0, 0.0, 1.0e12);
        BANK_LOAN_TERM_DAYS = BUILDER.defineInRange("loan-term-days", 14, 1, 3650);
        BANK_LOAN_PENALTY_PERCENT = BUILDER.defineInRange("loan-default-penalty-percent", 10.0, 0.0, 1000.0);
        BUILDER.pop();
        BUILDER.push("claims");
        CLAIM_COST = BUILDER.defineInRange("cost-per-chunk", 250.0, 0.0, 1.0e12);
        MAX_CLAIMS_PER_PLAYER = BUILDER.defineInRange("max-per-player", 16, 1, 1000);
        BUILDER.pop();
        BUILDER.push("jobs");
        JOB_MINER_PAYOUT = BUILDER.defineInRange("miner-payout", 0.25, 0.0, 1_000_000.0);
        JOB_FARMER_PAYOUT = BUILDER.defineInRange("farmer-payout", 0.20, 0.0, 1_000_000.0);
        JOB_HUNTER_PAYOUT = BUILDER.defineInRange("hunter-payout", 0.50, 0.0, 1_000_000.0);
        JOB_FISHER_PAYOUT = BUILDER.defineInRange("fisher-payout", 0.30, 0.0, 1_000_000.0);
        JOB_BUILDER_PAYOUT = BUILDER.defineInRange("builder-payout", 0.10, 0.0, 1_000_000.0);
        JOB_DAILY_CAP = BUILDER.defineInRange("daily-cap", 5_000.0, 0.0, 1.0e12);
        JOB_LEVEL_MULTIPLIER = BUILDER.defineInRange("multiplier-per-level", 0.05, 0.0, 1.0);
        JOB_XP_PER_ACTION = BUILDER.defineInRange("xp-per-action", 4, 1, 100_000);
        JOB_MAX_LEVEL = BUILDER.defineInRange("max-level", 50, 1, 1000);
        BUILDER.pop();
        BUILDER.push("quests");
        QUEST_ACTIONS_REQUIRED = BUILDER.defineInRange("actions-required", 20, 1, 100_000);
        QUEST_REWARD = BUILDER.defineInRange("reward", 250.0, 0.0, 1.0e12);
        BUILDER.pop();
        BUILDER.push("seasons");
        SEASON_DURATION_DAYS = BUILDER.defineInRange("duration-days", 28, 1, 3650);
        BUILDER.pop();
        BUILDER.push("web");
        WEB_ENABLED = BUILDER.define("enabled", false);
        WEB_HOST = BUILDER.define("host", "127.0.0.1");
        WEB_PORT = BUILDER.defineInRange("port", 8765, 1024, 65535);
        WEB_TOKEN = BUILDER.define("token", "");
        BUILDER.pop();
        BUILDER.push("market");
        MARKET_CATALOGUE = BUILDER.comment("Allowed items and base prices in format namespace:item=price.")
                .defineListAllowEmpty("catalogue", java.util.List.of(
                        "minecraft:coal=1.00", "minecraft:iron_ingot=4.00", "minecraft:gold_ingot=8.00",
                        "minecraft:diamond=40.00", "minecraft:emerald=25.00", "minecraft:wheat=0.50",
                        "minecraft:carrot=0.40", "minecraft:raw_cod=1.00", "minecraft:rotten_flesh=0.15"),
                        value -> value instanceof String text && text.matches("[a-z0-9_.-]+:[a-z0-9_./-]+=([0-9]+)(\\.[0-9]{1,2})?"));
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private ForgeConfig() {
    }
}
