package io.craftrealm.api.event;

import io.craftrealm.api.model.Quest;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired when a quest reward is collected. */
public class QuestCompletedEvent extends CraftRealmEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Quest quest;

    public QuestCompletedEvent(@NotNull Quest quest) {
        this.quest = quest;
    }

    public @NotNull Quest quest() {
        return quest;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
