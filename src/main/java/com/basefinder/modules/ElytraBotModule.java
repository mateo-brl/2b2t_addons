package com.basefinder.modules;

import com.basefinder.bootstrap.ServiceRegistry;
import com.basefinder.elytra.ElytraBot;
import com.basefinder.adapter.baritone.BaritoneApi;
import com.basefinder.util.Lang;
import net.minecraft.core.BlockPos;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.IModule;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;

/**
 * Standalone elytra flight module.
 * Auto-swaps elytra when durability is low. Lands safely when none left.
 */
public class ElytraBotModule extends ToggleableModule {

    private final ElytraBot elytraBot;
    private final BaritoneApi baritoneController;

    private final NumberSetting<Integer> targetX = new NumberSetting<>("Cible X", 0, -30000000, 30000000).incremental(1.0);
    private final NumberSetting<Integer> targetZ = new NumberSetting<>("Cible Z", 0, -30000000, 30000000).incremental(1.0);
    private final NumberSetting<Double> cruiseAltitude = new NumberSetting<>("Altitude croisière", 200.0, 50.0, 350.0);
    private final NumberSetting<Double> minAltitude = new NumberSetting<>("Altitude minimum", 100.0, 30.0, 200.0);
    private final NumberSetting<Integer> fireworkInterval = new NumberSetting<>("Intervalle fusées", 40, 10, 100);
    private final NumberSetting<Integer> minDurability = new NumberSetting<>("Durabilité min. elytra", 10, 1, 100);
    private final BooleanSetting antiKickNoise = new BooleanSetting("Anti-kick bruit", "Petits mouvements aléatoires pour éviter l'AFK kick", true);

    // --- LANGUE / LANGUAGE ---
    private final BooleanSetting langFr = new BooleanSetting("Français", "Interface en français (off = English)", true);

    public ElytraBotModule(ServiceRegistry registry) {
        super("ElytraBot", "Vol elytra automatique vers des coordonnées", ModuleCategory.EXTERNAL);
        this.elytraBot = registry.elytraBot();
        this.baritoneController = registry.baritoneApi();

        this.registerSettings(
                targetX,
                targetZ,
                cruiseAltitude,
                minAltitude,
                fireworkInterval,
                minDurability,
                antiKickNoise,
                langFr
        );
    }

    /**
     * Check if another module using ElytraBot is already active.
     */
    private boolean isElytraBotInUse() {
        for (String name : new String[]{"AutoTravel", "BaseFinder"}) {
            try {
                IModule other = RusherHackAPI.getModuleManager().getFeature(name).orElse(null);
                if (other instanceof ToggleableModule tm && tm != this && tm.isToggled()) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    @Override
    public void onEnable() {
        Lang.setFrench(langFr.getValue());

        if (isElytraBotInUse()) {
            ChatUtils.print("[ElytraBot] " + Lang.t(
                    "ERROR: Another module using ElytraBot is already active! Disable it first.",
                    "ERREUR : Un autre module utilisant ElytraBot est déjà actif ! Désactivez-le d'abord."));
            this.toggle();
            return;
        }

        if (mc.player == null || mc.level == null) {
            ChatUtils.print("[ElytraBot] " + Lang.t("Must be in a world!", "Vous devez être dans un monde !"));
            this.toggle();
            return;
        }

        var chest = mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
        if (!chest.is(net.minecraft.world.item.Items.ELYTRA)) {
            ChatUtils.print("[ElytraBot] " + Lang.t("ERROR: You must wear an Elytra!", "ERREUR : Vous devez porter un Elytra !"));
            this.toggle();
            return;
        }

        if (targetX.getValue() == 0 && targetZ.getValue() == 0) {
            ChatUtils.print("[ElytraBot] " + Lang.t("ERROR: Set Target X and Target Z first!", "ERREUR : Définissez Cible X et Cible Z d'abord !"));
            this.toggle();
            return;
        }

        int fireworks = elytraBot.getFireworkCount();
        if (fireworks == 0) {
            ChatUtils.print("[ElytraBot] " + Lang.t("WARNING: No fireworks found!", "ATTENTION : Aucune fusée trouvée !"));
        } else {
            ChatUtils.print("[ElytraBot] " + fireworks + Lang.t(" fireworks found.", " fusées trouvées."));
        }

        int elytraCount = elytraBot.getElytraCount();
        int durability = elytraBot.getEquippedElytraDurability();
        ChatUtils.print("[ElytraBot] Elytra: " + elytraCount + Lang.t(" usable | Current durability: ", " utilisables | Durabilité actuelle : ") + durability);
        if (elytraCount > 1) {
            ChatUtils.print("[ElytraBot] " + Lang.t("Auto-swap enabled (swap at " + minDurability.getValue() + " durability)", "Échange auto activé (échange à " + minDurability.getValue() + " de durabilité)"));
        }

        elytraBot.setCruiseAltitude(cruiseAltitude.getValue());
        elytraBot.setMinAltitude(minAltitude.getValue());
        elytraBot.setFireworkInterval(fireworkInterval.getValue());
        elytraBot.setMinElytraDurability(minDurability.getValue());
        elytraBot.setUseFlightNoise(antiKickNoise.getValue());

        // Wire up Baritone for landing
        elytraBot.setBaritoneController(baritoneController);
        elytraBot.setUseBaritoneLanding(baritoneController.isAvailable());
        if (baritoneController.isAvailable()) {
            ChatUtils.print("[ElytraBot] " + Lang.t("Baritone connected - smart landing enabled", "Baritone connecté - atterrissage intelligent activé"));
        }

        BlockPos target = new BlockPos(targetX.getValue(), 200, targetZ.getValue());
        elytraBot.startFlight(target);

        double distance = Math.sqrt(
            Math.pow(mc.player.getX() - targetX.getValue(), 2) +
            Math.pow(mc.player.getZ() - targetZ.getValue(), 2)
        );
        ChatUtils.print(String.format("[ElytraBot] " + Lang.t("Flying to %d, %d (%.0f blocks)", "Vol vers %d, %d (%.0f blocs)"),
            targetX.getValue(), targetZ.getValue(), distance));
        ChatUtils.print("[ElytraBot] " + Lang.t("Jump to take off!", "Sautez pour décoller !"));
    }

    @Override
    public void onDisable() {
        elytraBot.stop();
        if (mc.level != null) {
            ChatUtils.print("[ElytraBot] " + Lang.t("Stopped.", "Arrêté."));
        }
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        elytraBot.tick();

        double dist = elytraBot.getDistanceToDestination();
        if (dist >= 0 && dist < 50) {
            ChatUtils.print("[ElytraBot] " + Lang.t("Arrived at destination!", "Arrivé à destination !"));
            this.toggle();
        }

        if (!elytraBot.isFlying() && mc.player.onGround() && elytraBot.getFireworkCount() == 0) {
            ChatUtils.print("[ElytraBot] " + Lang.t("No fireworks remaining. Stopping.", "Plus de fusées. Arrêt."));
            this.toggle();
            return;
        }

        if (!elytraBot.isFlying() && mc.player.onGround() && elytraBot.getElytraCount() == 0) {
            ChatUtils.print("[ElytraBot] " + Lang.t("No elytra remaining. Stopped.", "Plus d'elytra. Arrêté."));
            this.toggle();
        }
    }

    public ElytraBot getElytraBot() {
        return elytraBot;
    }
}
