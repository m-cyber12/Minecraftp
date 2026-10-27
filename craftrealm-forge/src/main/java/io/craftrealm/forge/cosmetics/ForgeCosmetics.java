package io.craftrealm.forge.cosmetics;

import io.craftrealm.forge.storage.ForgeDatabase;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Persistent, lightweight server-side particle trail cosmetics. */
public final class ForgeCosmetics {
    private static final Map<String, ParticleOptions> TRAILS = Map.of(
            "FLAME", ParticleTypes.FLAME,
            "HEART", ParticleTypes.HEART,
            "HAPPY_VILLAGER", ParticleTypes.HAPPY_VILLAGER,
            "PORTAL", ParticleTypes.PORTAL,
            "SOUL", ParticleTypes.SOUL
    );

    private final Supplier<ForgeDatabase> database;
    private final Map<UUID, String> equipped = new ConcurrentHashMap<>();

    public ForgeCosmetics(Supplier<ForgeDatabase> database) {
        this.database = database;
    }

    public static java.util.Set<String> availableTrails() {
        return TRAILS.keySet();
    }

    public void load() {
        ForgeDatabase store = database.get();
        if (store != null) store.loadCosmetics().thenAccept(saved ->
                saved.forEach(equipped::putIfAbsent));
    }

    public void loadPlayer(UUID player) {
        ForgeDatabase store = database.get();
        if (store == null) return;
        store.cosmetic(player).thenAccept(trail -> {
            if (trail == null || !TRAILS.containsKey(trail)) equipped.remove(player);
            else equipped.put(player, trail);
        });
    }

    public CompletableFuture<Void> equip(UUID player, String trail) {
        ForgeDatabase store = database.get();
        if (store == null) return CompletableFuture.failedFuture(new IllegalStateException("storage unavailable"));
        String normalized = trail == null ? "NONE" : trail.toUpperCase(java.util.Locale.ROOT);
        if (!normalized.equals("NONE") && !TRAILS.containsKey(normalized)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("unknown trail"));
        }
        return store.setCosmetic(player, normalized).thenRun(() -> {
            if (normalized.equals("NONE")) equipped.remove(player);
            else equipped.put(player, normalized);
        });
    }

    public String equipped(UUID player) {
        return equipped.getOrDefault(player, "NONE");
    }

    public void tick(MinecraftServer server) {
        if (server.getTickCount() % 10 != 0 || equipped.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String selected = equipped.get(player.getUUID());
            ParticleOptions particle = TRAILS.get(selected);
            if (particle == null || player.level().dimension() == Level.NETHER) continue;
            player.serverLevel().sendParticles(particle, player.getX(), player.getY() + 0.25, player.getZ(),
                    1, 0.12, 0.08, 0.12, 0.0);
        }
    }

    public void clear() {
        equipped.clear();
    }
}
