package io.craftrealm.api.service;

import io.craftrealm.api.model.Cosmetic;
import io.craftrealm.api.model.CosmeticType;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Titles, chat prefixes, name colours and particle trails bought with Shards.
 */
public interface CosmeticService {

    List<Cosmetic> catalogue();

    List<Cosmetic> catalogue(CosmeticType type);

    Cosmetic byId(String id);

    CompletableFuture<Set<String>> owned(UUID uuid);

    /** Buys with Shards; fails when already owned or too expensive. */
    CompletableFuture<Boolean> purchase(UUID uuid, String id);

    /** Equips an owned cosmetic, replacing whatever was equipped in that slot. */
    CompletableFuture<Boolean> equip(UUID uuid, String id);

    CompletableFuture<Boolean> unequip(UUID uuid, CosmeticType type);

    CompletableFuture<String> equipped(UUID uuid, CosmeticType type);

    /** Cache-backed accessor for the chat listener. */
    String equippedCached(UUID uuid, CosmeticType type);

    void load(UUID uuid);

    void unload(UUID uuid);
}
