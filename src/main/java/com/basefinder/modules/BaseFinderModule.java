package com.basefinder.modules;

import com.basefinder.elytra.ElytraBot;
import com.basefinder.logger.BaseLogger;
import com.basefinder.navigation.NavigationHelper;
import com.basefinder.scanner.ChunkAgeAnalyzer;
import com.basefinder.scanner.ChunkScanner;
import com.basefinder.scanner.NewChunkDetector;
import com.basefinder.trail.TrailFollower;
import com.basefinder.util.BaseRecord;
import com.basefinder.util.BaseType;
import com.basefinder.util.ChunkAnalysis;
import net.minecraft.core.BlockPos;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.IModule;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.setting.StringSetting;
import org.rusherhack.core.setting.NullSetting;

import java.util.List;

/**
 * Main BaseFinder module - orchestrates the entire automated base hunting process.
 *
 * State machine:
 * SCANNING -> TRAIL_FOLLOWING -> FLYING -> SCANNING -> ...
 *
 * When enabled, it:
 * 1. Scans loaded chunks for player activity
 * 2. If a trail is found, follows it
 * 3. Uses elytra + fireworks to navigate between waypoints
 * 4. Logs all found bases to file and chat
 */
public class BaseFinderModule extends ToggleableModule {

    // Components
    private final ChunkScanner scanner = new ChunkScanner();
    private final TrailFollower trailFollower = new TrailFollower();
    private final ElytraBot elytraBot = new ElytraBot();
    private final NavigationHelper navigation = new NavigationHelper();
    private final BaseLogger logger = new BaseLogger();

    // State
    private FinderState state = FinderState.IDLE;
    private int tickCounter = 0;
    private int scanInterval = 20; // scan every second

    // === Settings ===

    // Search pattern
    private final NullSetting searchGroup = new NullSetting("Search Settings", "Configure search behavior");
    private final StringSetting searchPattern = new StringSetting("Pattern", "SPIRAL")
            .setNameVisible(true);
    private final NumberSetting<Integer> scanIntervalSetting = new NumberSetting<>("Scan Interval", 20, 5, 100)
            .incremental(5);
    private final NumberSetting<Double> minScore = new NumberSetting<>("Min Score", 20.0, 5.0, 200.0)
            .incremental(5.0);
    private final NumberSetting<Double> waypointThreshold = new NumberSetting<>("Waypoint Radius", 100.0, 20.0, 500.0)
            .incremental(10.0);

    // Detection filters
    private final NullSetting detectionGroup = new NullSetting("Detection Filters", "What to detect");
    private final BooleanSetting detectConstruction = new BooleanSetting("Constructions", true);
    private final BooleanSetting detectStorage = new BooleanSetting("Storage Bases", true);
    private final BooleanSetting detectMapArt = new BooleanSetting("Map Art", true);
    private final BooleanSetting detectTrails = new BooleanSetting("Trails", true);
    private final BooleanSetting followTrails = new BooleanSetting("Follow Trails", true);
    private final BooleanSetting useChunkTrails = new BooleanSetting("Chunk Trails", "Use new/old chunk data for trail detection", true);
    private final BooleanSetting useVersionBorders = new BooleanSetting("Version Borders", "Detect version generation borders", true);

    // Elytra settings
    private final NullSetting elytraGroup = new NullSetting("Elytra Settings", "Flight parameters");
    private final NumberSetting<Double> cruiseAltitude = new NumberSetting<>("Cruise Altitude", 200.0, 50.0, 350.0)
            .incremental(10.0);
    private final NumberSetting<Double> minAltitude = new NumberSetting<>("Min Altitude", 100.0, 30.0, 200.0)
            .incremental(10.0);
    private final NumberSetting<Integer> fireworkInterval = new NumberSetting<>("Firework Interval", 40, 10, 100)
            .incremental(5);
    private final BooleanSetting useElytra = new BooleanSetting("Use Elytra", true);

    // Navigation settings
    private final NullSetting navGroup = new NullSetting("Navigation", "Search area parameters");
    private final NumberSetting<Double> spiralStep = new NumberSetting<>("Spiral Step", 500.0, 100.0, 5000.0)
            .incremental(100.0);
    private final NumberSetting<Integer> searchMinDist = new NumberSetting<>("Min Distance", 5000, 100, 50000)
            .incremental(1000);
    private final NumberSetting<Integer> searchMaxDist = new NumberSetting<>("Max Distance", 100000, 10000, 500000)
            .incremental(10000);

    // Logging
    private final BooleanSetting logToChat = new BooleanSetting("Log to Chat", true);
    private final BooleanSetting logToFile = new BooleanSetting("Log to File", true);

    public enum FinderState {
        IDLE,
        SCANNING,
        TRAIL_FOLLOWING,
        FLYING_TO_WAYPOINT,
        INVESTIGATING,
        PAUSED
    }

    public BaseFinderModule() {
        super("BaseFinder", "Automated base hunting - scans chunks, follows trails, flies with elytra", ModuleCategory.WORLD);

        // Register settings with groups
        searchGroup.addSubSettings(searchPattern, scanIntervalSetting, minScore, waypointThreshold);
        detectionGroup.addSubSettings(detectConstruction, detectStorage, detectMapArt, detectTrails, followTrails, useChunkTrails, useVersionBorders);
        elytraGroup.addSubSettings(cruiseAltitude, minAltitude, fireworkInterval, useElytra);
        navGroup.addSubSettings(spiralStep, searchMinDist, searchMaxDist);

        this.registerSettings(
                searchGroup,
                detectionGroup,
                elytraGroup,
                navGroup,
                logToChat,
                logToFile
        );
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) {
            ChatUtils.print("[BaseFinder] Must be in a world to enable!");
            this.toggle();
            return;
        }

        // Apply settings to components
        applySettings();

        // Connect to NewChunks module if it's active
        connectToNewChunksModule();

        // Initialize navigation
        NavigationHelper.SearchPattern pattern;
        try {
            pattern = NavigationHelper.SearchPattern.valueOf(searchPattern.getValue().toUpperCase());
        } catch (IllegalArgumentException e) {
            pattern = NavigationHelper.SearchPattern.SPIRAL;
        }
        navigation.initializeSearch(pattern, mc.player.blockPosition());

        state = FinderState.SCANNING;
        tickCounter = 0;

        ChatUtils.print("[BaseFinder] Started! Pattern: " + pattern + " | Waypoints: " + navigation.getWaypointCount());
    }

    @Override
    public void onDisable() {
        state = FinderState.IDLE;
        elytraBot.stop();
        trailFollower.stopFollowing();

        if (mc.level != null) {
            ChatUtils.print("[BaseFinder] Stopped. Found " + logger.getCount() + " bases. Scanned " + scanner.getScannedCount() + " chunks.");
        }
    }

    private void applySettings() {
        scanner.setMinScore(minScore.getValue());
        scanner.setDetectConstruction(detectConstruction.getValue());
        scanner.setDetectStorage(detectStorage.getValue());
        scanner.setDetectMapArt(detectMapArt.getValue());
        scanner.setDetectTrails(detectTrails.getValue());

        elytraBot.setCruiseAltitude(cruiseAltitude.getValue());
        elytraBot.setMinAltitude(minAltitude.getValue());
        elytraBot.setFireworkInterval(fireworkInterval.getValue());

        navigation.setSpiralStep(spiralStep.getValue());
        navigation.setSearchMinDistance(searchMinDist.getValue());
        navigation.setSearchMaxDistance(searchMaxDist.getValue());

        logger.setLogToChat(logToChat.getValue());
        logger.setLogToFile(logToFile.getValue());

        scanInterval = scanIntervalSetting.getValue();
    }

    /**
     * Connect TrailFollower to NewChunks module's detector and analyzer
     * so it can use chunk age data for trail detection.
     */
    private void connectToNewChunksModule() {
        IModule ncModule = RusherHackAPI.getModuleManager().getFeature("NewChunks").orElse(null);
        if (ncModule instanceof NewChunksModule newChunksModule) {
            if (useChunkTrails.getValue()) {
                trailFollower.setNewChunkDetector(newChunksModule.getDetector());
                ChatUtils.print("[BaseFinder] Connected to NewChunks - chunk trail detection enabled");
            }
            if (useVersionBorders.getValue()) {
                trailFollower.setChunkAgeAnalyzer(newChunksModule.getAgeAnalyzer());
                ChatUtils.print("[BaseFinder] Connected to NewChunks - version border detection enabled");
            }
        } else {
            if (useChunkTrails.getValue() || useVersionBorders.getValue()) {
                ChatUtils.print("[BaseFinder] Enable NewChunks module for chunk trail & version border detection");
            }
        }
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        tickCounter++;
        navigation.updateTracking();

        switch (state) {
            case SCANNING -> handleScanning();
            case TRAIL_FOLLOWING -> handleTrailFollowing();
            case FLYING_TO_WAYPOINT -> handleFlying();
            case INVESTIGATING -> handleInvestigating();
            case PAUSED, IDLE -> {}
        }
    }

    private void handleScanning() {
        // Scan chunks periodically
        if (tickCounter % scanInterval != 0) return;

        List<ChunkAnalysis> newFinds = scanner.scanLoadedChunks();

        for (ChunkAnalysis analysis : newFinds) {
            if (analysis.getBaseType() != BaseType.TRAIL) {
                BaseRecord record = new BaseRecord(
                        analysis.getCenterBlockPos(),
                        analysis.getBaseType(),
                        analysis.getScore(),
                        analysis.getPlayerBlockCount(),
                        analysis.getStorageCount(),
                        analysis.getShulkerCount()
                );
                logger.logBase(record);
            }
        }

        // Check for trails to follow
        if (followTrails.getValue() && !scanner.getTrailChunks().isEmpty()) {
            if (trailFollower.detectTrail(scanner.getTrailChunks())) {
                state = FinderState.TRAIL_FOLLOWING;
                ChatUtils.print("[BaseFinder] Trail detected! Following...");
                return;
            }
        }

        // If scanning is done (all loaded chunks scanned), fly to next waypoint
        if (useElytra.getValue() && navigation.getCurrentTarget() != null) {
            state = FinderState.FLYING_TO_WAYPOINT;
            elytraBot.startFlight(navigation.getCurrentTarget());
        }
    }

    private void handleTrailFollowing() {
        BlockPos target = trailFollower.getNextTrailTarget();

        if (target == null) {
            // Lost the trail
            ChatUtils.print("[BaseFinder] Trail lost after " + trailFollower.getTrailLength() + " blocks. Resuming scan.");
            trailFollower.stopFollowing();
            state = FinderState.SCANNING;
            return;
        }

        // Walk/fly towards trail target
        if (useElytra.getValue() && mc.player != null && mc.player.isFallFlying()) {
            elytraBot.startFlight(target);
            elytraBot.tick();
        } else {
            // Ground movement - set player rotation towards target
            if (mc.player != null) {
                float yaw = trailFollower.getTrailYaw();
                mc.player.setYRot(yaw);
                // Auto-sprint forward
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(true);
            }
        }

        // Keep scanning while following trail
        if (tickCounter % scanInterval == 0) {
            List<ChunkAnalysis> newFinds = scanner.scanLoadedChunks();
            for (ChunkAnalysis analysis : newFinds) {
                if (analysis.getBaseType() != BaseType.TRAIL && analysis.getBaseType() != BaseType.NONE) {
                    BaseRecord record = new BaseRecord(
                            analysis.getCenterBlockPos(),
                            analysis.getBaseType(),
                            analysis.getScore(),
                            analysis.getPlayerBlockCount(),
                            analysis.getStorageCount(),
                            analysis.getShulkerCount()
                    );
                    logger.logBase(record);

                    // If we found something big, investigate
                    if (analysis.getScore() >= minScore.getValue() * 2) {
                        state = FinderState.INVESTIGATING;
                        ChatUtils.print("[BaseFinder] Significant find! Investigating...");
                        return;
                    }
                }
            }
        }
    }

    private void handleFlying() {
        elytraBot.tick();

        // Keep scanning while flying
        if (tickCounter % scanInterval == 0) {
            List<ChunkAnalysis> newFinds = scanner.scanLoadedChunks();
            for (ChunkAnalysis analysis : newFinds) {
                if (analysis.getBaseType() != BaseType.NONE && analysis.getBaseType() != BaseType.TRAIL) {
                    BaseRecord record = new BaseRecord(
                            analysis.getCenterBlockPos(),
                            analysis.getBaseType(),
                            analysis.getScore(),
                            analysis.getPlayerBlockCount(),
                            analysis.getStorageCount(),
                            analysis.getShulkerCount()
                    );
                    logger.logBase(record);
                }
            }

            // Check for trails
            if (followTrails.getValue() && !scanner.getTrailChunks().isEmpty()) {
                if (trailFollower.detectTrail(scanner.getTrailChunks())) {
                    elytraBot.stop();
                    state = FinderState.TRAIL_FOLLOWING;
                    ChatUtils.print("[BaseFinder] Trail detected while flying! Following...");
                    return;
                }
            }
        }

        // Check if we reached the waypoint
        if (navigation.isNearTarget(waypointThreshold.getValue())) {
            ChatUtils.print("[BaseFinder] Reached waypoint " + (navigation.getCurrentWaypointIndex() + 1) + "/" + navigation.getWaypointCount());
            if (!navigation.advanceToNext()) {
                ChatUtils.print("[BaseFinder] All waypoints visited! Total bases found: " + logger.getCount());
                this.toggle();
                return;
            }
            state = FinderState.SCANNING;
            elytraBot.stop();
        }

        // Handle elytra bot issues
        if (!elytraBot.isFlying() && useElytra.getValue()) {
            // Try to restart flight
            if (navigation.getCurrentTarget() != null) {
                elytraBot.startFlight(navigation.getCurrentTarget());
            }
        }
    }

    private void handleInvestigating() {
        // Scan the area thoroughly
        if (tickCounter % 10 == 0) {
            scanner.scanLoadedChunks();
        }

        // Stay and investigate for 5 seconds (100 ticks), then continue
        if (tickCounter % 100 == 0) {
            state = FinderState.SCANNING;
            ChatUtils.print("[BaseFinder] Investigation complete. Continuing search.");
        }
    }

    // Public accessors for HUD and commands
    public FinderState getState() { return state; }
    public ChunkScanner getScanner() { return scanner; }
    public TrailFollower getTrailFollower() { return trailFollower; }
    public ElytraBot getElytraBot() { return elytraBot; }
    public NavigationHelper getNavigation() { return navigation; }
    public BaseLogger getBaseLogger() { return logger; }

    public void pause() {
        if (state != FinderState.IDLE && state != FinderState.PAUSED) {
            state = FinderState.PAUSED;
            elytraBot.stop();
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            ChatUtils.print("[BaseFinder] Paused.");
        }
    }

    public void resume() {
        if (state == FinderState.PAUSED) {
            state = FinderState.SCANNING;
            ChatUtils.print("[BaseFinder] Resumed.");
        }
    }

    public void skipWaypoint() {
        if (navigation.advanceToNext()) {
            ChatUtils.print("[BaseFinder] Skipped to waypoint " + (navigation.getCurrentWaypointIndex() + 1));
            state = FinderState.SCANNING;
        }
    }
}
