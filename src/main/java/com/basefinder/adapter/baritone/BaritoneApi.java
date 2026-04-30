package com.basefinder.adapter.baritone;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter typé sur l'API Baritone bundlée par RusherHack.
 *
 * Avant l'étape 6 (audit/05) : reflection ad-hoc disséminée dans BaritoneController
 * (442 lignes), chaque appel répétant {@code getMethod} + {@code invoke} sans
 * cache, exceptions avalées en bloc. NPE silencieux décrits dans BUG-017.
 *
 * Après : tous les {@code Class<?>} et {@code Method} résolus une seule fois au
 * chargement, échec de bootstrap = {@link #isAvailable()} définitivement
 * {@code false} (pas de retry par appel), seule reflection restante = lecture/
 * écriture des Settings via {@link BaritoneSettingsReflection}.
 *
 * Stratégie d'usage côté ElytraBot : ce dernier gère tout le freinage aérien.
 * Quand assez lent et près du sol, on toggle l'elytra OFF puis on délègue la
 * navigation au sol à Baritone via {@link #landAt(BlockPos)}.
 */
public class BaritoneApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("BaritoneApi");

    // ===== Reflection cache (résolu une fois) =====
    private static final boolean BOOTSTRAPPED;

    private static final Class<?> CLS_BARITONE_API;
    private static final Class<?> CLS_GOAL_INTERFACE;
    private static final Class<?> CLS_GOAL_NEAR;
    private static final Class<?> CLS_GOAL_XZ;

    private static final Method M_API_GET_PROVIDER;
    private static final Method M_API_GET_SETTINGS;
    private static final Method M_PROVIDER_GET_PRIMARY;
    private static final Method M_BARITONE_GET_PATHING;
    private static final Method M_BARITONE_GET_CUSTOM_GOAL;
    private static final Method M_BARITONE_GET_COMMAND_MGR;
    private static final Method M_BARITONE_GET_MINE;
    private static final Method M_BARITONE_GET_ELYTRA;
    private static final Method M_PATHING_IS_PATHING;
    private static final Method M_PATHING_CANCEL_EVERYTHING;
    private static final Method M_GOAL_PROCESS_SET_GOAL_AND_PATH;
    private static final Method M_GOAL_PROCESS_IS_ACTIVE;
    private static final Method M_COMMAND_MGR_EXECUTE;
    private static final Method M_MINE_IS_ACTIVE;

    private static final Constructor<?> CTOR_GOAL_NEAR;
    private static final Constructor<?> CTOR_GOAL_XZ;

    static {
        boolean ok = false;
        Class<?> baritoneApi = null;
        Class<?> goalInterface = null;
        Class<?> goalNear = null;
        Class<?> goalXz = null;
        Method apiGetProvider = null;
        Method apiGetSettings = null;
        Method providerGetPrimary = null;
        Method baritoneGetPathing = null;
        Method baritoneGetCustomGoal = null;
        Method baritoneGetCommandMgr = null;
        Method baritoneGetMine = null;
        Method baritoneGetElytra = null;
        Method pathingIsPathing = null;
        Method pathingCancelEverything = null;
        Method goalProcessSetGoalAndPath = null;
        Method goalProcessIsActive = null;
        Method commandMgrExecute = null;
        Method mineIsActive = null;
        Constructor<?> ctorGoalNear = null;
        Constructor<?> ctorGoalXz = null;
        try {
            baritoneApi = Class.forName("baritone.api.BaritoneAPI");
            goalInterface = Class.forName("baritone.api.pathing.goals.Goal");
            goalNear = Class.forName("baritone.api.pathing.goals.GoalNear");
            goalXz = Class.forName("baritone.api.pathing.goals.GoalXZ");

            apiGetProvider = baritoneApi.getMethod("getProvider");
            apiGetSettings = baritoneApi.getMethod("getSettings");
            ctorGoalNear = goalNear.getConstructor(BlockPos.class, int.class);
            ctorGoalXz = goalXz.getConstructor(int.class, int.class);

            ok = true;
        } catch (ClassNotFoundException e) {
            LOGGER.warn("Baritone API not on classpath — controller features disabled");
        } catch (NoSuchMethodException e) {
            LOGGER.warn("Baritone API ABI mismatch — controller features disabled: {}", e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.warn("Baritone API bootstrap failed: {}", e.getMessage());
        }
        BOOTSTRAPPED = ok;
        CLS_BARITONE_API = baritoneApi;
        CLS_GOAL_INTERFACE = goalInterface;
        CLS_GOAL_NEAR = goalNear;
        CLS_GOAL_XZ = goalXz;
        M_API_GET_PROVIDER = apiGetProvider;
        M_API_GET_SETTINGS = apiGetSettings;
        CTOR_GOAL_NEAR = ctorGoalNear;
        CTOR_GOAL_XZ = ctorGoalXz;
        // Les méthodes d'instance ci-dessous sont résolues paresseusement la première
        // fois qu'on a un baritone instance, car elles dépendent des classes runtime.
        M_PROVIDER_GET_PRIMARY = providerGetPrimary;
        M_BARITONE_GET_PATHING = baritoneGetPathing;
        M_BARITONE_GET_CUSTOM_GOAL = baritoneGetCustomGoal;
        M_BARITONE_GET_COMMAND_MGR = baritoneGetCommandMgr;
        M_BARITONE_GET_MINE = baritoneGetMine;
        M_BARITONE_GET_ELYTRA = baritoneGetElytra;
        M_PATHING_IS_PATHING = pathingIsPathing;
        M_PATHING_CANCEL_EVERYTHING = pathingCancelEverything;
        M_GOAL_PROCESS_SET_GOAL_AND_PATH = goalProcessSetGoalAndPath;
        M_GOAL_PROCESS_IS_ACTIVE = goalProcessIsActive;
        M_COMMAND_MGR_EXECUTE = commandMgrExecute;
        M_MINE_IS_ACTIVE = mineIsActive;
    }

    private final Minecraft mc = Minecraft.getInstance();

    private boolean available;
    private boolean elytraAvailable;
    private boolean landingActive;
    private BlockPos landingTarget;
    private int acceptDamage = 3;

    private Object baritoneInstance;

    public BaritoneApi() {
        if (!BOOTSTRAPPED) {
            return;
        }
        try {
            Object provider = M_API_GET_PROVIDER.invoke(null);
            Method getPrimary = provider.getClass().getMethod("getPrimaryBaritone");
            this.baritoneInstance = getPrimary.invoke(provider);
            if (baritoneInstance != null) {
                this.available = true;
                LOGGER.info("Baritone API connected");
                try {
                    baritoneInstance.getClass().getMethod("getElytraProcess");
                    this.elytraAvailable = true;
                } catch (NoSuchMethodException ignored) {
                    this.elytraAvailable = false;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Baritone init failed: {}", e.getMessage());
            this.available = false;
        }
    }

    public boolean isAvailable() {
        return available && baritoneInstance != null;
    }

    public boolean isElytraAvailable() {
        return elytraAvailable && isAvailable();
    }

    /**
     * Toggle elytra OFF en envoyant le packet START_FALL_FLYING (toggle).
     * Préalable à toute prise en main de Baritone au sol.
     */
    public void stopElytra() {
        if (mc.player == null || !mc.player.isFallFlying()) return;
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            LOGGER.info("Elytra flight stopped for ground landing");
        }
    }

    public void configureForFastLanding() {
        if (!isAvailable()) return;
        try {
            Object settings = M_API_GET_SETTINGS.invoke(null);
            BaritoneSettingsReflection.setBool(settings, "allowDiagonalDescend", true);
            BaritoneSettingsReflection.setBool(settings, "allowOvershootDiagonalDescend", true);
            BaritoneSettingsReflection.setBool(settings, "allowParkour", true);
            BaritoneSettingsReflection.setBool(settings, "assumeWalkOnWater", false);
            int maxFall = Math.max(3, acceptDamage + 3);
            BaritoneSettingsReflection.setInt(settings, "maxFallHeightNoWater", maxFall);
            LOGGER.info("Configured: maxFall={}, acceptDamage={}", maxFall, acceptDamage);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Failed to configure settings: {}", e.getMessage());
        }
    }

    /**
     * Configure Baritone pour AutoMending : on veut juste casser les
     * minerais (XP via Mending) sans jamais tenter de ramasser les drops.
     *
     * - {@code mineScanDroppedItems = false} : ne marche pas vers les items au sol
     *   après avoir miné. Sur 2b2t, un drop tombé dans la lave / un trou /
     *   un portail = bot bloqué indéfiniment, et l'inventaire plein causerait
     *   le même blocage.
     * - {@code allowInventory = false} : Baritone ne va pas tenter de réorganiser
     *   l'inventaire (s'arrête de manger / placer pendant le mine).
     */
    public void configureForMendingMine() {
        if (!isAvailable()) return;
        try {
            Object settings = M_API_GET_SETTINGS.invoke(null);
            BaritoneSettingsReflection.setBool(settings, "mineScanDroppedItems", false);
            BaritoneSettingsReflection.setBool(settings, "allowInventory", false);
            LOGGER.info("Configured AutoMending: skip drops, no inventory swaps");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Failed to configure mending settings: {}", e.getMessage());
        }
    }

    public void landAt(BlockPos target) {
        this.landingTarget = target;
        this.landingActive = true;
        setGoalNearInternal(target, 3);
    }

    public boolean isLandingComplete() {
        if (!landingActive || !isAvailable()) return false;
        try {
            Object goalProcess = invokeOnBaritone("getCustomGoalProcess");
            if (goalProcess == null) return false;
            boolean active = (boolean) goalProcess.getClass().getMethod("isActive").invoke(goalProcess);
            if (!active) {
                landingActive = false;
                return true;
            }
            if (mc.player != null && landingTarget != null && mc.player.onGround()) {
                double distXZ = Math.sqrt(
                        Math.pow(mc.player.getX() - landingTarget.getX(), 2) +
                        Math.pow(mc.player.getZ() - landingTarget.getZ(), 2));
                if (distXZ < 5) {
                    cancelLanding();
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Error checking landing status: {}", e.getMessage());
            landingActive = false;
            return true;
        }
        return false;
    }

    public boolean isPathing() {
        if (!isAvailable()) return false;
        try {
            Object pathingBehavior = invokeOnBaritone("getPathingBehavior");
            if (pathingBehavior == null) return false;
            return (boolean) pathingBehavior.getClass().getMethod("isPathing").invoke(pathingBehavior);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public void cancelLanding() {
        if (!isAvailable()) {
            landingActive = false;
            return;
        }
        try {
            Object pathingBehavior = invokeOnBaritone("getPathingBehavior");
            if (pathingBehavior != null) {
                pathingBehavior.getClass().getMethod("cancelEverything").invoke(pathingBehavior);
                LOGGER.info("Navigation cancelled");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Error cancelling: {}", e.getMessage());
        }
        landingActive = false;
        landingTarget = null;
    }

    public void setAcceptDamage(int halfHearts) {
        this.acceptDamage = Math.max(0, Math.min(20, halfHearts));
    }

    public void executeCommand(String command) {
        if (!isAvailable()) {
            LOGGER.warn("Cannot execute command '{}': Baritone not available", command);
            return;
        }
        try {
            Object commandManager = invokeOnBaritone("getCommandManager");
            if (commandManager == null) return;
            commandManager.getClass().getMethod("execute", String.class).invoke(commandManager, command);
            LOGGER.info("Executed command: {}", command);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("Failed to execute command '{}': {}", command, e.getMessage());
        }
    }

    public boolean isMineProcessActive() {
        if (!isAvailable()) return false;
        try {
            Object mineProcess = invokeOnBaritone("getMineProcess");
            if (mineProcess == null) return false;
            return (boolean) mineProcess.getClass().getMethod("isActive").invoke(mineProcess);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public void goToXZ(int x, int z) {
        if (!isAvailable() || CTOR_GOAL_XZ == null) return;
        try {
            Object goal = CTOR_GOAL_XZ.newInstance(x, z);
            setGoal(goal);
            LOGGER.info("GoalXZ set: {}, {}", x, z);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("GoalXZ failed: {}", e.getMessage());
        }
    }

    public void goToNear(BlockPos pos, int range) {
        setGoalNearInternal(pos, range);
        LOGGER.info("GoalNear set: {} range {}", pos.toShortString(), range);
    }

    private void setGoalNearInternal(BlockPos pos, int range) {
        if (!isAvailable() || CTOR_GOAL_NEAR == null) return;
        try {
            Object goal = CTOR_GOAL_NEAR.newInstance(pos, range);
            setGoal(goal);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("GoalNear failed: {}", e.getMessage());
        }
    }

    private void setGoal(Object goal) throws ReflectiveOperationException {
        Object goalProcess = invokeOnBaritone("getCustomGoalProcess");
        if (goalProcess == null) return;
        Method setGoalAndPath = goalProcess.getClass().getMethod("setGoalAndPath", CLS_GOAL_INTERFACE);
        setGoalAndPath.invoke(goalProcess, goal);
    }

    // ===== Elytra (nether-pathfinder) =====

    public boolean elytraTo(int x, int z) {
        if (!isElytraAvailable() || CTOR_GOAL_XZ == null) return false;
        try {
            Object elytraProcess = invokeOnBaritone("getElytraProcess");
            if (elytraProcess == null) return false;
            Object goal = CTOR_GOAL_XZ.newInstance(x, z);
            Method pathTo;
            try {
                pathTo = elytraProcess.getClass().getMethod("pathTo", CLS_GOAL_XZ);
            } catch (NoSuchMethodException e) {
                pathTo = elytraProcess.getClass().getMethod("pathTo", CLS_GOAL_INTERFACE);
            }
            pathTo.invoke(elytraProcess, goal);
            LOGGER.info("Elytra pathTo: {}, {}", x, z);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("elytraTo failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean isElytraFlying() {
        if (!isElytraAvailable()) return false;
        try {
            Object elytraProcess = invokeOnBaritone("getElytraProcess");
            if (elytraProcess == null) return false;
            return (boolean) elytraProcess.getClass().getMethod("isActive").invoke(elytraProcess);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public void cancelElytra() {
        if (!isElytraAvailable()) return;
        try {
            Object elytraProcess = invokeOnBaritone("getElytraProcess");
            if (elytraProcess == null) return;
            try {
                elytraProcess.getClass().getMethod("cancel").invoke(elytraProcess);
                LOGGER.info("Elytra cancelled via cancel()");
                return;
            } catch (NoSuchMethodException ignored) {
                // fallthrough
            }
            cancelAll();
            LOGGER.info("Elytra cancelled via cancelAll fallback");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("cancelElytra failed, using cancelAll: {}", e.getMessage());
            cancelAll();
        }
    }

    public void cancelAll() {
        if (!isAvailable()) return;
        try {
            Object pathingBehavior = invokeOnBaritone("getPathingBehavior");
            if (pathingBehavior != null) {
                pathingBehavior.getClass().getMethod("cancelEverything").invoke(pathingBehavior);
                LOGGER.info("Cancelled all processes");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Failed to cancel all: {}", e.getMessage());
        }
    }

    /** Helper : invoque une méthode sans argument sur l'instance Baritone. */
    private Object invokeOnBaritone(String methodName) throws ReflectiveOperationException {
        return baritoneInstance.getClass().getMethod(methodName).invoke(baritoneInstance);
    }
}
