package io.craftrealm.api.model;

import java.util.UUID;

/** A single row of a balance leaderboard. */
public record BalanceEntry(UUID uuid, String name, double balance) {
}
