package com.basefinder.domain.scan;

/**
 * Types de bases détectables. Sortie de {@link ChunkClassifier#pickBaseType}.
 */
public enum BaseType {
    NONE("Aucun"),
    CONSTRUCTION("Construction"),
    STORAGE("Stockage"),
    MAP_ART("Map Art"),
    TRAIL("Piste"),
    STASH("Stash"),
    FARM("Ferme"),
    PORTAL("Portail"),
    CAVE_MINING("Ancienne zone minée (tunnel/galerie)");

    private final String displayName;

    BaseType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
