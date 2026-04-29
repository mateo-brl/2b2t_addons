package com.basefinder.domain.view;

/**
 * Snapshot immuable de l'état du module BaseFinder pour la couche présentation.
 *
 * Construit par le module à chaque tick, consommé read-only par le HUD ;
 * remplace les 13 getters {@code BaseFinderModule.getXxx()} et le pull
 * {@code RusherHackAPI.getModuleManager().getFeature("BaseHunter")} (audit/01 §3).
 *
 * Sérialisable tel quel vers le futur dashboard backend (audit/05 §5 étape 4).
 *
 * Tous les sous-records sont {@code @Nullable false} sauf {@link #terrain} et
 * {@link #trail}, où {@code null} traduit l'absence de la fonctionnalité.
 */
public record BaseFinderViewModel(
        boolean active,
        String stateName,
        FlightVm flight,
        /* @Nullable */ TerrainVm terrain,
        ScanVm scan,
        NavigationVm navigation,
        /* @Nullable */ TrailVm trail,
        SurvivalVm survival,
        LagVm lag,
        PlayerVm player
) {

    /** Snapshot retourné quand le module est désactivé. */
    public static final BaseFinderViewModel INACTIVE = new BaseFinderViewModel(
            false,
            "IDLE",
            FlightVm.OFF,
            null,
            ScanVm.EMPTY,
            NavigationVm.EMPTY,
            null,
            SurvivalVm.EMPTY,
            LagVm.OK,
            PlayerVm.UNKNOWN
    );

    public record FlightVm(
            boolean flying,
            String stateName,
            double destinationDistance,
            int fireworkCount,
            boolean circling,
            int circleTicks,
            boolean unloadedChunksAhead
    ) {
        public static final FlightVm OFF = new FlightVm(
                false, "IDLE", -1, 0, false, 0, false);
    }

    public record TerrainVm(
            int predictedMaxHeight,
            String source
    ) {
    }

    public record ScanVm(
            int scannedCount,
            int deferredCount,
            int basesFound
    ) {
        public static final ScanVm EMPTY = new ScanVm(0, 0, 0);
    }

    public record NavigationVm(
            boolean hasCurrentTarget,
            int waypointIndex,
            int waypointTotal,
            double progressPercent,
            double totalDistanceTraveled
    ) {
        public static final NavigationVm EMPTY = new NavigationVm(false, 0, 0, 0, 0);
    }

    public record TrailVm(
            String trailType,
            int trailLength
    ) {
    }

    public record SurvivalVm(
            int totemCount,
            long uptimeSeconds,
            boolean emergencyLanding,
            boolean resupplying
    ) {
        public static final SurvivalVm EMPTY = new SurvivalVm(0, 0, false, false);
    }

    public record LagVm(
            double estimatedTps,
            boolean severelyLagging
    ) {
        public static final LagVm OK = new LagVm(20.0, false);
    }

    public record PlayerVm(
            boolean present,
            int y,
            int health
    ) {
        public static final PlayerVm UNKNOWN = new PlayerVm(false, 0, 0);
    }
}
