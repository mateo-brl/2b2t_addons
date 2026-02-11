package com.basefinder.survival;

import com.basefinder.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.rusherhack.client.api.utils.ChatUtils;

/**
 * Detects nearby players and auto-disconnects to avoid being killed.
 * Critical for unattended 24/7 operation on 2b2t.
 */
public class PlayerDetector {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("PlayerDetector");
    private final Minecraft mc = Minecraft.getInstance();

    private double detectRange = 200.0; // blocks
    private int checkInterval = 5; // Check every 0.25 seconds (fast!)
    private int tickCounter = 0;
    private boolean autoDisconnect = true;

    /**
     * Tick the player detector. Returns true if disconnected.
     */
    public boolean tick() {
        if (mc.player == null || mc.level == null) return false;

        tickCounter++;
        if (tickCounter % checkInterval != 0) return false;

        // Scan all players in the level
        for (Player player : mc.level.players()) {
            // Skip self
            if (player == mc.player) continue;

            double distance = mc.player.distanceTo(player);
            if (distance <= detectRange) {
                String playerName = player.getName().getString();
                double x = player.getX();
                double z = player.getZ();

                LOGGER.warn("[PlayerDetector] Player detected: {} at distance {} ({}, {})",
                        playerName, (int) distance, (int) x, (int) z);
                ChatUtils.print("[Survival] " + Lang.t(
                        "PLAYER DETECTED: " + playerName + " at " + (int) distance + " blocks!",
                        "JOUEUR DÉTECTÉ : " + playerName + " à " + (int) distance + " blocs !"));

                if (autoDisconnect) {
                    disconnect(playerName, distance);
                    return true;
                }
            }
        }
        return false;
    }

    private void disconnect(String playerName, double distance) {
        LOGGER.warn("[PlayerDetector] Auto-disconnecting due to player: {} (dist: {})", playerName, (int) distance);

        ClientPacketListener connection = mc.getConnection();
        if (connection != null) {
            connection.getConnection().disconnect(
                    Component.literal("[BaseFinder] Player detected: " + playerName
                            + " (" + (int) distance + "m)")
            );
        }
    }

    public void setDetectRange(double range) { this.detectRange = range; }
    public void setAutoDisconnect(boolean v) { this.autoDisconnect = v; }
    public double getDetectRange() { return detectRange; }
}
