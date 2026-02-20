package com.basefinder.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

/**
 * Wrapper around the Baritone API for ground-phase landing.
 * Baritone is bundled with RusherHack and available at runtime.
 *
 * Strategy: ElytraBot handles all aerial braking (descent, flare).
 * When slow enough and near ground, elytra is toggled OFF, then
 * Baritone takes over for ground navigation to the exact destination.
 *
 * Uses reflection to avoid hard compile dependency.
 */
public class BaritoneController {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("BaritoneController");
    private final Minecraft mc = Minecraft.getInstance();

    private boolean available = false;
    private boolean landingActive = false;
    private BlockPos landingTarget = null;
    private int acceptDamage = 3; // half-hearts

    // Baritone API references (resolved via reflection)
    private Object baritoneInstance = null;

    public BaritoneController() {
        initBaritone();
    }

    /**
     * Try to access Baritone API via reflection.
     */
    private void initBaritone() {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            java.lang.reflect.Method getProvider = apiClass.getMethod("getProvider");
            Object provider = getProvider.invoke(null);

            java.lang.reflect.Method getPrimary = provider.getClass().getMethod("getPrimaryBaritone");
            baritoneInstance = getPrimary.invoke(provider);

            if (baritoneInstance != null) {
                available = true;
                LOGGER.info("[BaritoneController] Baritone API found and connected");
            }
        } catch (ClassNotFoundException e) {
            LOGGER.warn("[BaritoneController] Baritone API not found - landing delegation disabled");
            available = false;
        } catch (Exception e) {
            LOGGER.warn("[BaritoneController] Failed to initialize Baritone: {}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available && baritoneInstance != null;
    }

    /**
     * Stop elytra flight by sending the toggle packet.
     * Must be called BEFORE handing off to Baritone (Baritone can't pathfind while flying).
     */
    public void stopElytra() {
        if (mc.player == null || !mc.player.isFallFlying()) return;

        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
            ));
            LOGGER.info("[BaritoneController] Elytra flight stopped for ground landing");
        }
    }

    /**
     * Configure Baritone settings for safe ground approach after elytra stop.
     */
    public void configureForFastLanding() {
        if (!available || baritoneInstance == null) return;

        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            java.lang.reflect.Method getSettings = apiClass.getMethod("getSettings");
            Object settings = getSettings.invoke(null);

            // Allow diagonal and parkour movement for faster ground navigation
            setBaritoneSettingBool(settings, "allowDiagonalDescend", true);
            setBaritoneSettingBool(settings, "allowOvershootDiagonalDescend", true);
            setBaritoneSettingBool(settings, "allowParkour", true);
            setBaritoneSettingBool(settings, "assumeWalkOnWater", false);

            // Fall damage = (distance - 3) half-hearts. For 3 half-hearts: 6 blocks fall
            int maxFall = Math.max(3, acceptDamage + 3);
            setBaritoneSettingInt(settings, "maxFallHeightNoWater", maxFall);

            LOGGER.info("[BaritoneController] Configured: maxFall={}, acceptDamage={}", maxFall, acceptDamage);
        } catch (Exception e) {
            LOGGER.warn("[BaritoneController] Failed to configure settings: {}", e.getMessage());
        }
    }

    /**
     * Command Baritone to walk to the landing target.
     * Uses GoalNear with range 3 for tolerance (don't need exact block).
     */
    public void landAt(BlockPos target) {
        if (!available || baritoneInstance == null) return;

        try {
            this.landingTarget = target;
            this.landingActive = true;

            // Get the CustomGoalProcess
            java.lang.reflect.Method getCustomGoal = baritoneInstance.getClass().getMethod("getCustomGoalProcess");
            Object goalProcess = getCustomGoal.invoke(baritoneInstance);

            // Create GoalNear(target, 3) — land within 3 blocks of target
            Class<?> goalNearClass = Class.forName("baritone.api.pathing.goals.GoalNear");
            Object goal = goalNearClass.getConstructor(BlockPos.class, int.class)
                    .newInstance(target, 3);

            // Set the goal and path
            Class<?> goalInterface = Class.forName("baritone.api.pathing.goals.Goal");
            java.lang.reflect.Method setGoalAndPath = goalProcess.getClass().getMethod("setGoalAndPath", goalInterface);
            setGoalAndPath.invoke(goalProcess, goal);

            LOGGER.info("[BaritoneController] Ground navigation goal set near {}", target.toShortString());
        } catch (Exception e) {
            LOGGER.error("[BaritoneController] Failed to set landing goal: {}", e.getMessage());
            landingActive = false;
        }
    }

    /**
     * Check if Baritone has completed ground navigation.
     */
    public boolean isLandingComplete() {
        if (!landingActive || !available || baritoneInstance == null) return false;

        try {
            // Check if custom goal process is still active
            java.lang.reflect.Method getCustomGoal = baritoneInstance.getClass().getMethod("getCustomGoalProcess");
            Object goalProcess = getCustomGoal.invoke(baritoneInstance);
            java.lang.reflect.Method isActive = goalProcess.getClass().getMethod("isActive");
            boolean active = (boolean) isActive.invoke(goalProcess);

            if (!active) {
                landingActive = false;
                return true;
            }

            // Check if player reached target area
            if (mc.player != null && landingTarget != null && mc.player.onGround()) {
                double distXZ = Math.sqrt(
                        Math.pow(mc.player.getX() - landingTarget.getX(), 2) +
                        Math.pow(mc.player.getZ() - landingTarget.getZ(), 2)
                );
                if (distXZ < 5) {
                    cancelLanding();
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[BaritoneController] Error checking status: {}", e.getMessage());
            landingActive = false;
            return true;
        }

        return false;
    }

    /**
     * Check if Baritone is actively pathfinding.
     */
    public boolean isPathing() {
        if (!available || baritoneInstance == null) return false;
        try {
            java.lang.reflect.Method getPathingBehavior = baritoneInstance.getClass().getMethod("getPathingBehavior");
            Object pathingBehavior = getPathingBehavior.invoke(baritoneInstance);
            java.lang.reflect.Method isPathing = pathingBehavior.getClass().getMethod("isPathing");
            return (boolean) isPathing.invoke(pathingBehavior);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Cancel any active Baritone navigation.
     */
    public void cancelLanding() {
        if (!available || baritoneInstance == null) {
            landingActive = false;
            return;
        }

        try {
            java.lang.reflect.Method getPathingBehavior = baritoneInstance.getClass().getMethod("getPathingBehavior");
            Object pathingBehavior = getPathingBehavior.invoke(baritoneInstance);
            java.lang.reflect.Method cancelEverything = pathingBehavior.getClass().getMethod("cancelEverything");
            cancelEverything.invoke(pathingBehavior);
            LOGGER.info("[BaritoneController] Navigation cancelled");
        } catch (Exception e) {
            LOGGER.warn("[BaritoneController] Error cancelling: {}", e.getMessage());
        }

        landingActive = false;
        landingTarget = null;
    }

    public void setAcceptDamage(int halfHearts) {
        this.acceptDamage = Math.max(0, Math.min(20, halfHearts));
    }

    // Reflection helpers for Baritone settings
    private void setBaritoneSettingBool(Object settings, String name, boolean value) {
        try {
            java.lang.reflect.Field field = settings.getClass().getField(name);
            Object setting = field.get(settings);
            java.lang.reflect.Field valueField = setting.getClass().getSuperclass().getDeclaredField("value");
            valueField.setAccessible(true);
            valueField.set(setting, value);
        } catch (Exception e) {
            LOGGER.debug("[BaritoneController] Could not set {}: {}", name, e.getMessage());
        }
    }

    private void setBaritoneSettingInt(Object settings, String name, int value) {
        try {
            java.lang.reflect.Field field = settings.getClass().getField(name);
            Object setting = field.get(settings);
            java.lang.reflect.Field valueField = setting.getClass().getSuperclass().getDeclaredField("value");
            valueField.setAccessible(true);
            valueField.set(setting, value);
        } catch (Exception e) {
            LOGGER.debug("[BaritoneController] Could not set {}: {}", name, e.getMessage());
        }
    }
}
