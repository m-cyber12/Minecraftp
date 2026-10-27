package io.craftrealm.forge.jobs;

import io.craftrealm.forge.config.ForgeConfig;
import io.craftrealm.forge.discord.ForgeDiscordBridge;
import io.craftrealm.forge.events.ForgeEventService;
import io.craftrealm.forge.storage.ForgeDatabase;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.CropBlock;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.ItemFishedEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Pays selected professions for simple, verifiable survival actions. */
public final class ForgeJobListener {

    private static final int MAX_PLACED = 20_000;
    private static final long PLACED_TTL_MILLIS = 5 * 60_000L;

    private final Supplier<ForgeDatabase> database;
    private final ForgeEventService events;
    private final Supplier<ForgeDiscordBridge> discord;
    private final Logger logger;
    private final Map<String, Long> playerPlacedBlocks = new ConcurrentHashMap<>();

    public ForgeJobListener(Supplier<ForgeDatabase> database, ForgeEventService events,
                            Supplier<ForgeDiscordBridge> discord, Logger logger) {
        this.database = database;
        this.events = events;
        this.discord = discord;
        this.logger = logger;
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getPlayer() instanceof ServerPlayer player)
                || player.getAbilities().instabuild) return;
        String key = key(player, event.getPos().getX(), event.getPos().getY(), event.getPos().getZ());
        Long placedAt = playerPlacedBlocks.remove(key);
        if (placedAt != null && System.currentTimeMillis() - placedAt < PLACED_TTL_MILLIS) return;

        if (event.getState().getBlock() instanceof CropBlock crop && crop.isMaxAge(event.getState())) {
            reward(player, "FARMER", ForgeConfig.JOB_FARMER_PAYOUT.get());
            return;
        }
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(event.getState().getBlock()).getPath();
        if (path.endsWith("_ore") || path.equals("ancient_debris")) {
            reward(player, "MINER", ForgeConfig.JOB_MINER_PAYOUT.get());
        }
    }

    @SubscribeEvent
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)
                || player.getAbilities().instabuild) return;
        if (playerPlacedBlocks.size() > MAX_PLACED) {
            long cutoff = System.currentTimeMillis() - PLACED_TTL_MILLIS;
            playerPlacedBlocks.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
        playerPlacedBlocks.put(key(player, event.getPos().getX(), event.getPos().getY(), event.getPos().getZ()),
                System.currentTimeMillis());
        reward(player, "BUILDER", ForgeConfig.JOB_BUILDER_PAYOUT.get());
    }

    @SubscribeEvent
    public void onMobKilled(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)
                || event.getEntity().getType().getCategory() != MobCategory.MONSTER) return;
        reward(player, "HUNTER", ForgeConfig.JOB_HUNTER_PAYOUT.get());
    }

    @SubscribeEvent
    public void onFish(ItemFishedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            reward(player, "FISHER", ForgeConfig.JOB_FISHER_PAYOUT.get());
        }
    }

    public void close() {
        MinecraftForge.EVENT_BUS.unregister(this);
        playerPlacedBlocks.clear();
    }

    private void reward(ServerPlayer player, String job, double basePayout) {
        ForgeDatabase store = database.get();
        if (store == null || basePayout <= 0) return;
        String name = player.getGameProfile().getName();
        java.util.UUID playerId = player.getUUID();
        MinecraftServer server = player.getServer();
        long baseCents = Math.round(basePayout * events.rewardMultiplier(job) * 100.0);
        store.awardJob(playerId, name, job, baseCents,
                        Math.round(ForgeConfig.JOB_DAILY_CAP.get() * 100.0),
                        ForgeConfig.JOB_LEVEL_MULTIPLIER.get(), ForgeConfig.JOB_XP_PER_ACTION.get(),
                        ForgeConfig.JOB_MAX_LEVEL.get(), seasonKey(),
                        Math.round(ForgeConfig.STARTING_BALANCE.get() * 100.0),
                        Math.round(ForgeConfig.MAX_BALANCE.get() * 100.0))
                .whenComplete((award, failure) -> {
                    if (failure != null) {
                        logger.error("Could not award {} job progress to {}", job, name, failure);
                        return;
                    }
                    if (!award.success() || server == null) return;
                    server.execute(() -> {
                        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                        if (online == null) return;
                        online.displayClientMessage(net.minecraft.network.chat.Component.translatable("craftrealm.jobs.reward",
                                money(award.amountCents()), job.toLowerCase(), award.level(), award.xp()), true);
                        if (award.levelUp()) {
                            online.sendSystemMessage(net.minecraft.network.chat.Component.translatable("craftrealm.jobs.level_up",
                                    job.toLowerCase(), award.level()));
                            ForgeDiscordBridge bridge = discord.get();
                            if (bridge != null) bridge.send(name + " reached " + job.toLowerCase()
                                    + " profession level " + award.level() + ".");
                        }
                    });
                });
    }

    private static long seasonKey() {
        long day = Math.floorDiv(System.currentTimeMillis(), 86_400_000L);
        return Math.floorDiv(day, ForgeConfig.SEASON_DURATION_DAYS.get());
    }

    private static String key(ServerPlayer player, int x, int y, int z) {
        return player.level().dimension().location() + ":" + x + ":" + y + ":" + z;
    }

    private static String money(long cents) {
        return ForgeConfig.CURRENCY_SYMBOL.get() + String.format(java.util.Locale.ROOT, "%.2f", cents / 100.0);
    }
}
