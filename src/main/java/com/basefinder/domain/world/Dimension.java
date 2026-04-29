package com.basefinder.domain.world;

/**
 * Énumération des dimensions Minecraft, indépendante de net.minecraft.*.
 *
 * Remplace les String.equals("overworld") dispersés dans PortalHunterModule,
 * AutoTravelModule et ChunkAnalysis (audit/01-domain-map.md §4).
 */
public enum Dimension {
    OVERWORLD,
    NETHER,
    END;

    public String legacyName() {
        return switch (this) {
            case OVERWORLD -> "overworld";
            case NETHER -> "nether";
            case END -> "end";
        };
    }

    public static Dimension fromLegacyName(String name) {
        if (name == null) return OVERWORLD;
        return switch (name.toLowerCase()) {
            case "nether" -> NETHER;
            case "end" -> END;
            default -> OVERWORLD;
        };
    }
}
