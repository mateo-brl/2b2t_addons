package com.basefinder.scanner;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Analyzes underground air patterns to detect player-mined areas.
 *
 * Vanilla caves have organic shapes; player-mined tunnels are rectilinear
 * (1x2 corridors, straight galleries). This analyzer detects:
 * 1. Abnormal air density at mining depths (Y=-64 to Y=60)
 * 2. Straight horizontal tunnels (4+ consecutive air blocks in a line)
 *
 * Performance: samples 1 in 4 blocks (25%) and skips empty sections.
 * Disabled in the Nether (too different generation).
 */
public class CaveAirAnalyzer {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("BaseFinder");

    // Y range to scan
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 60;

    // Air density thresholds (vanilla caves are ~15-25% air at Y=0-30)
    private static final double HIGH_DENSITY_THRESHOLD = 0.35;
    private static final double VERY_HIGH_DENSITY_THRESHOLD = 0.45;

    // Tunnel detection
    private static final int MIN_TUNNEL_LENGTH = 4;
    private static final int MAX_TUNNEL_BONUS_COUNT = 3;

    // Scoring
    private static final double HIGH_DENSITY_SCORE = 10.0;
    private static final double VERY_HIGH_DENSITY_SCORE = 20.0;
    private static final double TUNNEL_SCORE = 8.0;
    private static final double MIN_REPORT_SCORE = 15.0;

    /**
     * Analyze a chunk for underground mining patterns.
     *
     * @param chunk the chunk to analyze
     * @param level the world level
     * @return a score >= 0. Scores >= 15 indicate likely player mining.
     */
    public static double analyzeChunkCaveAir(LevelChunk chunk, Level level) {
        // Disable in Nether - generation is too different
        ResourceKey<Level> dimension = level.dimension();
        if (dimension == Level.NETHER) {
            return 0.0;
        }

        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = level.getMinSectionY();

        double totalScore = 0.0;
        int tunnelsFound = 0;

        for (int sectionIdx = 0; sectionIdx < sections.length; sectionIdx++) {
            LevelChunkSection section = sections[sectionIdx];
            if (section == null) continue;

            int sectionWorldY = (minSectionY + sectionIdx) << 4;

            // Only scan sections within our Y range
            if (sectionWorldY + 16 <= MIN_Y || sectionWorldY > MAX_Y) continue;

            // Skip sections that are entirely air (natural void, not interesting)
            if (section.hasOnlyAir()) continue;

            // === 1. Air density analysis (sampled: every 4th block) ===
            int airCount = 0;
            int sampledCount = 0;

            for (int x = 0; x < 16; x += 2) {
                for (int z = 0; z < 16; z += 2) {
                    for (int y = 0; y < 16; y += 2) {
                        int worldY = sectionWorldY + y;
                        if (worldY < MIN_Y || worldY > MAX_Y) continue;

                        sampledCount++;
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) {
                            airCount++;
                        }
                    }
                }
            }

            if (sampledCount == 0) continue;

            double airDensity = (double) airCount / sampledCount;

            // Score based on air density at deep levels (Y=0 to Y=30 is the sweet spot)
            if (sectionWorldY >= -64 && sectionWorldY <= 30) {
                if (airDensity > VERY_HIGH_DENSITY_THRESHOLD) {
                    totalScore += VERY_HIGH_DENSITY_SCORE;
                } else if (airDensity > HIGH_DENSITY_THRESHOLD) {
                    totalScore += HIGH_DENSITY_SCORE;
                }
            } else if (sectionWorldY > 30 && sectionWorldY <= MAX_Y) {
                // Slightly lower weight for higher sections
                if (airDensity > VERY_HIGH_DENSITY_THRESHOLD) {
                    totalScore += VERY_HIGH_DENSITY_SCORE * 0.5;
                } else if (airDensity > HIGH_DENSITY_THRESHOLD) {
                    totalScore += HIGH_DENSITY_SCORE * 0.5;
                }
            }

            // === 2. Straight tunnel detection (sampled scan) ===
            // Scan for horizontal lines of air at typical mining heights (1-3 blocks above floor)
            if (tunnelsFound < MAX_TUNNEL_BONUS_COUNT) {
                tunnelsFound += detectTunnelsInSection(section, sectionWorldY);
            }
        }

        // Add tunnel bonus (capped)
        int cappedTunnels = Math.min(tunnelsFound, MAX_TUNNEL_BONUS_COUNT);
        totalScore += cappedTunnels * TUNNEL_SCORE;

        return totalScore;
    }

    /**
     * Detect straight tunnels within a chunk section.
     * Scans X-axis and Z-axis lines for consecutive air blocks at mining heights.
     * Uses sampling: checks every 2nd row to keep performance in check.
     *
     * @return number of tunnels detected in this section
     */
    private static int detectTunnelsInSection(LevelChunkSection section, int sectionWorldY) {
        int tunnelCount = 0;

        // Only check Y offsets 1, 2, 3 within the section (typical mining corridor heights)
        for (int y = 0; y < 16 && y <= 3; y++) {
            int worldY = sectionWorldY + y;
            if (worldY < MIN_Y || worldY > MAX_Y) continue;

            // Scan X-axis lines (sample every 2nd z)
            for (int z = 0; z < 16; z += 2) {
                int consecutive = 0;
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state.isAir()) {
                        consecutive++;
                        if (consecutive >= MIN_TUNNEL_LENGTH) {
                            // Verify it's a tunnel: check block above is also air (2-high corridor)
                            if (y + 1 < 16) {
                                BlockState above = section.getBlockState(x, y + 1, z);
                                if (above.isAir()) {
                                    tunnelCount++;
                                    break; // One tunnel per line is enough
                                }
                            }
                        }
                    } else {
                        consecutive = 0;
                    }
                }
            }

            // Scan Z-axis lines (sample every 2nd x)
            for (int x = 0; x < 16; x += 2) {
                int consecutive = 0;
                for (int z = 0; z < 16; z++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state.isAir()) {
                        consecutive++;
                        if (consecutive >= MIN_TUNNEL_LENGTH) {
                            if (y + 1 < 16) {
                                BlockState above = section.getBlockState(x, y + 1, z);
                                if (above.isAir()) {
                                    tunnelCount++;
                                    break;
                                }
                            }
                        }
                    } else {
                        consecutive = 0;
                    }
                }
            }
        }

        return tunnelCount;
    }

    /**
     * @return the minimum score threshold for reporting cave mining.
     */
    public static double getMinReportScore() {
        return MIN_REPORT_SCORE;
    }
}
