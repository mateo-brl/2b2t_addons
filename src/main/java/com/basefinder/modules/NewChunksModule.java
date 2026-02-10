package com.basefinder.modules;

import com.basefinder.scanner.ChunkAgeAnalyzer;
import com.basefinder.scanner.NewChunkDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.network.EventPacket;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.client.api.utils.WorldUtils;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NullSetting;
import org.rusherhack.core.setting.NumberSetting;

import java.awt.Color;

/**
 * NewChunks module - Detects and visualizes new vs old chunks.
 *
 * Detection methods:
 * 1. Liquid flow exploit: New chunks have pending fluid ticks that produce
 *    block update packets when first loaded.
 * 2. Version detection: Checks for version-specific blocks (deepslate at Y=0-4,
 *    copper ore, ancient debris) to determine chunk generation version.
 *
 * Visual overlay:
 * - NEW chunks: Red overlay (never visited by a player)
 * - OLD chunks: Green overlay (previously visited)
 * - PRE-1.18 chunks: Yellow overlay (generated in older MC version)
 */
public class NewChunksModule extends ToggleableModule {

    private final NewChunkDetector detector = new NewChunkDetector();
    private final ChunkAgeAnalyzer ageAnalyzer = new ChunkAgeAnalyzer();

    // Render settings
    private final NullSetting renderGroup = new NullSetting("Render");
    private final BooleanSetting showNewChunks = new BooleanSetting("Show New", true);
    private final BooleanSetting showOldChunks = new BooleanSetting("Show Old", true);
    private final BooleanSetting showVersionBorders = new BooleanSetting("Show Version Borders", true);
    private final ColorSetting newChunkColor = new ColorSetting("New Color", new Color(255, 50, 50, 80));
    private final ColorSetting oldChunkColor = new ColorSetting("Old Color", new Color(50, 255, 50, 80));
    private final ColorSetting versionBorderColor = new ColorSetting("Version Color", new Color(255, 255, 50, 80));
    private final NumberSetting<Integer> renderHeight = new NumberSetting<>("Render Y", -1, -1, 320); // -1 = player Y
    private final NumberSetting<Integer> renderDistance = new NumberSetting<>("Render Distance", 16, 4, 32);

    // Detection settings
    private final NullSetting detectionGroup = new NullSetting("Detection");
    private final BooleanSetting useLiquidDetection = new BooleanSetting("Liquid Detection", true);
    private final BooleanSetting useVersionDetection = new BooleanSetting("Version Detection", true);
    private final NumberSetting<Integer> classificationDelay = new NumberSetting<>("Classification Delay", 5, 1, 100); // 5 ticks = 0.25 sec

    // Stats
    private final BooleanSetting logNewChunks = new BooleanSetting("Log to Chat", false);
    private int lastNewCount = 0;
    private int lastOldCount = 0;

    public NewChunksModule() {
        super("NewChunks", "Detects and highlights new vs old chunks (liquid flow + version detection)", ModuleCategory.RENDER);

        renderGroup.addSubSettings(showNewChunks, showOldChunks, showVersionBorders,
                newChunkColor, oldChunkColor, versionBorderColor, renderHeight, renderDistance);
        detectionGroup.addSubSettings(useLiquidDetection, useVersionDetection, classificationDelay);

        this.registerSettings(renderGroup, detectionGroup, logNewChunks);
    }

    @Override
    public void onEnable() {
        detector.setEnabled(true);
        detector.setClassificationDelay(classificationDelay.getValue());

        if (mc.level != null) {
            ChatUtils.print("[NewChunks] Enabled - tracking new/old chunks");
            ChatUtils.print("[NewChunks] Walk around to detect chunks. Lines will appear at Y=" + renderHeight.getValue());
        }
    }

    @Override
    public void onDisable() {
        detector.setEnabled(false);
        detector.reset();
        if (mc.level != null) {
            ChatUtils.print("[NewChunks] Disabled");
        }
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("NewChunks");
    private int packetCounter = 0;
    private int tickCounter = 0;

    @Subscribe
    private void onPacketReceive(EventPacket.Receive event) {
        var packet = event.getPacket();

        // Log chunk packets for debugging
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            ChunkPos pos = new ChunkPos(chunkPacket.getX(), chunkPacket.getZ());
            detector.onChunkLoad(pos);
            packetCounter++;
            if (packetCounter % 20 == 1) {
                LOGGER.info("[NewChunks] Chunk loaded: ({}, {}), pending: {}, new: {}, old: {}",
                    pos.x, pos.z, detector.getPendingCount(), detector.getNewChunkCount(), detector.getOldChunkCount());
            }
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forgetPacket) {
            detector.onChunkUnload(forgetPacket.pos());
        }

        // Liquid detection
        if (!useLiquidDetection.getValue()) return;

        if (packet instanceof ClientboundBlockUpdatePacket blockUpdate) {
            detector.onBlockUpdate(blockUpdate.getPos(), blockUpdate.getBlockState());
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket sectionUpdate) {
            sectionUpdate.runUpdates((pos, state) -> {
                detector.onBlockUpdate(pos.immutable(), state);
            });
        }
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        detector.tick();

        // Version-based detection on loaded chunks
        if (useVersionDetection.getValue() && mc.level != null) {
            var chunks = WorldUtils.getChunks();
            int scanned = 0;
            for (var chunk : chunks) {
                if (scanned >= 2) break;
                ChunkPos pos = chunk.getPos();
                if (!detector.isNewChunk(pos) && !detector.isOldChunk(pos) && !detector.isPending(pos)) {
                    if (ageAnalyzer.isLikelyOldChunk(chunk)) {
                        detector.onChunkLoad(pos);
                    }
                    scanned++;
                }
            }
        }

        // Log new findings
        int newCount = detector.getNewChunkCount();
        int oldCount = detector.getOldChunkCount();
        int pendingCount = detector.getPendingCount();

        if (logNewChunks.getValue() && (newCount > lastNewCount || oldCount > lastOldCount)) {
            ChatUtils.print("[NewChunks] New: " + newCount + " | Old: " + oldCount + " | Pending: " + pendingCount);
        }
        lastNewCount = newCount;
        lastOldCount = oldCount;
    }

    @Subscribe
    private void onRender3D(EventRender3D event) {
        if (mc.player == null || mc.level == null) return;

        IRenderer3D renderer = event.getRenderer();

        ChunkPos playerChunk = new ChunkPos(mc.player.blockPosition());
        int renderDist = renderDistance.getValue();

        // Use player Y if renderHeight is -1
        int y = renderHeight.getValue();
        if (y < 0) {
            y = (int) mc.player.getY();
        }

        int newChunks = detector.getNewChunkCount();
        int oldChunks = detector.getOldChunkCount();

        // Debug: log render attempt
        if (tickCounter % 100 == 0) {
            LOGGER.info("[NewChunks] Rendering at Y={}, new={}, old={}, playerChunk=({},{})",
                y, newChunks, oldChunks, playerChunk.x, playerChunk.z);
        }
        tickCounter++;

        // Begin rendering - REQUIRED before any draw calls
        renderer.begin(event.getMatrixStack());

        try {
            // Render new chunks (RED)
            if (showNewChunks.getValue() && newChunks > 0) {
                int color = newChunkColor.getValueRGB();
                for (ChunkPos pos : detector.getNewChunks()) {
                    if (isInRenderRange(pos, playerChunk, renderDist)) {
                        renderChunkOverlay(renderer, pos, y, color);
                    }
                }
            }

            // Render old chunks
            if (showOldChunks.getValue()) {
                int color = oldChunkColor.getValueRGB();
                for (ChunkPos pos : detector.getOldChunks()) {
                    if (isInRenderRange(pos, playerChunk, renderDist)) {
                        renderChunkOverlay(renderer, pos, y, color);
                    }
                }
            }

            // Render version borders
            if (showVersionBorders.getValue() && useVersionDetection.getValue()) {
                renderVersionBorders(renderer, playerChunk, renderDist, y);
            }
        } finally {
            // End rendering - REQUIRED after draw calls
            renderer.end();
        }
    }

    private void renderChunkOverlay(IRenderer3D renderer, ChunkPos pos, int y, int color) {
        if (renderer == null) return;

        try {
            double x1 = pos.getMinBlockX();
            double z1 = pos.getMinBlockZ();
            double x2 = x1 + 16;
            double z2 = z1 + 16;

            // Draw thick chunk outline (multiple Y levels for visibility)
            for (int yOffset = 0; yOffset <= 2; yOffset++) {
                double drawY = y + yOffset;
                renderer.drawLine(x1, drawY, z1, x2, drawY, z1, color);
                renderer.drawLine(x2, drawY, z1, x2, drawY, z2, color);
                renderer.drawLine(x2, drawY, z2, x1, drawY, z2, color);
                renderer.drawLine(x1, drawY, z2, x1, drawY, z1, color);
            }

            // Draw vertical corner lines for better visibility
            renderer.drawLine(x1, y, z1, x1, y + 2, z1, color);
            renderer.drawLine(x2, y, z1, x2, y + 2, z1, color);
            renderer.drawLine(x1, y, z2, x1, y + 2, z2, color);
            renderer.drawLine(x2, y, z2, x2, y + 2, z2, color);
        } catch (Exception e) {
            // Silently ignore render errors to prevent crashes
        }
    }

    private void renderVersionBorders(IRenderer3D renderer, ChunkPos playerChunk, int renderDist, int y) {
        if (renderer == null) return;

        try {
            int color = versionBorderColor.getValueRGB();
            var chunks = WorldUtils.getChunks();
            if (chunks == null) return;

            for (var chunk : chunks) {
                if (chunk == null) continue;
                ChunkPos pos = chunk.getPos();
                if (pos == null || !isInRenderRange(pos, playerChunk, renderDist)) continue;

                if (ageAnalyzer.isLikelyOldChunk(chunk)) {
                    renderChunkOverlay(renderer, pos, y, color);
                }
            }
        } catch (Exception e) {
            // Silently ignore render errors
        }
    }

    private boolean isInRenderRange(ChunkPos pos, ChunkPos playerChunk, int renderDist) {
        return Math.abs(pos.x - playerChunk.x) <= renderDist
                && Math.abs(pos.z - playerChunk.z) <= renderDist;
    }

    // Public accessors for other modules
    public NewChunkDetector getDetector() { return detector; }
    public ChunkAgeAnalyzer getAgeAnalyzer() { return ageAnalyzer; }
}
