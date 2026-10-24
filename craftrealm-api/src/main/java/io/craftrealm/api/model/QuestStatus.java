package io.craftrealm.api.model;

/** Lifecycle of a quest assigned to a player. */
public enum QuestStatus {
    ACTIVE,
    /** Target reached, reward not yet collected. */
    COMPLETE,
    /** Reward collected. */
    CLAIMED,
    EXPIRED
}
