package com.basefinder.survival;

import com.basefinder.elytra.ElytraBot;
import com.basefinder.logger.DiscordNotifier;
import com.basefinder.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
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

    // External references
    private DiscordNotifier discordNotifier;
    private ElytraBot elytraBot;

    // Enable flags
    private boolean enableAutoTotem = true;
    private boolean enableAutoEat = true;
    private boolean enablePlayerDetection = true;
    private boolean enableFireworkResupply = true;
    private boolean enableAutoDisconnect = true;

    // Health monitoring
    private int healthThreshold = 10; // Emergency at HP < 10
    private int lastHealth = -1;
    private boolean emergencyLanding = false;

    // Critical alerts cooldowns (avoid spam)
    private long lastHealthAlertTime = 0;
    private long lastElytraAlertTime = 0;
    private long lastFireworkAlertTime = 0;
    private static final long ALERT_COOLDOWN_MS = 60000; // 1 minute between same alert type

    // Statistics
    private long startTime = 0;
    private long previousUptime = 0; // Cumulated uptime from previous sessions
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

        // PRIORITY 2: Equipment check - auto disconnect if no elytra AND no fireworks
        if (enableAutoDisconnect && elytraBot != null) {
            boolean shouldDisconnect = checkEquipmentCritical();
            if (shouldDisconnect) {
                disconnectCount++;
                return true; // Disconnected!
            }
        }

        // PRIORITY 3: Health monitoring
        checkHealth();

        // PRIORITY 4: Auto totem
        if (enableAutoTotem) {
            autoTotem.tick();
        }

        // PRIORITY 5: Auto eat (skip if currently eating)
        if (enableAutoEat && !autoEat.isCurrentlyEating()) {
            autoEat.tick();
        } else if (enableAutoEat) {
            autoEat.tick(); // Continue eating process
        }

        // PRIORITY 6: Firework resupply
        if (enableFireworkResupply) {
            fireworkResupply.tick();
        }

        return false;
    }

    /**
     * Check if equipment is critically low. Returns true if we should disconnect.
     */
    private boolean checkEquipmentCritical() {
        if (elytraBot == null) return false;

        int fireworks = elytraBot.getFireworkCount();
        int elytras = elytraBot.getElytraCount();
        int elytraDurability = elytraBot.getEquippedElytraDurability();
        long now = System.currentTimeMillis();

        // Alert: low fireworks (< 8)
        if (fireworks > 0 && fireworks < 8 && now - lastFireworkAlertTime > ALERT_COOLDOWN_MS) {
            lastFireworkAlertTime = now;
            String msg = Lang.t(
                    "Low fireworks: " + fireworks + " remaining",
                    "Fusées basses : " + fireworks + " restantes");
            ChatUtils.print("[Survival] " + msg);
            sendAlert("\u26A0\uFE0F " + Lang.t("Low Fireworks", "Fusées basses"), msg, 0xFFA500);
        }

        // Alert: no fireworks
        if (fireworks == 0 && now - lastFireworkAlertTime > ALERT_COOLDOWN_MS) {
            lastFireworkAlertTime = now;
            String msg = Lang.t(
                    "NO FIREWORKS! Cannot fly.",
                    "PLUS DE FUSÉES ! Vol impossible.");
            ChatUtils.print("[Survival] " + msg);
            sendAlert("\u274C " + Lang.t("No Fireworks", "Plus de fusées"), msg, 0xFF0000);
        }

        // Alert: low elytra durability
        if (elytraDurability > 0 && elytraDurability < 20 && now - lastElytraAlertTime > ALERT_COOLDOWN_MS) {
            lastElytraAlertTime = now;
            String msg = Lang.t(
                    "Elytra durability critical: " + elytraDurability,
                    "Durabilité elytra critique : " + elytraDurability);
            ChatUtils.print("[Survival] " + msg);
            sendAlert("\u26A0\uFE0F " + Lang.t("Elytra Durability", "Durabilité Elytra"), msg, 0xFFA500);
        }

        // Alert: no elytra available
        if (elytras == 0 && elytraDurability <= 0 && now - lastElytraAlertTime > ALERT_COOLDOWN_MS) {
            lastElytraAlertTime = now;
            String msg = Lang.t(
                    "NO ELYTRA! Cannot fly.",
                    "PLUS D'ELYTRA ! Vol impossible.");
            ChatUtils.print("[Survival] " + msg);
            sendAlert("\u274C " + Lang.t("No Elytra", "Plus d'elytra"), msg, 0xFF0000);
        }

        // Auto-disconnect: no usable elytra AND no fireworks = certain death if flying
        boolean noElytra = (elytras == 0 && elytraDurability <= 1);
        boolean noFireworks = (fireworks == 0);

        if (noElytra && noFireworks) {
            String msg = Lang.t(
                    "EMERGENCY DISCONNECT: No elytra and no fireworks! Disconnecting to prevent death.",
                    "DÉCONNEXION D'URGENCE : Plus d'elytra ni de fusées ! Déconnexion pour éviter la mort.");
            ChatUtils.print("[Survival] " + msg);
            sendAlert("\uD83D\uDED1 " + Lang.t("Emergency Disconnect", "Déconnexion d'urgence"), msg, 0xFF0000);

            // Disconnect
            try {
                Connection connection = mc.getConnection() != null ? mc.getConnection().getConnection() : null;
                if (connection != null) {
                    connection.disconnect(Component.literal("[BaseFinder] " + Lang.t("No equipment - safety disconnect", "Plus d'équipement - déconnexion de sécurité")));
                }
            } catch (Exception e) {
                LOGGER.error("[Survival] Failed to disconnect: {}", e.getMessage());
            }
            return true;
        }

        // Also disconnect if only one of the two is missing and we're actively flying
        if ((noElytra || noFireworks) && elytraBot.isFlying()) {
            String reason = noElytra
                    ? Lang.t("No elytra while flying", "Plus d'elytra en vol")
                    : Lang.t("No fireworks while flying", "Plus de fusées en vol");
            String msg = Lang.t(
                    "EMERGENCY DISCONNECT: " + reason + "! Disconnecting to prevent death.",
                    "DÉCONNEXION D'URGENCE : " + reason + " ! Déconnexion pour éviter la mort.");
            ChatUtils.print("[Survival] " + msg);
            sendAlert("\uD83D\uDED1 " + Lang.t("Emergency Disconnect", "Déconnexion d'urgence"), msg, 0xFF0000);

            try {
                Connection connection = mc.getConnection() != null ? mc.getConnection().getConnection() : null;
                if (connection != null) {
                    connection.disconnect(Component.literal("[BaseFinder] " + reason));
                }
            } catch (Exception e) {
                LOGGER.error("[Survival] Failed to disconnect: {}", e.getMessage());
            }
            return true;
        }

        return false;
    }

    private void checkHealth() {
        if (mc.player == null) return;

        int health = (int) mc.player.getHealth();
        long now = System.currentTimeMillis();

        // Health dropped significantly
        if (lastHealth > 0 && health < lastHealth - 4) {
            String msg = Lang.t(
                    "Health dropped! " + health + " HP (was " + lastHealth + ")",
                    "Santé en baisse ! " + health + " PV (était " + lastHealth + ")");
            ChatUtils.print("[Survival] " + msg);
            if (now - lastHealthAlertTime > ALERT_COOLDOWN_MS) {
                lastHealthAlertTime = now;
                sendAlert("\uD83D\uDC94 " + Lang.t("Health Drop", "Perte de vie"),
                        msg, 0xFF4444);
            }
        }

        // Critical health
        if (health < healthThreshold && health > 0) {
            if (!emergencyLanding) {
                emergencyLanding = true;
                String msg = Lang.t(
                        "CRITICAL HEALTH (" + health + " HP)! Emergency actions...",
                        "SANTÉ CRITIQUE (" + health + " PV) ! Actions d'urgence...");
                ChatUtils.print("[Survival] " + msg);
                if (now - lastHealthAlertTime > ALERT_COOLDOWN_MS) {
                    lastHealthAlertTime = now;
                    sendAlert("\u2764\uFE0F\u200D\uD83E\uDE79 " + Lang.t("Critical Health", "Santé critique"),
                            msg, 0xFF0000);
                }
            }
        } else {
            emergencyLanding = false;
        }

        lastHealth = health;
    }

    private void sendAlert(String title, String description, int color) {
        if (discordNotifier != null && discordNotifier.isEnabled()) {
            discordNotifier.notifyAlert(title, description, color);
        }
    }

    /**
     * Stop all survival subsystems and release resources.
     * Call on module disable.
     */
    public void stop() {
        autoEat.stop();
        emergencyLanding = false;
    }

    // Public API
    public AutoTotem getAutoTotem() { return autoTotem; }
    public AutoEat getAutoEat() { return autoEat; }
    public PlayerDetector getPlayerDetector() { return playerDetector; }
    public FireworkResupply getFireworkResupply() { return fireworkResupply; }

    public boolean isEmergencyLanding() { return emergencyLanding; }
    public boolean needsFireworkResupply() { return fireworkResupply.needsResupply(); }
    public boolean isResupplying() { return fireworkResupply.isResupplying(); }

    // External references
    public void setDiscordNotifier(DiscordNotifier notifier) { this.discordNotifier = notifier; }
    public void setElytraBot(ElytraBot bot) { this.elytraBot = bot; }

    // Settings
    public void setEnableAutoTotem(boolean v) { this.enableAutoTotem = v; }
    public void setEnableAutoEat(boolean v) { this.enableAutoEat = v; }
    public void setEnablePlayerDetection(boolean v) { this.enablePlayerDetection = v; }
    public void setEnableFireworkResupply(boolean v) { this.enableFireworkResupply = v; }
    public void setEnableAutoDisconnect(boolean v) { this.enableAutoDisconnect = v; }
    public void setHealthThreshold(int threshold) { this.healthThreshold = threshold; }
    public void setPlayerDetectRange(double range) { playerDetector.setDetectRange(range); }
    public void setResupplyThreshold(int threshold) { fireworkResupply.setResupplyThreshold(threshold); }

    // Stats
    public long getUptimeSeconds() { return previousUptime + (System.currentTimeMillis() - startTime) / 1000; }
    public void setPreviousUptime(long seconds) { this.previousUptime = seconds; }
    public int getDisconnectCount() { return disconnectCount; }
    public int getTotemCount() { return autoTotem.getTotemCount(); }
}
