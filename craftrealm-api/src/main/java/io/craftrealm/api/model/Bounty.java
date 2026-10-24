package io.craftrealm.api.model;

import java.util.UUID;

/**
 * A bounty placed on a player's head. The reward is escrowed at placement time,
 * so it can never be paid twice or vanish when the placer logs off.
 */
public record Bounty(long id, UUID target, String targetName, UUID placer, double amount,
                     long createdAt, BountyStatus status) {
}
