package com.basefinder.survival;

import com.basefinder.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.client.api.utils.InventoryUtils;

/**
 * Automatically resupplies firework rockets from shulker boxes in inventory.
 *
 * State machine:
 * IDLE -> LANDING -> PLACING -> OPENING -> TRANSFERRING -> CLOSING -> BREAKING -> COLLECTING -> DONE
 *
 * The player must have shulker boxes containing firework rockets in their inventory.
 */
public class FireworkResupply {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("FireworkResupply");
    private final Minecraft mc = Minecraft.getInstance();

    private ResupplyState state = ResupplyState.IDLE;
    private int stateTimer = 0;
    private int resupplyThreshold = 16; // Resupply when < this many fireworks
    private BlockPos placedShulkerPos = null;
    private int shulkerSlot = -1;

    public enum ResupplyState {
        IDLE,
        NEEDS_RESUPPLY,  // Signal to ElytraBot to land
        WAITING_GROUND,  // Wait until on ground
        PLACING,         // Place shulker box
        WAIT_PLACE,      // Wait for block to appear
        OPENING,         // Right-click to open
        WAIT_OPEN,       // Wait for GUI to open
        TRANSFERRING,    // Shift-click fireworks out
        CLOSING,         // Close shulker GUI
        BREAKING,        // Mine the shulker
        COLLECTING,      // Wait to pick up the shulker
        DONE             // Resupply complete, resume flight
    }

    /**
     * Tick the resupply system. Call every tick.
     */
    public void tick() {
        if (mc.player == null || mc.level == null) return;

        stateTimer++;

        switch (state) {
            case IDLE -> checkFireworkSupply();
            case WAITING_GROUND -> waitForGround();
            case PLACING -> placeShulker();
            case WAIT_PLACE -> waitForPlacement();
            case OPENING -> openShulker();
            case WAIT_OPEN -> waitForOpen();
            case TRANSFERRING -> transferFireworks();
            case CLOSING -> closeShulker();
            case BREAKING -> breakShulker();
            case COLLECTING -> waitForCollection();
            case DONE -> {
                state = ResupplyState.IDLE;
                stateTimer = 0;
            }
            default -> {}
        }

        // Safety timeout - if stuck in any state for too long, abort
        if (state != ResupplyState.IDLE && state != ResupplyState.NEEDS_RESUPPLY && stateTimer > 200) {
            LOGGER.warn("[FireworkResupply] Timeout in state {}, aborting", state);
            ChatUtils.print("[Survival] " + Lang.t("Resupply timeout, aborting.", "Timeout réapprovisionnement, abandon."));
            abort();
        }
    }

    private void checkFireworkSupply() {
        int fireworks = InventoryUtils.getItemCount(Items.FIREWORK_ROCKET, true, false);
        if (fireworks < resupplyThreshold && hasShulkerWithFireworks()) {
            state = ResupplyState.NEEDS_RESUPPLY;
            ChatUtils.print("[Survival] " + Lang.t(
                    "Low fireworks (" + fireworks + ")! Need to resupply from shulker.",
                    "Fusées basses (" + fireworks + ") ! Réapprovisionnement depuis shulker."));
        }
    }

    private void waitForGround() {
        if (mc.player.onGround()) {
            state = ResupplyState.PLACING;
            stateTimer = 0;
        }
    }

    private void placeShulker() {
        // Find a shulker with fireworks
        shulkerSlot = findShulkerWithFireworks();
        if (shulkerSlot < 0) {
            ChatUtils.print("[Survival] " + Lang.t("No shulker with fireworks found!", "Aucun shulker avec des fusées trouvé !"));
            state = ResupplyState.DONE;
            return;
        }

        // Move shulker to hotbar if needed
        if (shulkerSlot >= 9) {
            InventoryUtils.swapSlots(shulkerSlot, 36); // Move to hotbar slot 0
            mc.player.getInventory().selected = 0;
        } else {
            mc.player.getInventory().selected = shulkerSlot - 36;
        }

        // Look down to place
        mc.player.setXRot(90.0f);

        // Place at feet
        BlockPos placePos = mc.player.blockPosition().below();
        // Try placing on top of block below
        placedShulkerPos = mc.player.blockPosition();

        // Use item on block
        BlockHitResult hitResult = new BlockHitResult(
                Vec3.atCenterOf(placePos),
                Direction.UP,
                placePos,
                false
        );
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);

        state = ResupplyState.WAIT_PLACE;
        stateTimer = 0;
    }

    private void waitForPlacement() {
        if (stateTimer < 5) return; // Wait a few ticks

        // Check if shulker was placed
        if (placedShulkerPos != null && mc.level.getBlockState(placedShulkerPos).getBlock() instanceof ShulkerBoxBlock) {
            state = ResupplyState.OPENING;
            stateTimer = 0;
        } else if (stateTimer > 20) {
            // Try alternate position
            LOGGER.warn("[FireworkResupply] Shulker placement failed");
            abort();
        }
    }

    private void openShulker() {
        if (placedShulkerPos == null) { abort(); return; }

        // Look at shulker and right-click
        BlockHitResult hitResult = new BlockHitResult(
                Vec3.atCenterOf(placedShulkerPos),
                Direction.UP,
                placedShulkerPos,
                false
        );
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
        state = ResupplyState.WAIT_OPEN;
        stateTimer = 0;
    }

    private void waitForOpen() {
        if (stateTimer < 5) return;

        // Check if a container screen is open
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            state = ResupplyState.TRANSFERRING;
            stateTimer = 0;
        } else if (stateTimer > 20) {
            LOGGER.warn("[FireworkResupply] Failed to open shulker");
            abort();
        }
    }

    private void transferFireworks() {
        if (mc.player.containerMenu == mc.player.inventoryMenu) {
            abort();
            return;
        }

        int containerId = mc.player.containerMenu.containerId;
        boolean foundAny = false;

        // Shulker box has 27 slots (0-26)
        for (int i = 0; i < 27; i++) {
            ItemStack stack = mc.player.containerMenu.getSlot(i).getItem();
            if (stack.is(Items.FIREWORK_ROCKET)) {
                // Shift-click to move to player inventory
                mc.gameMode.handleInventoryMouseClick(containerId, i, 0, ClickType.QUICK_MOVE, mc.player);
                foundAny = true;
            }
        }

        if (foundAny) {
            int newCount = InventoryUtils.getItemCount(Items.FIREWORK_ROCKET, true, false);
            ChatUtils.print("[Survival] " + Lang.t(
                    "Resupplied! " + newCount + " fireworks now.",
                    "Réapprovisionné ! " + newCount + " fusées maintenant."));
        }

        state = ResupplyState.CLOSING;
        stateTimer = 0;
    }

    private void closeShulker() {
        mc.player.closeContainer();
        state = ResupplyState.BREAKING;
        stateTimer = 0;
    }

    private void breakShulker() {
        if (placedShulkerPos == null) { state = ResupplyState.DONE; return; }

        // Start breaking the shulker
        if (stateTimer == 1) {
            mc.gameMode.startDestroyBlock(placedShulkerPos, Direction.UP);
        }

        // Continue breaking
        mc.gameMode.continueDestroyBlock(placedShulkerPos, Direction.UP);

        // Check if broken
        if (mc.level.getBlockState(placedShulkerPos).isAir()) {
            state = ResupplyState.COLLECTING;
            stateTimer = 0;
        }

        // Timeout - try harder or abort
        if (stateTimer > 60) {
            LOGGER.warn("[FireworkResupply] Breaking shulker taking too long");
            state = ResupplyState.COLLECTING;
            stateTimer = 0;
        }
    }

    private void waitForCollection() {
        // Wait 20 ticks (1 second) for item to be picked up
        if (stateTimer >= 20) {
            state = ResupplyState.DONE;
            placedShulkerPos = null;
            ChatUtils.print("[Survival] " + Lang.t("Resupply complete! Resuming...", "Réapprovisionnement terminé ! Reprise..."));
        }
    }

    private void abort() {
        // Close any open GUI
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
        state = ResupplyState.DONE;
        placedShulkerPos = null;
        stateTimer = 0;
    }

    /**
     * Check if any shulker box in inventory contains firework rockets.
     */
    private boolean hasShulkerWithFireworks() {
        return findShulkerWithFireworks() >= 0;
    }

    /**
     * Find a shulker box in inventory that might contain firework rockets.
     * We can't easily check contents of unplaced shulkers in 1.21.4,
     * so we just find any shulker box (user said they keep fireworks in them).
     */
    private int findShulkerWithFireworks() {
        if (mc.player == null) return -1;
        for (int i = 9; i <= 44; i++) {
            ItemStack stack = mc.player.inventoryMenu.getSlot(i).getItem();
            if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) {
                if (blockItem.getBlock() instanceof ShulkerBoxBlock) {
                    return i;
                }
            }
        }
        return -1;
    }

    // Public API
    public ResupplyState getState() { return state; }
    public boolean needsResupply() { return state == ResupplyState.NEEDS_RESUPPLY; }
    public boolean isResupplying() { return state != ResupplyState.IDLE && state != ResupplyState.NEEDS_RESUPPLY && state != ResupplyState.DONE; }
    public void startResupply() { if (state == ResupplyState.NEEDS_RESUPPLY) { state = ResupplyState.WAITING_GROUND; stateTimer = 0; } }
    public void setResupplyThreshold(int threshold) { this.resupplyThreshold = threshold; }
}
