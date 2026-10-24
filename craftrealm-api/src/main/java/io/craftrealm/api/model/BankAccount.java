package io.craftrealm.api.model;

import java.util.UUID;

/**
 * A player's savings account.
 *
 * @param owner          account owner
 * @param balance        Crowns on deposit
 * @param lastInterestAt epoch millis of the last interest credit
 * @param createdAt      epoch millis
 */
public record BankAccount(UUID owner, double balance, long lastInterestAt, long createdAt) {

    public static BankAccount empty(UUID owner) {
        return new BankAccount(owner, 0.0, 0L, System.currentTimeMillis());
    }
}
