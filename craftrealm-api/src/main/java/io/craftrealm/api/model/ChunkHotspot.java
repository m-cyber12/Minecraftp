package io.craftrealm.api.model;

/** A chunk that holds far more entities than it should. */
public record ChunkHotspot(String world, int chunkX, int chunkZ, int entities, String topType) {

    public String key() {
        return world + ":" + chunkX + ":" + chunkZ;
    }
}
