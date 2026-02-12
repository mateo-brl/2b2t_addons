package com.basefinder.survival;

import com.basefinder.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.client.api.utils.InventoryUtils;

/**
 * Automatically eats food when hunger is low.
 * Prioritizes best food items available.
 */
public class AutoEat {

    private final Minecraft mc = Minecraft.getInstance();
    private int hungerThreshold = 14; // Eat when food level < 14 (7 bars)
    private int checkInterval = 20; // Check every second
    private int tickCounter = 0;

    // Eating state machine
    private boolean isEating = false;
    private int eatTimer = 0;
    private int previousSlot = -1;
    private static final int EAT_DURATION = 35; // 32 ticks + buffer

    public void tick() {
        if (mc.player == null) return;

        // Handle eating in progress
        if (isEating) {
            processEating();
            return;
        }

        tickCounter++;
        if (tickCounter % checkInterval != 0) return;

        // Check hunger level
        int foodLevel = mc.player.getFoodData().getFoodLevel();
        if (foodLevel >= hungerThreshold) return;

        // Find food in hotbar
        int foodSlot = findFoodInHotbar();
        if (foodSlot < 0) {
            // Try to move food from inventory to hotbar
            int invSlot = findFoodInInventory();
            if (invSlot >= 0) {
                InventoryUtils.swapSlots(invSlot, 44); // Move to hotbar slot 8
                foodSlot = 8;
            } else {
                return; // No food available
            }
        }

        // Start eating
        previousSlot = mc.player.getInventory().selected;
        mc.player.getInventory().selected = foodSlot;
        mc.options.keyUse.setDown(true);
        isEating = true;
        eatTimer = 0;
    }

    private void processEating() {
        eatTimer++;

        if (eatTimer >= EAT_DURATION) {
            // Done eating
            mc.options.keyUse.setDown(false);
            if (previousSlot >= 0) {
                mc.player.getInventory().selected = previousSlot;
            }
            isEating = false;
            previousSlot = -1;
        }
    }

    private int findFoodInHotbar() {
        if (mc.player == null) return -1;
        // Priority order: golden apple > enchanted golden apple > cooked beef > other food
        int bestSlot = -1;
        int bestPriority = -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            int priority = getFoodPriority(stack);
            if (priority > bestPriority) {
                bestPriority = priority;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private int findFoodInInventory() {
        if (mc.player == null) return -1;
        int bestSlot = -1;
        int bestPriority = -1;

        for (int i = 9; i <= 35; i++) {
            ItemStack stack = mc.player.inventoryMenu.getSlot(i).getItem();
            int priority = getFoodPriority(stack);
            if (priority > bestPriority) {
                bestPriority = priority;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private int getFoodPriority(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        if (stack.is(Items.ENCHANTED_GOLDEN_APPLE)) return 10;
        if (stack.is(Items.GOLDEN_APPLE)) return 9;
        if (stack.is(Items.COOKED_BEEF)) return 8;
        if (stack.is(Items.COOKED_PORKCHOP)) return 7;
        if (stack.is(Items.GOLDEN_CARROT)) return 7;
        if (stack.is(Items.COOKED_MUTTON)) return 6;
        if (stack.is(Items.COOKED_SALMON)) return 5;
        if (stack.is(Items.BREAD)) return 4;
        if (stack.is(Items.BAKED_POTATO)) return 4;
        if (stack.is(Items.COOKED_CHICKEN)) return 3;
        if (stack.is(Items.APPLE)) return 2;
        // Generic food check
        if (stack.has(net.minecraft.core.component.DataComponents.FOOD)) return 1;
        return -1;
    }

    /**
     * Stop eating and release held keys. Call on module disable.
     */
    public void stop() {
        if (isEating) {
            mc.options.keyUse.setDown(false);
            if (previousSlot >= 0 && mc.player != null) {
                mc.player.getInventory().selected = previousSlot;
            }
            isEating = false;
            previousSlot = -1;
            eatTimer = 0;
        }
    }

    public boolean isCurrentlyEating() { return isEating; }
    public void setHungerThreshold(int threshold) { this.hungerThreshold = threshold; }
}
