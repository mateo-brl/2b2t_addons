package com.basefinder.util;

/**
 * Types of bases that can be detected.
 */
public enum BaseType {
    NONE("None"),
    CONSTRUCTION("Construction"),
    STORAGE("Storage"),
    MAP_ART("Map Art"),
    TRAIL("Trail");

    private final String displayName;

    BaseType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
