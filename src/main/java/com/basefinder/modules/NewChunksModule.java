package com.basefinder.modules;

import com.basefinder.scanner.ChunkAgeAnalyzer;
import com.basefinder.scanner.NewChunkDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import org.rusherhack.core.event.stage.Stage;
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
    private final NumberSetting<Integer> renderHeight = new NumberSetting<>("Render Y", 64, 0, 320);
    private final NumberSetting<Integer> renderDistance = new NumberSetting<>("Render Distance", 16, 4, 32);

    // Detection settings
    private final NullSetting detectionGroup = new NullSetting("Detection");
    private final BooleanSetting useLiquidDetection = new BooleanSetting("Liquid Detection", true);
    private final BooleanSetting useVersionDetection = new BooleanSetting("Version Detection", true);
    private final NumberSetting<Integer> classificationDelay = new NumberSetting<>("Classification Delay", 40, 10, 100);

    // Stats
    private final BooleanSetting logNewChunks = new BooleanSetting("Log to Chat", false);
    private int lastNewCount = 0;
    private int lastOldCount = 0;

    public NewChunksModule() {
        super("NewChunks", "Detects and highlights new vs old chunks (liquid flow + version detection)", ModuleCategory.EXTERNAL);

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

    @Subscribe
    private void onPacketReceive(EventPacket.Receive event) {
        if (!useLiquidDetection.getValue()) return;

        var packet = event.getPacket();

        if (packet instanceof ClientboundBlockUpdatePacket blockUpdate) {
            detector.onBlockUpdate(blockUpdate.getPos(), blockUpdate.getBlockState());
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket sectionUpdate) {
            sectionUpdate.runUpdates((pos, state) -> {
                detector.onBlockUpdate(pos.immutable(), state);
            });
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            detector.onChunkLoad(new ChunkPos(chunkPacket.getX(), chunkPacket.getZ()));
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forgetPacket) {
            detector.onChunkUnload(forgetPacket.pos());
        }
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (event.getStage() != Stage.PRE) return;

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
        if (logNewChunks.getValue()) {
            int newCount = detector.getNewChunkCount();
            int oldCount = detector.getOldChunkCount();
            if (newCount > lastNewCount) {
                ChatUtils.print("[NewChunks] New: " + newCount + " | Old: " + oldCount);
            }
            lastNewCount = newCount;
            lastOldCount = oldCount;
        }
    }

    @Subscribe
    private void onRender3D(EventRender3D event) {
        if (mc.player == null || mc.level == null) return;

        IRenderer3D renderer = event.getRenderer();

        ChunkPos playerChunk = new ChunkPos(mc.player.blockPosition());
        int renderDist = renderDistance.getValue();
        int y = renderHeight.getValue();

        // Render new chunks
        if (showNewChunks.getValue()) {
            int color = newChunkColor.getValue().getRGB();
            for (ChunkPos pos : detector.getNewChunks()) {
                if (isInRenderRange(pos, playerChunk, renderDist)) {
                    renderChunkOverlay(renderer, pos, y, color);
                }
            }
        }

        // Render old chunks
        if (showOldChunks.getValue()) {
            int color = oldChunkColor.getValue().getRGB();
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
    }

    private void renderChunkOverlay(IRenderer3D renderer, ChunkPos pos, int y, int color) {
        if (renderer == null) return;

        try {
            double x1 = pos.getMinBlockX();
            double z1 = pos.getMinBlockZ();
            double x2 = x1 + 16;
            double z2 = z1 + 16;

            // Draw chunk outline using lines instead of filled box
            renderer.drawLine(x1, y, z1, x2, y, z1, color);
            renderer.drawLine(x2, y, z1, x2, y, z2, color);
            renderer.drawLine(x2, y, z2, x1, y, z2, color);
            renderer.drawLine(x1, y, z2, x1, y, z1, color);
        } catch (Exception e) {
            // Silently ignore render errors to prevent crashes
        }
    }

    private void renderVersionBorders(IRenderer3D renderer, ChunkPos playerChunk, int renderDist, int y) {
        if (renderer == null) return;

        try {
            int color = versionBorderColor.getValue().getRGB();
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
