package io.craftrealm.events.listener;

import io.craftrealm.api.model.CosmeticType;
import io.craftrealm.api.service.CosmeticService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;

/**
 * Applies cosmetics: chat prefix, name colour and particle trails.
 *
 * <p>Chat decoration uses the renderer, so it composes with other plugins' formats
 * instead of overwriting them. Player names are inserted as plain text components,
 * never parsed as MiniMessage — a name is user data, not markup.</p>
 */
public final class CosmeticListener implements Listener {

    private final CosmeticService cosmetics;
    private final MiniMessage mini = MiniMessage.miniMessage();

    public CosmeticListener(CosmeticService cosmetics) {
        this.cosmetics = cosmetics;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String prefix = decoration(player.getUniqueId(), CosmeticType.PREFIX);
        String colour = decoration(player.getUniqueId(), CosmeticType.NAME_COLOR);
        boolean hasPrefix = prefix != null && !prefix.isBlank();
        boolean hasColour = colour != null && !colour.isBlank();
        if (!hasPrefix && !hasColour) {
            return;
        }

        Component prefixComponent = hasPrefix ? mini.deserialize(prefix) : Component.empty();
        Component name = hasColour
                ? mini.deserialize("<" + colour + ">").append(Component.text(player.getName()))
                : Component.text(player.getName());

        event.renderer((source, sourceDisplayName, message, audience) -> Component.empty()
                .append(prefixComponent)
                .append(name)
                .append(Component.text(" \u00BB "))
                .append(message));
    }

    /**
     * Resolves the equipped cosmetic of a type into its raw payload.
     *
     * <p>{@code equippedCached} is fed by {@link CosmeticService#load(UUID)} on join, so
     * decorating a chat line costs two map lookups and never touches the database.</p>
     */
    private String decoration(java.util.UUID uuid, CosmeticType type) {
        String id = cosmetics.equippedCached(uuid, type);
        if (id == null || id.isBlank()) {
            return null;
        }
        var cosmetic = cosmetics.byId(id);
        return cosmetic == null ? null : cosmetic.value();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cosmetics.unload(event.getPlayer().getUniqueId());
    }

    /** Spawns trail particles; called from the module's repeating task. */
    public void tickTrails() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            String id = cosmetics.equippedCached(player.getUniqueId(), CosmeticType.PARTICLE_TRAIL);
            if (id == null) {
                continue;
            }
            var cosmetic = cosmetics.byId(id);
            if (cosmetic == null) {
                continue;
            }
            Particle particle = particleOf(cosmetic.value());
            if (particle == null) {
                continue;
            }
            player.getWorld().spawnParticle(particle, player.getLocation().add(0, 0.1, 0),
                    4, 0.2, 0.05, 0.2, 0.01);
        }
    }

    private static Particle particleOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
