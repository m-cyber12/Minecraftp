package io.craftrealm.forge;

import com.mojang.logging.LogUtils;
import io.craftrealm.forge.claims.ForgeClaims;
import io.craftrealm.forge.command.ForgeCommands;
import io.craftrealm.forge.config.ForgeConfig;
import io.craftrealm.forge.cosmetics.ForgeCosmetics;
import io.craftrealm.forge.api.ForgeEconomyAPI;
import io.craftrealm.forge.discord.ForgeDiscordBridge;
import io.craftrealm.forge.events.ForgeEventService;
import io.craftrealm.forge.gui.ForgeMenuService;
import io.craftrealm.forge.jobs.ForgeJobListener;
import io.craftrealm.forge.market.ForgeMarket;
import io.craftrealm.forge.storage.ForgeDatabase;
import io.craftrealm.forge.web.ForgeWebPanel;
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
    private final ForgeEventService events = new ForgeEventService();
    private final ForgeMenuService menus = new ForgeMenuService();
    private volatile ForgeJobListener jobListener;
    private volatile ForgeMarket market;
    private volatile ForgeCosmetics cosmetics;
    private volatile ForgeWebPanel webPanel;
    private volatile ForgeDiscordBridge discord;
    private volatile ForgeEconomyAPI economyApi;
    private long lastMaintenanceAt;

    public CraftRealmForge() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ForgeConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("CraftRealm Forge {} initialized", MOD_ID);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ForgeCommands.register(event, () -> database, () -> claims, () -> market,
                () -> events, () -> cosmetics, () -> discord, () -> menus, LOGGER);
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
            if (ForgeConfig.DISCORD_ENABLED.get()) {
                discord = new ForgeDiscordBridge(true, ForgeConfig.DISCORD_WEBHOOK_URL.get(), LOGGER);
            }
            jobListener = new ForgeJobListener(() -> database, events, () -> discord, LOGGER);
            market = new ForgeMarket(created, LOGGER);
            economyApi = new ForgeEconomyAPI(() -> database, () -> market, event::getServer);
            ForgeEconomyAPI.install(economyApi);
            cosmetics = new ForgeCosmetics(() -> database);
            cosmetics.load();
            if (ForgeConfig.WEB_ENABLED.get()) {
                ForgeWebPanel panel = new ForgeWebPanel(() -> database, ForgeConfig.WEB_TOKEN.get(), LOGGER);
                try {
                    if (panel.start(ForgeConfig.WEB_HOST.get(), ForgeConfig.WEB_PORT.get())) webPanel = panel;
                    else panel.close();
                } catch (java.io.IOException webFailure) {
                    panel.close();
                    LOGGER.error("Could not start the CraftRealm web dashboard", webFailure);
                }
            }
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
                ForgeCosmetics currentCosmetics = cosmetics;
                if (currentCosmetics != null) currentCosmetics.loadPlayer(player.getUUID());
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
        events.tick(event.getServer(), now);
        ForgeCosmetics currentCosmetics = cosmetics;
        if (currentCosmetics != null) currentCosmetics.tick(event.getServer());
        ForgeWebPanel currentWeb = webPanel;
        if (currentWeb != null) currentWeb.updateStatus(
                event.getServer().getWorldData().getLevelName(),
                event.getServer().getPlayerList().getPlayers().size());
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
        ForgeWebPanel currentWeb = webPanel;
        webPanel = null;
        if (currentWeb != null) currentWeb.close();
        ForgeDiscordBridge currentDiscord = discord;
        discord = null;
        if (currentDiscord != null) currentDiscord.close();
        ForgeEconomyAPI currentApi = economyApi;
        economyApi = null;
        if (currentApi != null) ForgeEconomyAPI.uninstall(currentApi);
        market = null;
        ForgeCosmetics currentCosmetics = cosmetics;
        cosmetics = null;
        if (currentCosmetics != null) currentCosmetics.clear();
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
