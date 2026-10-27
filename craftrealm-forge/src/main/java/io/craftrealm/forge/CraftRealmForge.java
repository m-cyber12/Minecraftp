package io.craftrealm.forge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/** Forge 1.20.1 entry point for the native CraftRealm mod. */
@Mod(CraftRealmForge.MOD_ID)
public final class CraftRealmForge {

    public static final String MOD_ID = "craftrealm";
    private static final Logger LOGGER = LogUtils.getLogger();

    public CraftRealmForge() {
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("CraftRealm Forge {} initialized", MOD_ID);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("CraftRealm Forge services are attaching to world '{}'", event.getServer().getWorldData().getLevelName());
    }
}
