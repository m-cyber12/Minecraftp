package io.craftrealm.forge.claims;

import io.craftrealm.forge.storage.ForgeDatabase;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Chunk claim cache and Forge event protection. */
public final class ForgeClaims {

    private final ForgeDatabase database;
    private final Logger logger;
    private final Map<Key, ForgeDatabase.Claim> claims = new ConcurrentHashMap<>();

    public ForgeClaims(ForgeDatabase database, Logger logger) {
        this.database = database;
        this.logger = logger;
        MinecraftForge.EVENT_BUS.register(this);
    }

    public void load(Collection<ForgeDatabase.Claim> loaded) {
        claims.clear();
        for (ForgeDatabase.Claim claim : loaded) {
            claims.put(new Key(claim.dimension(), claim.chunkX(), claim.chunkZ()), claim);
        }
        logger.info("Loaded {} Forge land claims", claims.size());
    }

    public ForgeDatabase.Claim get(String dimension, int chunkX, int chunkZ) {
        return claims.get(new Key(dimension, chunkX, chunkZ));
    }

    public void createAsync(ServerPlayer player, String dimension, int chunkX, int chunkZ,
                            long costCents, long openingBalanceCents, int maxClaims,
                            java.util.function.Consumer<ForgeDatabase.ClaimResult> completion) {
        UUID playerId = player.getUUID();
        String playerName = player.getGameProfile().getName();
        net.minecraft.server.MinecraftServer server = player.getServer();
        database.createClaim(playerId, playerName, dimension,
                chunkX, chunkZ, costCents, openingBalanceCents, maxClaims)
                .whenComplete((result, failure) -> server.execute(() -> {
                    if (failure != null) {
                        completion.accept(ForgeDatabase.ClaimResult.failed("storage-error"));
                    } else if (result.success()) {
                        claims.put(new Key(dimension, chunkX, chunkZ), result.claim());
                        completion.accept(result);
                    } else {
                        completion.accept(result);
                    }
                }));
    }

    public void removeAsync(ServerPlayer player, String dimension, int chunkX, int chunkZ,
                            java.util.function.Consumer<ForgeDatabase.ClaimResult> completion) {
        net.minecraft.server.MinecraftServer server = player.getServer();
        database.removeClaim(player.getUUID(), dimension, chunkX, chunkZ)
                .whenComplete((result, failure) -> server.execute(() -> {
                    if (failure != null) {
                        completion.accept(ForgeDatabase.ClaimResult.failed("storage-error"));
                    } else if (result.success()) {
                        claims.remove(new Key(dimension, chunkX, chunkZ));
                        completion.accept(result);
                    } else {
                        completion.accept(result);
                    }
                }));
    }

    public java.util.List<ForgeDatabase.Claim> ownedBy(UUID player) {
        return claims.values().stream().filter(claim -> claim.owner().equals(player)).toList();
    }

    public void close() {
        MinecraftForge.EVENT_BUS.unregister(this);
        claims.clear();
    }

    public boolean mayBuild(String dimension, BlockPos position, UUID player) {
        ForgeDatabase.Claim claim = get(dimension, position.getX() >> 4, position.getZ() >> 4);
        return claim == null || claim.owner().equals(player);
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide() || !(event.getPlayer() instanceof ServerPlayer player)) return;
        if (player.hasPermissions(2)) return;
        if (!mayBuild(player.level().dimension().location().toString(), event.getPos(), player.getUUID())) {
            event.setCanceled(true);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("This chunk is protected by another player."));
        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.hasPermissions(2)) return;
        if (!mayBuild(player.level().dimension().location().toString(), event.getPos(), player.getUUID())) {
            event.setCanceled(true);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("This chunk is protected by another player."));
        }
    }

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.hasPermissions(2)) return;
        if (!mayBuild(player.level().dimension().location().toString(), event.getPos(), player.getUUID())) {
            event.setCanceled(true);
        }
    }

    private record Key(String dimension, int chunkX, int chunkZ) { }
}
