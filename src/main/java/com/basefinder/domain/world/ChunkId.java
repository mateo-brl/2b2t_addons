package com.basefinder.domain.world;

/**
 * Identifiant d'un chunk indépendant de net.minecraft.world.level.ChunkPos.
 *
 * Couples (x, z) avec la dimension : un chunk overworld et un chunk nether aux
 * mêmes coordonnées sont des entités distinctes. Audit/01 §4 : les ChunkPos non-
 * désambiguïsés étaient une source de bugs.
 *
 * @param x   coordonnée X du chunk (chunk = bloc / 16)
 * @param z   coordonnée Z du chunk
 * @param dim dimension dans laquelle vit ce chunk
 */
public record ChunkId(int x, int z, Dimension dim) {

    public int blockMinX() {
        return x << 4;
    }

    public int blockMinZ() {
        return z << 4;
    }

    public int middleBlockX() {
        return (x << 4) + 8;
    }

    public int middleBlockZ() {
        return (z << 4) + 8;
    }

    /**
     * Clé Long compacte pour Long2ObjectOpenHashMap (32 bits x | 32 bits z).
     * La dimension n'est PAS encodée dans la clé : la map doit être par-dimension
     * ou on doit passer ChunkId en clé directement.
     */
    public long packed() {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }
}
