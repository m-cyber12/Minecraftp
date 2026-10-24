package io.craftrealm.core.config;

import io.craftrealm.api.model.Cosmetic;
import io.craftrealm.api.model.CosmeticType;
import io.craftrealm.api.model.JobType;
import io.craftrealm.api.model.PunishmentType;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns {@code config.yml} into an immutable {@link Settings} snapshot.
 *
 * <p>Every getter supplies the documented default, so a config file from an older
 * version keeps working after an update — a missing key degrades to the shipped
 * behaviour instead of throwing.</p>
 */
public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static Settings load(FileConfiguration config) {
        Settings.Storage storage = storage(config);
        EconomySettings economy = economy(config);
        BankSettings bank = bank(config);
        MarketSettings market = market(config);
        Settings.MarketCatalogue catalogue = catalogue(config);
        AuctionSettings auction = auction(config);
        JobSettings jobs = jobs(config);
        ClaimSettings claims = claims(config);
        Settings.ChestShopSettings chestShops = chestShops(config);
        WebSettings web = web(config);
        DiscordSettings discord = discord(config);
        Settings.ThemeSettings theme = theme(config);
        Settings.StaffSettings staff = staff(config);
        Settings.AdminSettings admin = admin(config);
        Settings.EventSettings events = events(config);
        Settings.QuestSettings quests = quests(config);
        Settings.SeasonSettings seasons = seasons(config);
        Settings.CosmeticSettings cosmetics = cosmetics(config);
        Settings.BountySettings bounties = bounties(config);
        Settings.StreakSettings streaks = streaks(config);
        Settings.TreasureSettings treasures = treasures(config);
        Settings.MessageSettings messages = messages(config);

        return new Settings(config.getInt("config-version", 1), storage, economy, bank, market, catalogue,
                auction, jobs, claims, chestShops, web, discord, theme, staff, admin, events, quests,
                seasons, cosmetics, bounties, streaks, treasures, messages);
    }

    // ------------------------------------------------------------------ storage

    private static Settings.Storage storage(FileConfiguration config) {
        return new Settings.Storage(
                config.getString("storage.type", "sqlite"),
                config.getString("storage.file", "data/craftrealm.db"),
                config.getString("storage.mysql.host", "127.0.0.1"),
                config.getInt("storage.mysql.port", 3306),
                config.getString("storage.mysql.database", "craftrealm"),
                config.getString("storage.mysql.username", "craftrealm"),
                config.getString("storage.mysql.password", ""),
                config.getString("storage.mysql.properties", "useSSL=false&characterEncoding=utf8"),
                config.getInt("storage.pool.maximum-size", 8),
                config.getInt("storage.pool.minimum-idle", 2),
                config.getInt("storage.threads", 4),
                config.getString("storage.server-id", "local"));
    }

    // ------------------------------------------------------------------ economy

    private static EconomySettings economy(FileConfiguration config) {
        return new EconomySettings(
                config.getString("economy.currency.name", "Crowns"),
                config.getString("economy.currency.symbol", "⛃"),
                config.getInt("economy.currency.decimals", 2),
                config.getDouble("economy.currency.starting-balance", 100.0),
                config.getDouble("economy.limits.max-transaction", 1_000_000_000.0),
                config.getDouble("economy.limits.min-pay", 0.01),
                config.getInt("economy.limits.transactions-per-second", 20),
                config.getDouble("economy.alerts.large-transaction", 100_000.0),
                config.getString("storage.server-id", "local"));
    }

    private static BankSettings bank(FileConfiguration config) {
        return new BankSettings(
                config.getDouble("bank.interest.rate-percent", 1.5),
                config.getLong("bank.interest.period-hours", 24L) * 3_600_000L,
                config.getDouble("bank.interest.max-balance", 1_000_000.0),
                config.getBoolean("bank.loans.enabled", true),
                config.getDouble("bank.loans.max-principal", 25_000.0),
                config.getDouble("bank.loans.limit-multiplier", 2.0),
                config.getDouble("bank.loans.daily-rate-percent", 2.0),
                config.getInt("bank.loans.term-days", 7),
                config.getDouble("bank.loans.penalty-percent", 10.0),
                config.getInt("bank.loans.default-after-days", 3));
    }

    // ------------------------------------------------------------------- market

    private static MarketSettings market(FileConfiguration config) {
        return new MarketSettings(
                config.getDouble("market.buy-multiplier", 1.25),
                config.getDouble("market.elasticity", 0.02),
                config.getInt("market.reference-volume", 64),
                config.getDouble("market.min-factor", 0.35),
                config.getDouble("market.max-factor", 3.0),
                config.getDouble("market.recovery-per-hour", 0.08),
                config.getDouble("market.trend-threshold", 0.03));
    }

    private static Settings.MarketCatalogue catalogue(FileConfiguration config) {
        Map<Material, Double> basePrices = new LinkedHashMap<>();
        ConfigurationSection prices = config.getConfigurationSection("market.base-prices");
        if (prices != null) {
            for (String key : prices.getKeys(false)) {
                Material material = material(key);
                if (material != null) {
                    basePrices.put(material, prices.getDouble(key, 1.0));
                }
            }
        }

        Set<Material> blacklist = new LinkedHashSet<>();
        for (String raw : config.getStringList("market.blacklist")) {
            Material material = material(raw);
            if (material != null) {
                blacklist.add(material);
            }
        }

        Map<String, List<Material>> categories = new LinkedHashMap<>();
        ConfigurationSection categorySection = config.getConfigurationSection("market.categories");
        if (categorySection != null) {
            for (String key : categorySection.getKeys(false)) {
                List<Material> materials = new ArrayList<>();
                for (String raw : categorySection.getStringList(key)) {
                    Material material = material(raw);
                    if (material != null && basePrices.containsKey(material)) {
                        materials.add(material);
                    }
                }
                if (!materials.isEmpty()) {
                    categories.put(key, materials);
                }
            }
        }
        return new Settings.MarketCatalogue(basePrices, blacklist, categories);
    }

    // ------------------------------------------------------------------ auction

    private static AuctionSettings auction(FileConfiguration config) {
        return new AuctionSettings(
                config.getDouble("auction.listing-fee-percent", 5.0),
                config.getDouble("auction.listing-fee-min", 5.0),
                config.getInt("auction.max-listings", 8),
                config.getDouble("auction.min-price", 1.0),
                config.getDouble("auction.max-price", 5_000_000.0),
                config.getLong("auction.default-duration-hours", 48L) * 3_600_000L,
                config.getLong("auction.max-duration-hours", 72L) * 3_600_000L,
                config.getDouble("auction.min-bid-increment-percent", 5.0),
                config.getDouble("auction.tax-percent", 2.0));
    }

    // --------------------------------------------------------------------- jobs

    private static JobSettings jobs(FileConfiguration config) {
        Map<JobType, Map<Material, Double>> payouts = new EnumMap<>(JobType.class);
        Map<org.bukkit.entity.EntityType, Double> hunterPayouts = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("jobs.payouts");
        if (section != null) {
            for (String jobKey : section.getKeys(false)) {
                JobType job = JobType.parse(jobKey);
                if (job == null) {
                    continue;
                }
                ConfigurationSection table = section.getConfigurationSection(jobKey);
                if (table == null) {
                    continue;
                }
                Map<Material, Double> amounts = new LinkedHashMap<>();
                for (String materialKey : table.getKeys(false)) {
                    Material material = material(materialKey);
                    if (material != null) {
                        amounts.put(material, table.getDouble(materialKey, 0.0));
                        continue;
                    }
                    // Hunter payouts are keyed by entity type, not by material.
                    try {
                        hunterPayouts.put(org.bukkit.entity.EntityType.valueOf(
                                materialKey.toUpperCase(Locale.ROOT)), table.getDouble(materialKey, 0.0));
                    } catch (IllegalArgumentException ignored) {
                        io.craftrealm.core.CraftRealmCore.logger()
                                .warning("Unknown job payout key: " + materialKey);
                    }
                }
                payouts.put(job, amounts);
            }
        }
        return new JobSettings(
                config.getInt("jobs.max-level", 50),
                config.getDouble("jobs.multiplier-per-level", 0.05),
                config.getLong("jobs.xp-base", 100L),
                config.getDouble("jobs.xp-growth", 1.15),
                config.getDouble("jobs.daily-cap", 5_000.0),
                config.getDouble("jobs.xp-per-crown", 4.0),
                payouts,
                hunterPayouts);
    }

    // ------------------------------------------------------------------- claims

    private static ClaimSettings claims(FileConfiguration config) {
        return new ClaimSettings(
                config.getBoolean("claims.enabled", true),
                config.getDouble("claims.price-per-block", 0.5),
                config.getDouble("claims.upkeep-per-block-per-day", 0.02),
                config.getInt("claims.min-size", 16),
                config.getInt("claims.max-size", 64),
                config.getInt("claims.max-claims", 3),
                config.getInt("claims.grace-days", 3));
    }

    private static Settings.ChestShopSettings chestShops(FileConfiguration config) {
        return new Settings.ChestShopSettings(
                config.getBoolean("chest-shops.enabled", true),
                config.getDouble("chest-shops.tax-percent", 2.0),
                config.getInt("chest-shops.max-per-player", 10),
                config.getBoolean("chest-shops.holograms", true),
                config.getBoolean("chest-shops.require-sign", true));
    }

    // ---------------------------------------------------------------- web/discord

    private static WebSettings web(FileConfiguration config) {
        return new WebSettings(
                config.getBoolean("web.enabled", false),
                config.getString("web.host", "0.0.0.0"),
                config.getInt("web.port", 8080),
                config.getStringList("web.tokens"),
                config.getInt("web.session-minutes", 720),
                config.getBoolean("web.allow-console", true),
                config.getInt("web.max-console-lines", 200));
    }

    private static DiscordSettings discord(FileConfiguration config) {
        return new DiscordSettings(
                config.getBoolean("discord.enabled", false),
                config.getString("discord.webhook-url", ""),
                config.getBoolean("discord.notify.bans", true),
                config.getBoolean("discord.notify.reports", true),
                config.getBoolean("discord.notify.large-transactions", true),
                config.getDouble("discord.large-transaction-threshold", 100_000.0),
                config.getBoolean("discord.notify.events", true));
    }

    // -------------------------------------------------------------------- theme

    private static Settings.ThemeSettings theme(FileConfiguration config) {
        Map<String, String> palette = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("theme.palette");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                palette.put(key, section.getString(key, "#ffffff"));
            }
        }
        if (palette.isEmpty()) {
            palette.put("oak", "#b07d4f");
            palette.put("spruce", "#6b4a2f");
            palette.put("deepslate", "#36393d");
            palette.put("redstone", "#c0392b");
            palette.put("gold", "#e8b339");
            palette.put("copper", "#c77b53");
            palette.put("emerald", "#2fbf71");
            palette.put("lava", "#f26b21");
            palette.put("parchment", "#e8d8b0");
        }
        return new Settings.ThemeSettings(
                config.getBoolean("theme.sounds", true),
                config.getBoolean("theme.custom-model-data.enabled", false),
                config.getInt("theme.custom-model-data.offset", 10_000),
                palette);
    }

    // -------------------------------------------------------------------- staff

    private static Settings.StaffSettings staff(FileConfiguration config) {
        List<Settings.StaffTier> tiers = new ArrayList<>();
        ConfigurationSection tierSection = config.getConfigurationSection("staff.tiers");
        if (tierSection != null) {
            for (String key : tierSection.getKeys(false)) {
                tiers.add(new Settings.StaffTier(key,
                        tierSection.getString(key + ".permission", "craftrealm.tier." + key),
                        tierSection.getInt(key + ".weight", tiers.size() + 1)));
            }
        }
        if (tiers.isEmpty()) {
            tiers.add(new Settings.StaffTier("helper", "craftrealm.tier.helper", 1));
            tiers.add(new Settings.StaffTier("mod", "craftrealm.tier.mod", 2));
            tiers.add(new Settings.StaffTier("admin", "craftrealm.tier.admin", 3));
            tiers.add(new Settings.StaffTier("owner", "craftrealm.tier.owner", 4));
        }

        List<Settings.EscalationRule> escalation = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("staff.escalation")) {
            Object warnings = raw.get("warnings");
            Object action = raw.get("action");
            Object duration = raw.get("duration");
            if (warnings == null || action == null) {
                continue;
            }
            try {
                int count = Integer.parseInt(String.valueOf(warnings));
                PunishmentType type = PunishmentType.valueOf(String.valueOf(action).toUpperCase(Locale.ROOT));
                long millis = duration == null ? 0L
                        : io.craftrealm.core.util.TimeUtil.parseDuration(String.valueOf(duration));
                escalation.add(new Settings.EscalationRule(count, type, millis));
            } catch (IllegalArgumentException ignored) {
                io.craftrealm.core.CraftRealmCore.logger()
                        .warning("Ignoring malformed staff.escalation entry: " + raw);
            }
        }
        escalation.sort(java.util.Comparator.comparingInt(Settings.EscalationRule::warnings));
        return new Settings.StaffSettings(tiers, escalation, tiers.size());
    }

    private static Settings.AdminSettings admin(FileConfiguration config) {
        return new Settings.AdminSettings(
                config.getInt("admin.health.lag-entity-threshold", 150),
                config.getInt("admin.health.scan-radius-chunks", 3),
                config.getInt("admin.health.max-cleanup-chunks", 8),
                config.getInt("admin.reports.cooldown-seconds", 60),
                config.getLong("admin.reports.auto-close-days", 14L),
                config.getBoolean("admin.freeze.blocks-commands", true),
                config.getString("admin.ban.default-reason", "Removed from the realm"));
    }

    // ------------------------------------------------------------------- events

    private static Settings.EventSettings events(FileConfiguration config) {
        Set<Material> goldMaterials = new LinkedHashSet<>();
        for (String raw : config.getStringList("events.gold-rush.materials")) {
            Material material = material(raw);
            if (material != null) {
                goldMaterials.add(material);
            }
        }
        return new Settings.EventSettings(
                config.getBoolean("events.enabled", true),
                config.getBoolean("events.auto-start", true),
                config.getLong("events.check-interval-minutes", 30L),
                config.getInt("events.min-gap-minutes", 60),
                config.getLong("events.duration-minutes", 20L),
                config.getStringList("events.pool"),
                config.getDouble("events.gold-rush.multiplier", 2.0),
                goldMaterials,
                config.getDouble("events.blood-moon.multiplier", 2.0),
                config.getDouble("events.market-crash.multiplier", 0.6),
                config.getInt("events.merchant-caravan.rare-items", 4),
                config.getBoolean("events.announce", true));
    }

    // ------------------------------------------------------------------- quests

    private static Settings.QuestSettings quests(FileConfiguration config) {
        List<Settings.QuestTemplate> templates = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("quests.templates")) {
            String id = String.valueOf(value(raw, "id", ""));
            String kind = String.valueOf(value(raw, "kind", "MINE_BLOCK"));
            String scope = String.valueOf(value(raw, "scope", "DAILY")).toUpperCase(Locale.ROOT);
            String rawMaterial = raw.get("material") == null ? null : String.valueOf(raw.get("material"));
            double target = number(raw.get("target"), 64);
            double reward = number(raw.get("reward"), 100);
            String currency = String.valueOf(value(raw, "currency", "CROWNS"));
            if (id.isBlank()) {
                continue;
            }
            templates.add(new Settings.QuestTemplate(id, kind, scope,
                    rawMaterial == null ? null : material(rawMaterial), target, reward, currency));
        }
        return new Settings.QuestSettings(
                config.getBoolean("quests.enabled", true),
                config.getInt("quests.daily-count", 3),
                config.getInt("quests.weekly-count", 2),
                templates);
    }

    // ------------------------------------------------------------------ seasons

    private static Settings.SeasonSettings seasons(FileConfiguration config) {
        List<Settings.SeasonReward> rewards = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("seasons.rewards")) {
            rewards.add(new Settings.SeasonReward(
                    (int) number(raw.get("rank"), 1),
                    (int) number(raw.get("shards"), 10),
                    number(raw.get("crowns"), 0),
                    String.valueOf(value(raw, "title", ""))));
        }
        return new Settings.SeasonSettings(
                config.getBoolean("seasons.enabled", true),
                config.getInt("seasons.length-days", 30),
                rewards);
    }

    // ---------------------------------------------------------------- cosmetics

    private static Settings.CosmeticSettings cosmetics(FileConfiguration config) {
        List<Cosmetic> catalogue = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("cosmetics.catalogue")) {
            String id = String.valueOf(value(raw, "id", ""));
            String type = String.valueOf(value(raw, "type", "PREFIX")).toUpperCase(Locale.ROOT);
            String value = String.valueOf(value(raw, "value", ""));
            if (id.isBlank()) {
                continue;
            }
            try {
                catalogue.add(new Cosmetic(id, CosmeticType.valueOf(type), value,
                        (int) number(raw.get("cost"), 10),
                        String.valueOf(value(raw, "name", id))));
            } catch (IllegalArgumentException ignored) {
                io.craftrealm.core.CraftRealmCore.logger()
                        .warning("Ignoring cosmetic with unknown type '" + type + "': " + id);
            }
        }
        return new Settings.CosmeticSettings(config.getBoolean("cosmetics.enabled", true), catalogue);
    }

    // ----------------------------------------------------------------- bounties

    private static Settings.BountySettings bounties(FileConfiguration config) {
        return new Settings.BountySettings(
                config.getBoolean("bounties.enabled", true),
                config.getDouble("bounties.min", 100.0),
                config.getDouble("bounties.max", 100_000.0),
                config.getDouble("bounties.fee-percent", 5.0),
                config.getBoolean("bounties.anonymous", false));
    }

    private static Settings.StreakSettings streaks(FileConfiguration config) {
        return new Settings.StreakSettings(
                config.getBoolean("streaks.enabled", true),
                config.getDouble("streaks.base-reward", 50.0),
                config.getDouble("streaks.growth-percent", 15.0),
                config.getInt("streaks.max-streak", 30),
                config.getInt("streaks.shard-every-days", 7),
                config.getInt("streaks.shards-per-milestone", 2));
    }

    private static Settings.TreasureSettings treasures(FileConfiguration config) {
        Material chest = material(config.getString("treasure.chest-block", "CHEST"));
        return new Settings.TreasureSettings(
                config.getBoolean("treasure.enabled", true),
                config.getInt("treasure.default-shards", 3),
                chest == null ? Material.CHEST : chest,
                config.getBoolean("treasure.announce", true));
    }

    private static Settings.MessageSettings messages(FileConfiguration config) {
        return new Settings.MessageSettings(
                config.getString("messages.locale", "en_US"),
                config.getString("messages.fallback", "en_US"),
                config.getString("messages.prefix", "<gradient:#e8b339:#c77b53><bold>CraftRealm</bold></gradient> <dark_gray>»</dark_gray> "));
    }

    // ------------------------------------------------------------------ helpers

    private static Object value(Map<?, ?> values, String key, Object fallback) {
        Object value = values.get(key);
        return value == null ? fallback : value;
    }

    private static double number(Object raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** Lenient material lookup that logs unknown names instead of crashing enable. */
    public static Material material(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        if (material == null) {
            io.craftrealm.core.CraftRealmCore.logger().warning("Unknown material in config: " + raw);
        }
        return material;
    }
}
