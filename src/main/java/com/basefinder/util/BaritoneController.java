package com.basefinder.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Wrapper around the Baritone API for landing delegation.
 * Baritone is bundled with RusherHack and available at runtime.
 *
 * Uses reflection to access Baritone API to avoid hard compile dependency
 * in case Baritone is not present.
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
    private java.lang.reflect.Method customGoalProcessMethod = null;
    private java.lang.reflect.Method isActiveMethod = null;
    private java.lang.reflect.Method cancelMethod = null;

    public BaritoneController() {
        initBaritone();
    }

    /**
     * Try to access Baritone API via reflection.
     * Baritone is bundled with RusherHack, so it should be available.
     */
    private void initBaritone() {
        try {
            // Try to find the Baritone API class
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
            LOGGER.warn("[BaritoneController] Baritone API not found on classpath - landing delegation disabled");
            available = false;
        } catch (Exception e) {
            LOGGER.warn("[BaritoneController] Failed to initialize Baritone: {}", e.getMessage());
            available = false;
        }
    }

    /**
     * Check if Baritone is available and loaded.
     */
    public boolean isAvailable() {
        return available && baritoneInstance != null;
    }

    /**
     * Configure Baritone for fast landing (accept some fall damage).
     */
    public void configureForFastLanding() {
        if (!available || baritoneInstance == null) return;

        try {
            // Access Baritone settings
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            java.lang.reflect.Method getSettings = apiClass.getMethod("getSettings");
            Object settings = getSettings.invoke(null);

            // Set allowDiagonalDescend = true
            setBaritoneSettingBool(settings, "allowDiagonalDescend", true);

            // Set assumeWalkOnWater = false
            setBaritoneSettingBool(settings, "assumeWalkOnWater", false);

            // Increase max fall height based on accepted damage
            // Fall damage = (distance - 3) half-hearts. So for 3 half-hearts: 6 blocks fall
            int maxFall = Math.max(3, acceptDamage + 3);
            setBaritoneSettingInt(settings, "maxFallHeightNoWater", maxFall);

            LOGGER.info("[BaritoneController] Configured for fast landing: maxFall={}, acceptDamage={}", maxFall, acceptDamage);
        } catch (Exception e) {
            LOGGER.warn("[BaritoneController] Failed to configure settings: {}", e.getMessage());
        }
    }

    /**
     * Command Baritone to navigate to a ground position for landing.
     */
    public void landAt(BlockPos target) {
        if (!available || baritoneInstance == null) return;

        try {
            this.landingTarget = target;
            this.landingActive = true;

            // Get the CustomGoalProcess from Baritone
            java.lang.reflect.Method getCustomGoal = baritoneInstance.getClass().getMethod("getCustomGoalProcess");
            Object goalProcess = getCustomGoal.invoke(baritoneInstance);

            // Create a GoalBlock at the target
            Class<?> goalBlockClass = Class.forName("baritone.api.pathing.goals.GoalBlock");
            Object goal = goalBlockClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(target.getX(), target.getY(), target.getZ());

            // Set the goal and path
            Class<?> goalInterface = Class.forName("baritone.api.pathing.goals.Goal");
            java.lang.reflect.Method setGoalAndPath = goalProcess.getClass().getMethod("setGoalAndPath", goalInterface);
            setGoalAndPath.invoke(goalProcess, goal);

            LOGGER.info("[BaritoneController] Landing goal set at {}", target.toShortString());
        } catch (Exception e) {
            LOGGER.error("[BaritoneController] Failed to set landing goal: {}", e.getMessage());
            landingActive = false;
        }
    }

    /**
     * Check if Baritone has completed the landing (reached goal or is idle).
     */
    public boolean isLandingComplete() {
        if (!landingActive || !available || baritoneInstance == null) return false;

        try {
            // Check if the custom goal process is still active
            java.lang.reflect.Method getCustomGoal = baritoneInstance.getClass().getMethod("getCustomGoalProcess");
            Object goalProcess = getCustomGoal.invoke(baritoneInstance);

            java.lang.reflect.Method isActive = goalProcess.getClass().getMethod("isActive");
            boolean active = (boolean) isActive.invoke(goalProcess);

            if (!active) {
                // Baritone finished (reached goal or gave up)
                landingActive = false;
                return true;
            }

            // Also check if player is close to target and on ground
            if (mc.player != null && landingTarget != null) {
                double dist = mc.player.position().distanceTo(
                        new net.minecraft.world.phys.Vec3(landingTarget.getX(), landingTarget.getY(), landingTarget.getZ()));
                if (dist < 3 && mc.player.onGround()) {
                    cancelLanding();
                    return true;
                }
            }

        } catch (Exception e) {
            LOGGER.warn("[BaritoneController] Error checking landing status: {}", e.getMessage());
            landingActive = false;
            return true; // Assume complete on error
        }

        return false;
    }

    /**
     * Cancel any active Baritone landing process.
     */
    public void cancelLanding() {
        if (!available || baritoneInstance == null) {
            landingActive = false;
            return;
        }

        try {
            java.lang.reflect.Method getCustomGoal = baritoneInstance.getClass().getMethod("getCustomGoalProcess");
            Object goalProcess = getCustomGoal.invoke(baritoneInstance);

            java.lang.reflect.Method isActive = goalProcess.getClass().getMethod("isActive");
            boolean active = (boolean) isActive.invoke(goalProcess);

            if (active) {
                // Cancel the path
                Class<?> iBaritoneProcess = Class.forName("baritone.api.process.IBaritoneProcess");
                // Use the PathingBehavior to cancel
                java.lang.reflect.Method getPathingBehavior = baritoneInstance.getClass().getMethod("getPathingBehavior");
                Object pathingBehavior = getPathingBehavior.invoke(baritoneInstance);

                java.lang.reflect.Method cancelEverything = pathingBehavior.getClass().getMethod("cancelEverything");
                cancelEverything.invoke(pathingBehavior);

                LOGGER.info("[BaritoneController] Landing cancelled");
            }
        } catch (Exception e) {
            LOGGER.warn("[BaritoneController] Error cancelling landing: {}", e.getMessage());
        }

        landingActive = false;
        landingTarget = null;
    }

    /**
     * Set accepted fall damage (in half-hearts).
     */
    public void setAcceptDamage(int halfHearts) {
        this.acceptDamage = Math.max(0, Math.min(20, halfHearts));
    }

    // Helper: set a boolean setting in Baritone via reflection
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

    // Helper: set an int setting in Baritone via reflection
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
