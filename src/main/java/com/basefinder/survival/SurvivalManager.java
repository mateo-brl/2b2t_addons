package com.basefinder.survival;

import com.basefinder.util.Lang;
import net.minecraft.client.Minecraft;
import org.rusherhack.client.api.utils.ChatUtils;

/**
 * Orchestrates all survival systems for 24/7 unattended operation.
 *
 * Priority order (highest first):
 * 1. Player detection - instant disconnect
 * 2. Health monitoring - emergency actions
 * 3. Auto totem - keep totem in offhand
 * 4. Auto eat - eat when hungry
 * 5. Firework resupply - refill from shulkers
 */
public class SurvivalManager {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Survival");
    private final Minecraft mc = Minecraft.getInstance();

    // Subsystems
    private final AutoTotem autoTotem = new AutoTotem();
    private final AutoEat autoEat = new AutoEat();
    private final PlayerDetector playerDetector = new PlayerDetector();
    private final FireworkResupply fireworkResupply = new FireworkResupply();

    // Enable flags
    private boolean enableAutoTotem = true;
    private boolean enableAutoEat = true;
    private boolean enablePlayerDetection = true;
    private boolean enableFireworkResupply = true;

    // Health monitoring
    private int healthThreshold = 10; // Emergency at HP < 10
    private int lastHealth = -1;
    private boolean emergencyLanding = false;

    // Statistics
    private long startTime = 0;
    private int disconnectCount = 0;

    public void onEnable() {
        startTime = System.currentTimeMillis();
        disconnectCount = 0;
        lastHealth = -1;
        emergencyLanding = false;

        StringBuilder status = new StringBuilder("[Survival] ");
        if (enableAutoTotem) status.append(Lang.t("Totem ", "Totem "));
        if (enableAutoEat) status.append(Lang.t("Eat ", "Manger "));
        if (enablePlayerDetection) status.append(Lang.t("Radar ", "Radar "));
        if (enableFireworkResupply) status.append(Lang.t("Resupply ", "Réappro "));
        ChatUtils.print(status.toString().trim());
    }

    /**
     * Tick all survival systems. Returns true if disconnected.
     */
    public boolean tick() {
        if (mc.player == null || mc.level == null) return false;

        // PRIORITY 1: Player detection - instant disconnect
        if (enablePlayerDetection) {
            if (playerDetector.tick()) {
                disconnectCount++;
                return true; // Disconnected!
            }
        }

        // PRIORITY 2: Health monitoring
        checkHealth();

        // PRIORITY 3: Auto totem
        if (enableAutoTotem) {
            autoTotem.tick();
        }

        // PRIORITY 4: Auto eat (skip if currently eating)
        if (enableAutoEat && !autoEat.isCurrentlyEating()) {
            autoEat.tick();
        } else if (enableAutoEat) {
            autoEat.tick(); // Continue eating process
        }

        // PRIORITY 5: Firework resupply
        if (enableFireworkResupply) {
            fireworkResupply.tick();
        }

        return false;
    }

    private void checkHealth() {
        if (mc.player == null) return;

        int health = (int) mc.player.getHealth();

        // Health dropped significantly
        if (lastHealth > 0 && health < lastHealth - 4) {
            ChatUtils.print("[Survival] " + Lang.t(
                    "Health dropped! " + health + " HP",
                    "Santé en baisse ! " + health + " PV"));
        }

        // Critical health
        if (health < healthThreshold && health > 0) {
            if (!emergencyLanding) {
                emergencyLanding = true;
                ChatUtils.print("[Survival] " + Lang.t(
                        "CRITICAL HEALTH (" + health + " HP)! Emergency actions...",
                        "SANTÉ CRITIQUE (" + health + " PV) ! Actions d'urgence..."));
            }
        } else {
            emergencyLanding = false;
        }

        lastHealth = health;
    }

    // Public API
    public AutoTotem getAutoTotem() { return autoTotem; }
    public AutoEat getAutoEat() { return autoEat; }
    public PlayerDetector getPlayerDetector() { return playerDetector; }
    public FireworkResupply getFireworkResupply() { return fireworkResupply; }

    public boolean isEmergencyLanding() { return emergencyLanding; }
    public boolean needsFireworkResupply() { return fireworkResupply.needsResupply(); }
    public boolean isResupplying() { return fireworkResupply.isResupplying(); }

    // Settings
    public void setEnableAutoTotem(boolean v) { this.enableAutoTotem = v; }
    public void setEnableAutoEat(boolean v) { this.enableAutoEat = v; }
    public void setEnablePlayerDetection(boolean v) { this.enablePlayerDetection = v; }
    public void setEnableFireworkResupply(boolean v) { this.enableFireworkResupply = v; }
    public void setHealthThreshold(int threshold) { this.healthThreshold = threshold; }
    public void setPlayerDetectRange(double range) { playerDetector.setDetectRange(range); }
    public void setResupplyThreshold(int threshold) { fireworkResupply.setResupplyThreshold(threshold); }

    // Stats
    public long getUptimeSeconds() { return (System.currentTimeMillis() - startTime) / 1000; }
    public int getDisconnectCount() { return disconnectCount; }
    public int getTotemCount() { return autoTotem.getTotemCount(); }
}
