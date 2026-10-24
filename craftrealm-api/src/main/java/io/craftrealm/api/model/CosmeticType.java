package io.craftrealm.api.model;

/** Categories of cosmetic unlock. One of each can be equipped at a time. */
public enum CosmeticType {
    /** MiniMessage snippet shown before the chat name. */
    PREFIX,
    /** Chat name colour, e.g. {@code #c77b53}. */
    NAME_COLOR,
    /** Particle trail id, e.g. {@code embers}. */
    PARTICLE_TRAIL
}
