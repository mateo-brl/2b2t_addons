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
import net.minecraft.world.level.chunk.LevelChunk;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.network.EventPacket;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.events.world.EventChunk;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.client.api.utils.WorldUtils;
import org.rusherhack.core.event.stage.Stage;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NullSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.event.subscribe.Subscribe;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Set;

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
    private final NullSetting renderGroup = new NullSetting("Render", "Visual settings");
    private final BooleanSetting showNewChunks = new BooleanSetting("Show New", true);
    private final BooleanSetting showOldChunks = new BooleanSetting("Show Old", true);
    private final BooleanSetting showVersionBorders = new BooleanSetting("Show Version Borders", true);
    private final ColorSetting newChunkColor = new ColorSetting("New Color", new Color(255, 50, 50, 80))
            .setAlphaAllowed(true);
    private final ColorSetting oldChunkColor = new ColorSetting("Old Color", new Color(50, 255, 50, 80))
            .setAlphaAllowed(true);
    private final ColorSetting versionBorderColor = new ColorSetting("Version Color", new Color(255, 255, 50, 80))
            .setAlphaAllowed(true);
    private final NumberSetting<Integer> renderHeight = new NumberSetting<>("Render Y", 64, 0, 320)
            .incremental(1);
    private final NumberSetting<Integer> renderDistance = new NumberSetting<>("Render Distance", 16, 4, 32)
            .incremental(1);

    // Detection settings
    private final NullSetting detectionGroup = new NullSetting("Detection", "Detection settings");
    private final BooleanSetting useLiquidDetection = new BooleanSetting("Liquid Detection", true);
    private final BooleanSetting useVersionDetection = new BooleanSetting("Version Detection", true);
    private final NumberSetting<Integer> classificationDelay = new NumberSetting<>("Classification Delay", 40, 10, 100)
            .incremental(5);

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

        if (event.getPacket() instanceof ClientboundBlockUpdatePacket packet) {
            BlockPos pos = packet.getPos();
            BlockState state = packet.getBlockState();
            detector.onBlockUpdate(pos, state);
        }

        if (event.getPacket() instanceof ClientboundSectionBlocksUpdatePacket packet) {
            // Process multi-block updates
            packet.runUpdates((pos, state) -> {
                detector.onBlockUpdate(pos.immutable(), state);
            });
        }

        if (event.getPacket() instanceof ClientboundLevelChunkWithLightPacket packet) {
            ChunkPos pos = new ChunkPos(packet.getX(), packet.getZ());
            detector.onChunkLoad(pos);
        }

        if (event.getPacket() instanceof ClientboundForgetLevelChunkPacket packet) {
            ChunkPos pos = new ChunkPos(packet.getX(), packet.getZ());
            detector.onChunkUnload(pos);
        }
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        detector.tick();

        // Version-based detection on loaded chunks
        if (useVersionDetection.getValue() && mc.level != null) {
            // Only scan a few chunks per tick to avoid lag
            var chunks = WorldUtils.getChunks();
            int scanned = 0;
            for (var chunk : chunks) {
                if (scanned >= 2) break;
                ChunkPos pos = chunk.getPos();
                if (!detector.isNewChunk(pos) && !detector.isOldChunk(pos) && !detector.isPending(pos)) {
                    if (ageAnalyzer.isLikelyOldChunk(chunk)) {
                        // Force classify as old if version analysis says so
                        detector.onChunkLoad(pos); // register it
                        // The chunk won't get fluid updates since it's old, so it will
                        // eventually be classified as old by the delay mechanism
                    }
                    scanned++;
                }
            }
        }

        // Log new findings
        if (logNewChunks.getValue()) {
            int newCount = detector.getNewChunkCount();
            int oldCount = detector.getOldChunkCount();
            if (newCount != lastNewCount || oldCount != lastOldCount) {
                if (newCount > lastNewCount) {
                    ChatUtils.print("[NewChunks] New: " + newCount + " | Old: " + oldCount);
                }
                lastNewCount = newCount;
                lastOldCount = oldCount;
            }
        }
    }

    @Subscribe
    private void onRender3D(EventRender3D event) {
        if (mc.player == null || mc.level == null) return;

        IRenderer3D renderer = event.getRenderer();
        renderer.begin(event.getMatrixStack());

        ChunkPos playerChunk = new ChunkPos(mc.player.blockPosition());
        int renderDist = renderDistance.getValue();
        int y = renderHeight.getValue();

        // Render new chunks
        if (showNewChunks.getValue()) {
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

        renderer.end();
    }

    private void renderChunkOverlay(IRenderer3D renderer, ChunkPos pos, int y, int color) {
        double x1 = pos.getMinBlockX();
        double z1 = pos.getMinBlockZ();
        // Draw a flat plane at the specified Y level covering the chunk
        renderer.drawPlane(
                x1, y, z1,
                16, 16,
                net.minecraft.core.Direction.UP,
                true, false,
                color
        );
    }

    private void renderVersionBorders(IRenderer3D renderer, ChunkPos playerChunk, int renderDist, int y) {
        int color = versionBorderColor.getValueRGB();
        var chunks = WorldUtils.getChunks();

        for (var chunk : chunks) {
            ChunkPos pos = chunk.getPos();
            if (!isInRenderRange(pos, playerChunk, renderDist)) continue;

            if (ageAnalyzer.isLikelyOldChunk(chunk)) {
                renderChunkOverlay(renderer, pos, y, color);
            }
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
