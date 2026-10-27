package io.craftrealm.forge;

import com.mojang.logging.LogUtils;
import io.craftrealm.forge.command.ForgeCommands;
import io.craftrealm.forge.config.ForgeConfig;
import io.craftrealm.forge.storage.ForgeDatabase;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
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

    public CraftRealmForge() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ForgeConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("CraftRealm Forge {} initialized", MOD_ID);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ForgeCommands.register(event, () -> database, LOGGER);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        Path databaseFile = FMLPaths.CONFIGDIR.get().resolve("craftrealm").resolve("craftrealm.db");
        ForgeDatabase created = new ForgeDatabase(databaseFile, ForgeConfig.DATABASE_THREADS.get(), LOGGER);
        try {
            created.open();
            database = created;
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
    public void onServerStopping(ServerStoppingEvent event) {
        ForgeDatabase current = database;
        database = null;
        if (current != null) {
            current.close();
            LOGGER.info("CraftRealm Forge storage closed");
        }
    }
}
