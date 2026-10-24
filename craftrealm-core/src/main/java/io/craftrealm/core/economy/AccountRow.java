package io.craftrealm.core.economy;

import java.util.UUID;

/**
 * A raw account row: the wallet balance and the premium Shard balance live in
 * the same table so a single row lock protects both.
 */
public record AccountRow(UUID uuid, String name, double balance, long shards) {

    public AccountRow withName(String newName) {
        return new AccountRow(uuid, newName, balance, shards);
    }

    public AccountRow withBalance(double newBalance) {
        return new AccountRow(uuid, name, newBalance, shards);
    }
}
