package io.craftrealm.forge;

import com.mojang.logging.LogUtils;
import io.craftrealm.forge.claims.ForgeClaims;
import io.craftrealm.forge.command.ForgeCommands;
import io.craftrealm.forge.config.ForgeConfig;
import io.craftrealm.forge.jobs.ForgeJobListener;
import io.craftrealm.forge.market.ForgeMarket;
import io.craftrealm.forge.storage.ForgeDatabase;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Path;

/** Native Forge 1.20.1 entry point for the CraftRealm mod. */
@Mod(CraftRealmForge.MOD_ID)
public final class CraftRealmForge {

    public static final String MOD_ID = "craftrealm";
    private static final Logger LOGGER = LogUtils.getLogger();

    private volatile ForgeDatabase database;
    private volatile ForgeClaims claims;
    private volatile ForgeJobListener jobListener;
    private volatile ForgeMarket market;
    private long lastMaintenanceAt;

    public CraftRealmForge() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ForgeConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("CraftRealm Forge {} initialized", MOD_ID);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ForgeCommands.register(event, () -> database, () -> claims, () -> market, LOGGER);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        Path databaseFile = FMLPaths.CONFIGDIR.get().resolve("craftrealm").resolve("craftrealm.db");
        ForgeDatabase created = new ForgeDatabase(databaseFile, ForgeConfig.DATABASE_THREADS.get(), LOGGER);
        try {
            created.open();
            ForgeClaims loadedClaims = new ForgeClaims(created, LOGGER);
            claims = loadedClaims;
            database = created;
            created.loadClaims().whenComplete((loaded, failure) -> event.getServer().execute(() -> {
                if (failure != null) {
                    LOGGER.error("Could not load land claims; protection remains fail-closed", failure);
                } else if (claims == loadedClaims) {
                    loadedClaims.load(loaded);
                }
            }));
            jobListener = new ForgeJobListener(() -> database, LOGGER);
            market = new ForgeMarket(created, LOGGER);
            lastMaintenanceAt = System.currentTimeMillis() - 60_000L;
            LOGGER.info("CraftRealm Forge services started for '{}'", event.getServer().getWorldData().getLevelName());
        } catch (Exception failure) {
            created.close();
            throw new IllegalStateException("CraftRealm could not open its economy database", failure);
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            ForgeDatabase current = database;
            if (current != null) {
                String playerName = player.getGameProfile().getName();
                current.balance(player.getUUID(), playerName,
                                Math.round(ForgeConfig.STARTING_BALANCE.get() * 100.0))
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                LOGGER.error("Could not initialize account for {}", playerName, failure);
                            }
                        });
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastMaintenanceAt < 60_000L) {
            return;
        }
        lastMaintenanceAt = now;
        ForgeDatabase current = database;
        if (current != null) {
            current.processInterest(now, 86_400_000L, ForgeConfig.BANK_DAILY_INTEREST_PERCENT.get(),
                            Math.round(ForgeConfig.MAX_BALANCE.get() * 100.0))
                    .exceptionally(failure -> {
                        LOGGER.error("Bank interest cycle failed", failure);
                        return 0;
                    });
            current.processOverdueLoans(now - 2 * 86_400_000L,
                            ForgeConfig.BANK_LOAN_PENALTY_PERCENT.get())
                    .exceptionally(failure -> {
                        LOGGER.error("Loan overdue cycle failed", failure);
                        return 0;
                    });
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        market = null;
        ForgeClaims currentClaims = claims;
        claims = null;
        if (currentClaims != null) currentClaims.close();
        ForgeJobListener currentJobs = jobListener;
        jobListener = null;
        if (currentJobs != null) currentJobs.close();
        ForgeDatabase current = database;
        database = null;
        if (current != null) {
            current.close();
            LOGGER.info("CraftRealm Forge storage closed");
        }
    }
}
