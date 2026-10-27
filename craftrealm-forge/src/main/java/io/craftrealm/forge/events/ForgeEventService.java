package io.craftrealm.forge.events;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** One timed server-wide profession event at a time, controlled by operators. */
public final class ForgeEventService {
    private final AtomicReference<ActiveEvent> active = new AtomicReference<>();
    private long lastExpiryNotice;

    public ActiveEvent active() {
        ActiveEvent current = active.get();
        return current != null && current.endsAtMillis() > System.currentTimeMillis() ? current : null;
    }

    public double rewardMultiplier(String job) {
        ActiveEvent current = active();
        return current != null && (current.job().equals("ALL") || current.job().equalsIgnoreCase(job))
                ? current.multiplier() : 1.0;
    }

    public ActiveEvent start(MinecraftServer server, String job, int minutes, double multiplier) {
        long end = System.currentTimeMillis() + minutes * 60_000L;
        ActiveEvent next = new ActiveEvent(job.toUpperCase(Locale.ROOT), multiplier, end);
        active.set(next);
        server.getPlayerList().broadcastSystemMessage(Component.translatable("craftrealm.event.started", job.equalsIgnoreCase("ALL")
                ? "all professions" : job.toLowerCase(Locale.ROOT), multiplier, minutes), false);
        return next;
    }

    public boolean stop(MinecraftServer server) {
        ActiveEvent previous = active.getAndSet(null);
        if (previous == null) return false;
        server.getPlayerList().broadcastSystemMessage(Component.translatable("craftrealm.event.stopped", previous.job().equals("ALL")
                ? "profession" : previous.job().toLowerCase(Locale.ROOT)), false);
        return true;
    }

    /** Called from the server tick event; expiry notification is emitted once. */
    public void tick(MinecraftServer server, long now) {
        ActiveEvent current = active.get();
        if (current != null && current.endsAtMillis() <= now && active.compareAndSet(current, null)) {
            if (lastExpiryNotice != current.endsAtMillis()) {
                lastExpiryNotice = current.endsAtMillis();
                server.getPlayerList().broadcastSystemMessage(Component.translatable("craftrealm.event.expired"), false);
            }
        }
    }

    public record ActiveEvent(String job, double multiplier, long endsAtMillis) { }
}
