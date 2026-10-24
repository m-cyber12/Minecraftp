package io.craftrealm.api.model;

/**
 * A single cosmetic in the shop.
 *
 * @param id          stable id, e.g. {@code prefix.smith}
 * @param type        category
 * @param value       the payload (MiniMessage snippet, hex colour or trail id)
 * @param shardCost   price in Shards
 * @param displayName already-localised display name
 */
public record Cosmetic(String id, CosmeticType type, String value, int shardCost, String displayName) {
}
