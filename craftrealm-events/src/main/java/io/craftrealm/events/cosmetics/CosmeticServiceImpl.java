package io.craftrealm.events.cosmetics;

import io.craftrealm.api.model.Cosmetic;
import io.craftrealm.api.model.CosmeticType;
import io.craftrealm.api.service.CosmeticService;
import io.craftrealm.api.service.ShardService;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.repo.ProgressionRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Titles, prefixes, name colours and particle trails, bought with Shards.
 *
 * <p>The equipped cosmetic of each type is cached because the chat listener needs it
 * synchronously on the async chat thread. Purchases are written to the database
 * first and only then reflected in the cache, so a relog can never "lose" a
 * cosmetic that was paid for.</p>
 */
public final class CosmeticServiceImpl implements CosmeticService {

    private final ShardService shards;
    private final ProgressionRepository repository;
    private final Settings.CosmeticSettings settings;

    private final Map<UUID, Set<String>> owned = new ConcurrentHashMap<>();
    private final Map<UUID, Map<CosmeticType, String>> equipped = new ConcurrentHashMap<>();

    public CosmeticServiceImpl(ShardService shards, ProgressionRepository repository,
                               Settings.CosmeticSettings settings) {
        this.shards = shards;
        this.repository = repository;
        this.settings = settings;
    }

    @Override
    public List<Cosmetic> catalogue() {
        return settings.catalogue();
    }

    @Override
    public List<Cosmetic> catalogue(CosmeticType type) {
        return settings.catalogue().stream().filter(cosmetic -> cosmetic.type() == type).toList();
    }

    @Override
    public Cosmetic byId(String id) {
        return settings.catalogue().stream()
                .filter(cosmetic -> cosmetic.id().equalsIgnoreCase(id))
                .findFirst()
                .orElse(null);
    }

    @Override
    public CompletableFuture<Set<String>> owned(UUID uuid) {
        Set<String> cached = owned.get(uuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(Set.copyOf(cached));
        }
        return repository.cosmetics(uuid).thenApply(rows -> {
            Set<String> ids = rows.stream().map(ProgressionRepository.OwnedCosmetic::cosmetic)
                    .collect(Collectors.toUnmodifiableSet());
            owned.put(uuid, ids);
            Map<CosmeticType, String> byType = new ConcurrentHashMap<>();
            for (var row : rows) {
                Cosmetic cosmetic = byId(row.cosmetic());
                if (row.equipped() && cosmetic != null) {
                    byType.put(cosmetic.type(), row.cosmetic());
                }
            }
            equipped.put(uuid, byType);
            return ids;
        });
    }

    @Override
    public CompletableFuture<Boolean> purchase(UUID uuid, String id) {
        Cosmetic cosmetic = byId(id);
        if (cosmetic == null || !settings.enabled()) {
            return CompletableFuture.completedFuture(false);
        }
        return owned(uuid).thenCompose(current -> {
            if (current.contains(cosmetic.id())) {
                return CompletableFuture.completedFuture(false);
            }
            return shards.take(uuid, cosmetic.shardCost(), "cosmetic " + cosmetic.id())
                    .thenCompose(taken -> {
                        if (!taken) {
                            return CompletableFuture.completedFuture(false);
                        }
                        return repository.grantCosmetic(uuid, cosmetic.id()).thenApply(granted -> {
                            if (!granted) {
                                shards.add(uuid, cosmetic.shardCost(), "cosmetic refund " + cosmetic.id());
                                return false;
                            }
                            Set<String> updated = new java.util.HashSet<>(current);
                            updated.add(cosmetic.id());
                            owned.put(uuid, Set.copyOf(updated));
                            return true;
                        });
                    });
        });
    }

    @Override
    public CompletableFuture<Boolean> equip(UUID uuid, String id) {
        Cosmetic cosmetic = byId(id);
        if (cosmetic == null) {
            return CompletableFuture.completedFuture(false);
        }
        return owned(uuid).thenCompose(current -> {
            if (!current.contains(cosmetic.id())) {
                return CompletableFuture.completedFuture(false);
            }
            String family = familyPrefix(cosmetic);
            return repository.equip(uuid, cosmetic.id(), family).thenApply(done -> {
                if (done) {
                    equipped.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>())
                            .put(cosmetic.type(), cosmetic.id());
                }
                return done;
            });
        });
    }

    @Override
    public CompletableFuture<Boolean> unequip(UUID uuid, CosmeticType type) {
        return repository.unequipFamily(uuid, familyPrefix(type)).thenApply(done -> {
            Map<CosmeticType, String> map = equipped.get(uuid);
            if (map != null) {
                map.remove(type);
            }
            return done;
        });
    }

    @Override
    public CompletableFuture<String> equipped(UUID uuid, CosmeticType type) {
        return owned(uuid).thenApply(ignored -> {
            Map<CosmeticType, String> map = equipped.get(uuid);
            return map == null ? null : map.get(type);
        });
    }

    @Override
    public String equippedCached(UUID uuid, CosmeticType type) {
        Map<CosmeticType, String> map = equipped.get(uuid);
        return map == null ? null : map.get(type);
    }

    /** The chat decoration for a player: prefix or name colour, MiniMessage-ready. */
    public String chatDecoration(UUID uuid, CosmeticType type) {
        String id = equippedCached(uuid, type);
        if (id == null) {
            return "";
        }
        Cosmetic cosmetic = byId(id);
        return cosmetic == null ? "" : cosmetic.value();
    }

    /** Family prefix used to replace the previous cosmetic of the same type. */
    private static String familyPrefix(Cosmetic cosmetic) {
        return familyPrefix(cosmetic.type());
    }

    private static String familyPrefix(CosmeticType type) {
        return type.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public void load(UUID uuid) {
        owned(uuid);
    }

    @Override
    public void unload(UUID uuid) {
        owned.remove(uuid);
        equipped.remove(uuid);
    }

    public Settings.CosmeticSettings settings() {
        return settings;
    }
}
