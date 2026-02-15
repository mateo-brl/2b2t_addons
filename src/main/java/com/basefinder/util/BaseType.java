package com.basefinder.util;

/**
 * Types of bases that can be detected.
 */
public enum BaseType {
    NONE("Aucun"),
    CONSTRUCTION("Construction"),
    STORAGE("Stockage"),
    MAP_ART("Map Art"),
    TRAIL("Piste"),
    STASH("Stash"),
    FARM("Ferme"),
    PORTAL("Portail");

    private final String displayName;

    BaseType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
