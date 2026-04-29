package com.basefinder.domain.scan;

/**
 * Comptes bruts produits par un scan de chunk, sans aucune dépendance Minecraft.
 *
 * Entrée pure du {@link ChunkClassifier}. Le scan MC produit ces compteurs depuis
 * un LevelChunk ; cette structure les transporte vers la logique de scoring testable.
 *
 * @param strongBlockCount    blocs 100 % joueur (shulkers comptés ici aussi)
 * @param mediumBlockCount    blocs ambigus (furnace, crafting table, etc.)
 * @param obsidianCount       obsidienne brute (>5 = significatif, >=10 = portail)
 * @param storageCount        chests, barrels, hoppers, shulkers, etc.
 * @param trailBlockCount     dirt path, rails, packed/blue ice, nether bricks
 * @param mapArtBlockCount    laine, béton, verre teinté, béton poudreux
 * @param shulkerCount        shulker boxes spécifiquement (drive STASH/STORAGE)
 * @param signWithTextCount   panneaux avec texte (15 pts/pc, fort indicateur)
 * @param ancientCityBlockCount blocs Deep Dark trouvés sous Y=0
 * @param villageSignatureCount blocs uniques aux villages (bell, composter, hay)
 * @param trialChamberSignatureCount blocs uniques aux trial chambers (vault, spawner, heavy core)
 * @param bedrockLayerBlocks  blocs forts/storage trouvés entre Y=0 et Y=10
 * @param skyLayerBlocks      blocs forts/storage trouvés au-dessus de Y=200
 * @param caveAirScore        score CaveAirAnalyzer (peut déclencher CAVE_MINING)
 */
public record ChunkCounts(
        int strongBlockCount,
        int mediumBlockCount,
        int obsidianCount,
        int storageCount,
        int trailBlockCount,
        int mapArtBlockCount,
        int shulkerCount,
        int signWithTextCount,
        int ancientCityBlockCount,
        int villageSignatureCount,
        int trialChamberSignatureCount,
        int bedrockLayerBlocks,
        int skyLayerBlocks,
        double caveAirScore
) {
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int strongBlockCount;
        private int mediumBlockCount;
        private int obsidianCount;
        private int storageCount;
        private int trailBlockCount;
        private int mapArtBlockCount;
        private int shulkerCount;
        private int signWithTextCount;
        private int ancientCityBlockCount;
        private int villageSignatureCount;
        private int trialChamberSignatureCount;
        private int bedrockLayerBlocks;
        private int skyLayerBlocks;
        private double caveAirScore;

        public Builder strongBlockCount(int v) { this.strongBlockCount = v; return this; }
        public Builder mediumBlockCount(int v) { this.mediumBlockCount = v; return this; }
        public Builder obsidianCount(int v) { this.obsidianCount = v; return this; }
        public Builder storageCount(int v) { this.storageCount = v; return this; }
        public Builder trailBlockCount(int v) { this.trailBlockCount = v; return this; }
        public Builder mapArtBlockCount(int v) { this.mapArtBlockCount = v; return this; }
        public Builder shulkerCount(int v) { this.shulkerCount = v; return this; }
        public Builder signWithTextCount(int v) { this.signWithTextCount = v; return this; }
        public Builder ancientCityBlockCount(int v) { this.ancientCityBlockCount = v; return this; }
        public Builder villageSignatureCount(int v) { this.villageSignatureCount = v; return this; }
        public Builder trialChamberSignatureCount(int v) { this.trialChamberSignatureCount = v; return this; }
        public Builder bedrockLayerBlocks(int v) { this.bedrockLayerBlocks = v; return this; }
        public Builder skyLayerBlocks(int v) { this.skyLayerBlocks = v; return this; }
        public Builder caveAirScore(double v) { this.caveAirScore = v; return this; }

        public ChunkCounts build() {
            return new ChunkCounts(
                    strongBlockCount, mediumBlockCount, obsidianCount, storageCount,
                    trailBlockCount, mapArtBlockCount, shulkerCount, signWithTextCount,
                    ancientCityBlockCount, villageSignatureCount, trialChamberSignatureCount,
                    bedrockLayerBlocks, skyLayerBlocks, caveAirScore);
        }
    }
}
