package io.craftrealm.core.config;

import io.craftrealm.api.model.Cosmetic;
import io.craftrealm.api.model.PunishmentType;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The immutable configuration snapshot.
 *
 * <p>Built once per load by {@code ConfigLoader} and handed to every service
 * through its constructor. Nothing reads {@code FileConfiguration} at runtime, so
 * reloading is a single atomic swap and there is no risk of half-applied config
 * being observed mid-tick.</p>
 */
public record Settings(
        int configVersion,
        Storage storage,
        EconomySettings economy,
        BankSettings bank,
        MarketSettings market,
        MarketCatalogue catalogue,
        AuctionSettings auction,
        JobSettings jobs,
        ClaimSettings claims,
        ChestShopSettings chestShops,
        WebSettings web,
        DiscordSettings discord,
        ThemeSettings theme,
        StaffSettings staff,
        AdminSettings admin,
        EventSettings events,
        QuestSettings quests,
        SeasonSettings seasons,
        CosmeticSettings cosmetics,
        BountySettings bounties,
        StreakSettings streaks,
        TreasureSettings treasures,
        MessageSettings messages
) {

    // ------------------------------------------------------------------ storage

    /** Storage backend configuration. */
    public record Storage(String type, String file, String host, int port, String database,
                          String username, String password, String properties, int poolSize,
                          int minIdle, int threads, String serverId) {

        public boolean mysql() {
            return "mysql".equalsIgnoreCase(type);
        }
    }

    // ------------------------------------------------------------------- market

    /**
     * Which materials the market trades and how they are grouped in {@code /shop}.
     *
     * @param basePrices anchor price per material
     * @param blacklist  materials that can never be sold or bought
     * @param categories ordered GUI categories
     */
    public record MarketCatalogue(Map<Material, Double> basePrices, Set<Material> blacklist,
                                  Map<String, List<Material>> categories) {
    }

    // -------------------------------------------------------------------- shops

    /** Player-run chest shops. */
    public record ChestShopSettings(boolean enabled, double taxPercent, int maxPerPlayer,
                                    boolean holograms, boolean requireSign) {
    }

    // -------------------------------------------------------------------- theme

    /**
     * Visual theme switches.
     *
     * @param sounds                  play GUI sounds
     * @param customModelData         attach custom model data to GUI icons
     * @param customModelDataOffset   base value; each icon gets offset + its own index
     * @param palette                 named colours, e.g. {@code gold -> #e8b339}
     */
    public record ThemeSettings(boolean sounds, boolean customModelData, int customModelDataOffset,
                                Map<String, String> palette) {
    }

    // -------------------------------------------------------------------- staff

    /** A permission tier (Helper/Mod/Admin/Owner). */
    public record StaffTier(String id, String permission, int weight) {
    }

    /** One rung of the warning escalation ladder. */
    public record EscalationRule(int warnings, PunishmentType action, long durationMillis) {
    }

    /** Tiers and the auto-escalation ladder. */
    public record StaffSettings(List<StaffTier> tiers, List<EscalationRule> escalation, int tierWeights) {
    }

    // -------------------------------------------------------------------- admin

    /** Control room / health / reports tuning. */
    public record AdminSettings(int lagEntityThreshold, int lagScanRadiusChunks, int maxCleanupChunks,
                                int reportCooldownSeconds, long reportAutoCloseDays,
                                boolean freezeBlocksCommands, String defaultBanReason) {
    }

    // ------------------------------------------------------------------- events

    /** Random world events. */
    public record EventSettings(boolean enabled, boolean autoStart, long checkIntervalMinutes,
                                int minGapMinutes, long durationMinutes, List<String> pool,
                                double goldRushMultiplier, Set<Material> goldRushMaterials,
                                double bloodMoonMultiplier, double marketCrashMultiplier,
                                int caravanRareItems, boolean announce) {
    }

    // ------------------------------------------------------------------- quests

    /** A configurable quest template. */
    public record QuestTemplate(String id, String kind, String scope, Material material,
                                double target, double reward, String currency) {

        public boolean shardReward() {
            return "SHARDS".equalsIgnoreCase(currency);
        }
    }

    /** Daily/weekly quest generation. */
    public record QuestSettings(boolean enabled, int dailyCount, int weeklyCount,
                                List<QuestTemplate> templates) {
    }

    // ------------------------------------------------------------------ seasons

    /** Reward for a finishing position. */
    public record SeasonReward(int rank, int shards, double crowns, String title) {
    }

    /** Season length and payouts. */
    public record SeasonSettings(boolean enabled, int lengthDays, List<SeasonReward> rewards) {
    }

    // ---------------------------------------------------------------- cosmetics

    /** Cosmetic catalogue. */
    public record CosmeticSettings(boolean enabled, List<Cosmetic> catalogue) {
    }

    // ----------------------------------------------------------------- bounties

    /** Bounty board limits. */
    public record BountySettings(boolean enabled, double minimum, double maximum,
                                 double feePercent, boolean anonymous) {
    }

    // ------------------------------------------------------------------ streaks

    /** Daily login streak rewards. */
    public record StreakSettings(boolean enabled, double baseReward, double growthPercent,
                                 int maxStreak, int shardEveryDays, int shardsPerMilestone) {
    }

    // ----------------------------------------------------------------- treasure

    /** Treasure hunt defaults. */
    public record TreasureSettings(boolean enabled, int defaultShards, Material chestBlock,
                                   boolean announce) {
    }

    // ----------------------------------------------------------------- messages

    /** Localisation. */
    public record MessageSettings(String locale, String fallback, String prefix) {
    }
}
