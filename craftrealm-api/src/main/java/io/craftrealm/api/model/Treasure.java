package io.craftrealm.api.model;

import java.util.UUID;

/** An admin-hidden treasure chest with a cryptic clue and a Shard reward. */
public record Treasure(long id, String world, double x, double y, double z, String clue,
                       int shards, UUID foundBy, long foundAt) {

    public boolean found() {
        return foundBy != null;
    }
}
