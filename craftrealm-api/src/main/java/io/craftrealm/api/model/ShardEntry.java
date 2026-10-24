package io.craftrealm.api.model;

import java.util.UUID;

/** A single row of a Shard leaderboard. */
public record ShardEntry(UUID uuid, String name, long shards) {
}
