package io.craftrealm.api.model;

import java.util.UUID;

/**
 * A physical, uniquely identified banknote.
 *
 * @param id      unguessable id embedded in the item's persistent data
 * @param issuer  who withdrew it
 * @param amount  face value in Crowns
 * @param createdAt epoch millis
 */
public record Note(String id, UUID issuer, double amount, long createdAt) {
}
