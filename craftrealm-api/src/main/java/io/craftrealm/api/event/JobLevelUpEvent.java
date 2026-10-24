package io.craftrealm.api.event;

import io.craftrealm.api.model.JobType;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Fired when a player levels up in a job. */
public class JobLevelUpEvent extends CraftRealmEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final JobType job;
    private final int newLevel;

    public JobLevelUpEvent(@NotNull UUID player, @NotNull JobType job, int newLevel) {
        this.player = player;
        this.job = job;
        this.newLevel = newLevel;
    }

    public @NotNull UUID player() {
        return player;
    }

    public @NotNull JobType job() {
        return job;
    }

    public int newLevel() {
        return newLevel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
