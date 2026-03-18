package com.basefinder.modules;

import com.basefinder.util.BaritoneController;
import com.basefinder.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.core.component.DataComponents;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * AutoMending - Automatically repairs Mending elytras by mining XP-giving ores.
 * Supports all XP-dropping ores: lapis, redstone, diamond, emerald, coal, iron, gold,
 * copper, nether gold, nether quartz (with deepslate variants). Each ore type can be
 * individually toggled. Equips the most damaged elytra, uses Baritone to mine ores for XP,
 * swaps to the next elytra when repaired, and surfaces when all are done.
 */
public class AutoMendingModule extends ToggleableModule {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("AutoMending");

    // --- Ore Toggle Settings ---
    private final BooleanSetting mineLapis = new BooleanSetting("Mine Lapis", "Mine lapis_ore and deepslate_lapis_ore (2-5 XP)", true);
    private final BooleanSetting mineRedstone = new BooleanSetting("Mine Redstone", "Mine redstone_ore and deepslate_redstone_ore (1-5 XP)", true);
    private final BooleanSetting mineDiamond = new BooleanSetting("Mine Diamond", "Mine diamond_ore and deepslate_diamond_ore (3-7 XP)", true);
    private final BooleanSetting mineEmerald = new BooleanSetting("Mine Emerald", "Mine emerald_ore and deepslate_emerald_ore (3-7 XP)", true);
    private final BooleanSetting mineCoal = new BooleanSetting("Mine Coal", "Mine coal_ore and deepslate_coal_ore (0-2 XP)", false);
    private final BooleanSetting mineIron = new BooleanSetting("Mine Iron", "Mine iron_ore and deepslate_iron_ore (raw iron drop = XP)", false);
    private final BooleanSetting mineGold = new BooleanSetting("Mine Gold", "Mine gold_ore and deepslate_gold_ore (raw gold drop = XP)", false);
    private final BooleanSetting mineCopper = new BooleanSetting("Mine Copper", "Mine copper_ore and deepslate_copper_ore (raw copper drop = XP)", false);
    private final BooleanSetting mineNetherGold = new BooleanSetting("Mine Nether Gold", "Mine nether_gold_ore (0-1 XP)", false);
    private final BooleanSetting mineNetherQuartz = new BooleanSetting("Mine Nether Quartz", "Mine nether_quartz_ore (2-5 XP)", false);

    // --- General Settings ---
    private final NumberSetting<Integer> repairThreshold = new NumberSetting<>("Seuil réparation", "Durability considered 'repaired'", 430, 1, 432);
    private final BooleanSetting autoDisable = new BooleanSetting("Auto désactiver", "Disable module when all elytras are repaired", true);
    private final BooleanSetting goSurface = new BooleanSetting("Remonter surface", "Use Baritone 'surface' when done", true);
    private final BooleanSetting langFr = new BooleanSetting("Français", "Interface en français (off = English)", true);

    // --- State machine ---
    private enum MendingState {
        IDLE, EQUIPPING, MINING, SWAPPING, ASCENDING, DONE
    }

    private MendingState state = MendingState.IDLE;
    private final BaritoneController baritoneController = new BaritoneController();

    // Elytra swap state (3-tick process)
    private int swapStep = 0; // 0=none, 1=pickup, 2=equip, 3=putdown
    private int swapSlot = -1;

    // Tick counters
    private int tickCounter = 0;
    private int mineCheckInterval = 20; // check durability every second
    private int ascendingTimer = 0;
    private static final int ASCENDING_TIMEOUT = 6000; // 5 minutes

    // Track which elytras we've repaired
    private int elytrasRepaired = 0;
    private int totalElytrasToRepair = 0;

    public AutoMendingModule() {
        super("AutoMending", "Auto-repair Mending elytras by mining XP ores", ModuleCategory.EXTERNAL);

        this.registerSettings(
                mineLapis,
                mineRedstone,
                mineDiamond,
                mineEmerald,
                mineCoal,
                mineIron,
                mineGold,
                mineCopper,
                mineNetherGold,
                mineNetherQuartz,
                repairThreshold,
                autoDisable,
                goSurface,
                langFr
        );
    }

    @Override
    public void onEnable() {
        Lang.setFrench(langFr.getValue());

        if (mc.player == null || mc.level == null) {
            ChatUtils.print("[AutoMending] " + Lang.t("Must be in a world!", "Vous devez être dans un monde !"));
            this.toggle();
            return;
        }

        // Check Baritone
        if (!baritoneController.isAvailable()) {
            ChatUtils.print("[AutoMending] " + Lang.t("ERROR: Baritone not available!", "ERREUR : Baritone non disponible !"));
            this.toggle();
            return;
        }

        // Check at least one ore type selected
        if (!isAnyOreEnabled()) {
            ChatUtils.print("[AutoMending] " + Lang.t("ERROR: Select at least one ore type!", "ERREUR : Sélectionnez au moins un type de minerai !"));
            this.toggle();
            return;
        }

        // Find all Mending elytras that need repair
        List<Integer> mendingElytras = findAllDamagedMendingElytras();
        if (mendingElytras.isEmpty()) {
            // Also check equipped
            ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
            if (chest.is(Items.ELYTRA) && hasMending(chest) && needsRepair(chest)) {
                totalElytrasToRepair = 1;
            } else {
                ChatUtils.print("[AutoMending] " + Lang.t("No damaged Mending elytras found!", "Aucun elytra Mending abîmé trouvé !"));
                this.toggle();
                return;
            }
        } else {
            // Count equipped too if it needs repair
            ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
            totalElytrasToRepair = mendingElytras.size();
            if (chest.is(Items.ELYTRA) && hasMending(chest) && needsRepair(chest)) {
                totalElytrasToRepair++;
            }
        }

        // Warn if no pickaxe
        if (!hasPickaxe()) {
            ChatUtils.print("[AutoMending] " + Lang.t("WARNING: No pickaxe found!", "ATTENTION : Aucune pioche trouvée !"));
        }

        elytrasRepaired = 0;
        tickCounter = 0;
        ascendingTimer = 0;
        swapStep = 0;
        swapSlot = -1;

        ChatUtils.print("[AutoMending] " + Lang.t(
                "Starting! " + totalElytrasToRepair + " elytra(s) to repair.",
                "Démarrage ! " + totalElytrasToRepair + " elytra(s) à réparer."
        ));

        // Start by equipping the most damaged elytra
        state = MendingState.EQUIPPING;
    }

    @Override
    public void onDisable() {
        if (baritoneController.isAvailable()) {
            baritoneController.cancelAll();
        }
        state = MendingState.IDLE;
        swapStep = 0;
        swapSlot = -1;
        if (mc.level != null) {
            ChatUtils.print("[AutoMending] " + Lang.t("Stopped.", "Arrêté."));
        }
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        tickCounter++;

        // Process swap steps (1 step per tick for server sync)
        if (swapStep > 0) {
            processElytraSwap();
            return;
        }

        switch (state) {
            case IDLE -> {}
            case EQUIPPING -> handleEquipping();
            case MINING -> handleMining();
            case SWAPPING -> handleSwapping();
            case ASCENDING -> handleAscending();
            case DONE -> handleDone();
        }
    }

    // ===== STATE HANDLERS =====

    /**
     * EQUIPPING: Find the most damaged Mending elytra and equip it.
     */
    private void handleEquipping() {
        // Find the most damaged Mending elytra in inventory
        int mostDamagedSlot = findMostDamagedMendingElytra();

        if (mostDamagedSlot >= 0) {
            // Check if already equipped one that needs repair
            ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
            if (chest.is(Items.ELYTRA) && hasMending(chest) && needsRepair(chest)) {
                // Compare: is the inventory one MORE damaged?
                int equippedDurability = chest.getMaxDamage() - chest.getDamageValue();
                ItemStack invStack = mc.player.inventoryMenu.getSlot(mostDamagedSlot).getItem();
                int invDurability = invStack.getMaxDamage() - invStack.getDamageValue();

                if (invDurability < equippedDurability) {
                    // Swap to the more damaged one
                    startElytraSwap(mostDamagedSlot);
                    LOGGER.info("[AutoMending] Equipping more damaged elytra from slot {} (dur: {})", mostDamagedSlot, invDurability);
                } else {
                    // Currently equipped one is already the most damaged, proceed to mining
                    ChatUtils.print("[AutoMending] " + Lang.t(
                            "Equipped elytra durability: " + equippedDurability + "/432",
                            "Durabilité elytra équipé : " + equippedDurability + "/432"
                    ));
                    startMining();
                }
            } else {
                // No Mending elytra equipped, equip one
                startElytraSwap(mostDamagedSlot);
                ItemStack invStack = mc.player.inventoryMenu.getSlot(mostDamagedSlot).getItem();
                int dur = invStack.getMaxDamage() - invStack.getDamageValue();
                LOGGER.info("[AutoMending] Equipping elytra from slot {} (dur: {})", mostDamagedSlot, dur);
            }
        } else {
            // No damaged Mending elytra in inventory, check equipped
            ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
            if (chest.is(Items.ELYTRA) && hasMending(chest) && needsRepair(chest)) {
                int dur = chest.getMaxDamage() - chest.getDamageValue();
                ChatUtils.print("[AutoMending] " + Lang.t(
                        "Equipped elytra durability: " + dur + "/432",
                        "Durabilité elytra équipé : " + dur + "/432"
                ));
                startMining();
            } else {
                // All repaired!
                state = goSurface.getValue() ? MendingState.ASCENDING : MendingState.DONE;
            }
        }
    }

    /**
     * Check if at least one ore type is enabled.
     */
    private boolean isAnyOreEnabled() {
        return mineLapis.getValue() || mineRedstone.getValue() || mineDiamond.getValue()
                || mineEmerald.getValue() || mineCoal.getValue() || mineIron.getValue()
                || mineGold.getValue() || mineCopper.getValue() || mineNetherGold.getValue()
                || mineNetherQuartz.getValue();
    }

    /**
     * Start the Baritone mine command for selected ores.
     */
    private void startMining() {
        StringBuilder cmd = new StringBuilder("mine");

        if (mineLapis.getValue()) {
            cmd.append(" lapis_ore deepslate_lapis_ore");
        }
        if (mineRedstone.getValue()) {
            cmd.append(" redstone_ore deepslate_redstone_ore");
        }
        if (mineDiamond.getValue()) {
            cmd.append(" diamond_ore deepslate_diamond_ore");
        }
        if (mineEmerald.getValue()) {
            cmd.append(" emerald_ore deepslate_emerald_ore");
        }
        if (mineCoal.getValue()) {
            cmd.append(" coal_ore deepslate_coal_ore");
        }
        if (mineIron.getValue()) {
            cmd.append(" iron_ore deepslate_iron_ore");
        }
        if (mineGold.getValue()) {
            cmd.append(" gold_ore deepslate_gold_ore");
        }
        if (mineCopper.getValue()) {
            cmd.append(" copper_ore deepslate_copper_ore");
        }
        if (mineNetherGold.getValue()) {
            cmd.append(" nether_gold_ore");
        }
        if (mineNetherQuartz.getValue()) {
            cmd.append(" nether_quartz_ore");
        }

        baritoneController.executeCommand(cmd.toString());
        state = MendingState.MINING;

        // Count enabled ore types for the chat message
        int oreCount = 0;
        if (mineLapis.getValue()) oreCount++;
        if (mineRedstone.getValue()) oreCount++;
        if (mineDiamond.getValue()) oreCount++;
        if (mineEmerald.getValue()) oreCount++;
        if (mineCoal.getValue()) oreCount++;
        if (mineIron.getValue()) oreCount++;
        if (mineGold.getValue()) oreCount++;
        if (mineCopper.getValue()) oreCount++;
        if (mineNetherGold.getValue()) oreCount++;
        if (mineNetherQuartz.getValue()) oreCount++;

        ChatUtils.print("[AutoMending] " + Lang.t(
                "Mining " + oreCount + " ore type(s) for XP...",
                "Minage de " + oreCount + " type(s) de minerai pour XP..."
        ));
        LOGGER.info("[AutoMending] Started mining: {}", cmd);
    }

    /**
     * MINING: Monitor durability while Baritone mines.
     */
    private void handleMining() {
        if (tickCounter % mineCheckInterval != 0) return;

        // Check equipped elytra durability
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA) || !hasMending(chest)) {
            // Elytra was unequipped somehow, try to re-equip
            LOGGER.warn("[AutoMending] Equipped elytra lost, attempting re-equip");
            baritoneController.cancelAll();
            state = MendingState.EQUIPPING;
            return;
        }

        int durability = chest.getMaxDamage() - chest.getDamageValue();

        // Log progress every 5 seconds
        if (tickCounter % 100 == 0) {
            LOGGER.info("[AutoMending] Current elytra durability: {}/{}", durability, repairThreshold.getValue());
        }

        // Check if repaired
        if (durability >= repairThreshold.getValue()) {
            elytrasRepaired++;
            ChatUtils.print("[AutoMending] " + Lang.t(
                    "Elytra repaired! (" + elytrasRepaired + "/" + totalElytrasToRepair + ") Durability: " + durability,
                    "Elytra réparé ! (" + elytrasRepaired + "/" + totalElytrasToRepair + ") Durabilité : " + durability
            ));
            baritoneController.cancelAll();
            state = MendingState.SWAPPING;
            return;
        }

        // Check if Baritone stopped mining (no ores nearby) - relaunch
        if (!baritoneController.isMineProcessActive()) {
            LOGGER.info("[AutoMending] Baritone mine process stopped, relaunching...");
            startMining();
        }
    }

    /**
     * SWAPPING: Find next damaged Mending elytra and swap, or proceed to ascending.
     */
    private void handleSwapping() {
        int nextSlot = findMostDamagedMendingElytra();

        if (nextSlot >= 0) {
            ItemStack stack = mc.player.inventoryMenu.getSlot(nextSlot).getItem();
            int dur = stack.getMaxDamage() - stack.getDamageValue();
            ChatUtils.print("[AutoMending] " + Lang.t(
                    "Swapping to next elytra (durability: " + dur + ")",
                    "Échange vers le prochain elytra (durabilité : " + dur + ")"
            ));
            startElytraSwap(nextSlot);
            // After swap completes, processElytraSwap will set state back to MINING
        } else {
            // No more elytras to repair
            ChatUtils.print("[AutoMending] " + Lang.t(
                    "All elytras repaired! (" + elytrasRepaired + " total)",
                    "Tous les elytras réparés ! (" + elytrasRepaired + " au total)"
            ));
            if (goSurface.getValue()) {
                state = MendingState.ASCENDING;
                ascendingTimer = 0;
                baritoneController.executeCommand("surface");
                ChatUtils.print("[AutoMending] " + Lang.t("Ascending to surface...", "Remontée en surface..."));
            } else {
                state = MendingState.DONE;
            }
        }
    }

    /**
     * ASCENDING: Wait for Baritone to surface.
     */
    private void handleAscending() {
        ascendingTimer++;

        // Check if at surface: Y > 60 and on ground
        if (mc.player.getY() > 60 && mc.player.onGround()) {
            ChatUtils.print("[AutoMending] " + Lang.t("Reached surface!", "Surface atteinte !"));
            state = MendingState.DONE;
            return;
        }

        // Timeout after 5 minutes
        if (ascendingTimer >= ASCENDING_TIMEOUT) {
            ChatUtils.print("[AutoMending] " + Lang.t("Surface timeout! Stopping.", "Timeout remontée ! Arrêt."));
            state = MendingState.DONE;
        }
    }

    /**
     * DONE: Send completion message and optionally auto-disable.
     */
    private void handleDone() {
        ChatUtils.print("[AutoMending] " + Lang.t(
                "Done! Repaired " + elytrasRepaired + " elytra(s).",
                "Terminé ! " + elytrasRepaired + " elytra(s) réparé(s)."
        ));

        if (autoDisable.getValue()) {
            this.toggle();
        } else {
            state = MendingState.IDLE;
        }
    }

    // ===== ELYTRA SWAP (3-tick process, same pattern as ElytraBot) =====

    private void startElytraSwap(int inventorySlot) {
        swapSlot = inventorySlot;
        swapStep = 1;
        LOGGER.info("[AutoMending] Starting elytra swap from slot {}", inventorySlot);
    }

    /**
     * Process one step of the elytra swap per tick.
     * Step 1: Pick up new elytra from inventory slot
     * Step 2: Click chest armor slot (6) to swap
     * Step 3: Put old item in original slot
     */
    private void processElytraSwap() {
        if (mc.player == null || mc.gameMode == null || swapSlot < 0) {
            swapStep = 0;
            swapSlot = -1;
            return;
        }

        int containerId = mc.player.inventoryMenu.containerId;

        switch (swapStep) {
            case 1 -> {
                // Pick up elytra from inventory
                mc.gameMode.handleInventoryMouseClick(containerId, swapSlot, 0, ClickType.PICKUP, mc.player);
                swapStep = 2;
                LOGGER.info("[AutoMending] Swap step 1: picked up from slot {}", swapSlot);
            }
            case 2 -> {
                // Click chest slot to swap (slot 6 = chestplate in inventoryMenu)
                mc.gameMode.handleInventoryMouseClick(containerId, 6, 0, ClickType.PICKUP, mc.player);
                swapStep = 3;
                LOGGER.info("[AutoMending] Swap step 2: clicked chest slot");
            }
            case 3 -> {
                // Put old item in original inventory slot
                mc.gameMode.handleInventoryMouseClick(containerId, swapSlot, 0, ClickType.PICKUP, mc.player);
                swapStep = 0;
                swapSlot = -1;
                LOGGER.info("[AutoMending] Swap step 3: swap complete");

                // After swap, transition based on current state
                if (state == MendingState.EQUIPPING || state == MendingState.SWAPPING) {
                    // Start/resume mining
                    startMining();
                }
            }
            default -> {
                swapStep = 0;
                swapSlot = -1;
            }
        }
    }

    // ===== MENDING DETECTION (1.21.4) =====

    /**
     * Check if an ItemStack has the Mending enchantment.
     * Uses DataComponents + registry lookup for 1.21.4 compatibility.
     */
    private boolean hasMending(ItemStack stack) {
        if (stack.isEmpty()) return false;
        try {
            ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
            if (enchantments == null || enchantments.isEmpty()) return false;
            var registry = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            var mendingHolder = registry.getOrThrow(Enchantments.MENDING);
            return enchantments.getLevel(mendingHolder) > 0;
        } catch (Exception e) {
            LOGGER.warn("[AutoMending] Failed to check Mending enchantment: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if an elytra needs repair (durability below threshold).
     */
    private boolean needsRepair(ItemStack stack) {
        if (!stack.is(Items.ELYTRA)) return false;
        int durability = stack.getMaxDamage() - stack.getDamageValue();
        return durability < repairThreshold.getValue();
    }

    // ===== INVENTORY HELPERS =====

    /**
     * Find all damaged Mending elytras in inventory (slots 9-44).
     * Returns list of inventory slot indices.
     */
    private List<Integer> findAllDamagedMendingElytras() {
        List<Integer> result = new ArrayList<>();
        if (mc.player == null) return result;

        for (int i = 9; i <= 44; i++) {
            ItemStack stack = mc.player.inventoryMenu.getSlot(i).getItem();
            if (stack.is(Items.ELYTRA) && hasMending(stack) && needsRepair(stack)) {
                result.add(i);
            }
        }
        return result;
    }

    /**
     * Find the most damaged Mending elytra in inventory (slots 9-44).
     * Returns slot index or -1 if none found.
     */
    private int findMostDamagedMendingElytra() {
        if (mc.player == null) return -1;

        int bestSlot = -1;
        int lowestDurability = Integer.MAX_VALUE;

        for (int i = 9; i <= 44; i++) {
            ItemStack stack = mc.player.inventoryMenu.getSlot(i).getItem();
            if (stack.is(Items.ELYTRA) && hasMending(stack) && needsRepair(stack)) {
                int dur = stack.getMaxDamage() - stack.getDamageValue();
                if (dur < lowestDurability) {
                    lowestDurability = dur;
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    /**
     * Check if the player has a pickaxe in their inventory.
     */
    private boolean hasPickaxe() {
        if (mc.player == null) return false;
        for (int i = 0; i <= 44; i++) {
            ItemStack stack = mc.player.inventoryMenu.getSlot(i).getItem();
            if (stack.is(Items.DIAMOND_PICKAXE) || stack.is(Items.NETHERITE_PICKAXE)
                    || stack.is(Items.IRON_PICKAXE) || stack.is(Items.STONE_PICKAXE)
                    || stack.is(Items.WOODEN_PICKAXE) || stack.is(Items.GOLDEN_PICKAXE)) {
                return true;
            }
        }
        return false;
    }
}
