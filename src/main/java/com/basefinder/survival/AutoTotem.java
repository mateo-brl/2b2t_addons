package com.basefinder.survival;

import com.basefinder.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.rusherhack.client.api.utils.ChatUtils;

/**
 * Automatically keeps a Totem of Undying in the offhand slot.
 * Essential for 24/7 operation on 2b2t.
 */
public class AutoTotem {

    private final Minecraft mc = Minecraft.getInstance();
    private int checkInterval = 10; // Check every 0.5 seconds
    private int tickCounter = 0;
    private int lastTotemCount = -1;

    public void tick() {
        if (mc.player == null) return;

        tickCounter++;
        if (tickCounter % checkInterval != 0) return;

        // Check if offhand already has a totem
        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand.is(Items.TOTEM_OF_UNDYING)) return;

        // Find totem in inventory
        int totemSlot = findTotemInInventory();
        if (totemSlot < 0) {
            // No totems available
            int count = countTotems();
            if (count == 0 && lastTotemCount != 0) {
                ChatUtils.print("[Survival] " + Lang.t("WARNING: No totems left!", "ATTENTION : Plus de totems !"));
                lastTotemCount = 0;
            }
            return;
        }

        // Swap totem to offhand using SWAP click type with button 40 (offhand)
        int containerId = mc.player.inventoryMenu.containerId;
        mc.gameMode.handleInventoryMouseClick(containerId, totemSlot, 40, ClickType.SWAP, mc.player);

        int remaining = countTotems();
        if (remaining != lastTotemCount) {
            ChatUtils.print("[Survival] " + Lang.t("Totem equipped! " + remaining + " remaining.", "Totem équipé ! " + remaining + " restants."));
            lastTotemCount = remaining;
        }
    }

    private int findTotemInInventory() {
        if (mc.player == null) return -1;
        // Search main inventory (slots 9-35) and hotbar (slots 36-44)
        for (int i = 9; i <= 44; i++) {
            ItemStack stack = mc.player.inventoryMenu.getSlot(i).getItem();
            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                return i;
            }
        }
        return -1;
    }

    private int countTotems() {
        if (mc.player == null) return 0;
        int count = 0;
        // Offhand
        if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) count++;
        // Inventory
        for (int i = 9; i <= 44; i++) {
            ItemStack stack = mc.player.inventoryMenu.getSlot(i).getItem();
            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public int getTotemCount() { return countTotems(); }
}
