package com.basefinder.modules;

import com.basefinder.elytra.ElytraBot;
import net.minecraft.core.BlockPos;
import org.rusherhack.client.api.events.client.EventUpdate;
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

    private final ElytraBot elytraBot = new ElytraBot();

    private final NumberSetting<Integer> targetX = new NumberSetting<>("Cible X", 0, -30000000, 30000000);
    private final NumberSetting<Integer> targetZ = new NumberSetting<>("Cible Z", 0, -30000000, 30000000);
    private final NumberSetting<Double> cruiseAltitude = new NumberSetting<>("Altitude croisière", 200.0, 50.0, 350.0);
    private final NumberSetting<Double> minAltitude = new NumberSetting<>("Altitude minimum", 100.0, 30.0, 200.0);
    private final NumberSetting<Integer> fireworkInterval = new NumberSetting<>("Intervalle fusées", 40, 10, 100);
    private final NumberSetting<Integer> minDurability = new NumberSetting<>("Durabilité min. elytra", 10, 1, 100);

    public ElytraBotModule() {
        super("ElytraBot", "Vol elytra automatique vers des coordonnées", ModuleCategory.EXTERNAL);

        this.registerSettings(
                targetX,
                targetZ,
                cruiseAltitude,
                minAltitude,
                fireworkInterval,
                minDurability
        );
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) {
            ChatUtils.print("[ElytraBot] Vous devez être dans un monde !");
            this.toggle();
            return;
        }

        // Vérifier si on porte un elytra
        var chest = mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
        if (!chest.is(net.minecraft.world.item.Items.ELYTRA)) {
            ChatUtils.print("[ElytraBot] ERREUR : Vous devez porter un Elytra !");
            this.toggle();
            return;
        }

        // Vérifier si la cible est définie
        if (targetX.getValue() == 0 && targetZ.getValue() == 0) {
            ChatUtils.print("[ElytraBot] ERREUR : Définissez Cible X et Cible Z d'abord !");
            this.toggle();
            return;
        }

        // Vérifier les fusées
        int fireworks = elytraBot.getFireworkCount();
        if (fireworks == 0) {
            ChatUtils.print("[ElytraBot] ATTENTION : Aucune fusée trouvée ! Vous avez besoin de fusées pour voler.");
        } else {
            ChatUtils.print("[ElytraBot] " + fireworks + " fusées trouvées.");
        }

        // Afficher le nombre d'elytra et la durabilité
        int elytraCount = elytraBot.getElytraCount();
        int durability = elytraBot.getEquippedElytraDurability();
        ChatUtils.print("[ElytraBot] Elytra : " + elytraCount + " utilisables | Durabilité actuelle : " + durability);
        if (elytraCount > 1) {
            ChatUtils.print("[ElytraBot] Échange auto activé (échange à " + minDurability.getValue() + " de durabilité)");
        }

        elytraBot.setCruiseAltitude(cruiseAltitude.getValue());
        elytraBot.setMinAltitude(minAltitude.getValue());
        elytraBot.setFireworkInterval(fireworkInterval.getValue());
        elytraBot.setMinElytraDurability(minDurability.getValue());

        BlockPos target = new BlockPos(targetX.getValue(), 200, targetZ.getValue());
        elytraBot.startFlight(target);

        double distance = Math.sqrt(
            Math.pow(mc.player.getX() - targetX.getValue(), 2) +
            Math.pow(mc.player.getZ() - targetZ.getValue(), 2)
        );
        ChatUtils.print(String.format("[ElytraBot] Vol vers %d, %d (%.0f blocs)",
            targetX.getValue(), targetZ.getValue(), distance));
        ChatUtils.print("[ElytraBot] Sautez pour décoller !");
    }

    @Override
    public void onDisable() {
        elytraBot.stop();
        if (mc.level != null) {
            ChatUtils.print("[ElytraBot] Arrêté.");
        }
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        elytraBot.tick();

        // Vérifier si arrivé
        double dist = elytraBot.getDistanceToDestination();
        if (dist >= 0 && dist < 50) {
            ChatUtils.print("[ElytraBot] Arrivé à destination !");
            this.toggle();
        }

        // Vérifier si plus de fusées
        if (!elytraBot.isFlying() && mc.player.onGround() && elytraBot.getFireworkCount() == 0) {
            ChatUtils.print("[ElytraBot] Plus de fusées. Arrêt.");
            this.toggle();
            return;
        }

        // Vérifier si atterri après urgence (plus d'elytra)
        if (!elytraBot.isFlying() && mc.player.onGround() && elytraBot.getElytraCount() == 0) {
            ChatUtils.print("[ElytraBot] Plus d'elytra. Arrêté.");
            this.toggle();
        }
    }

    public ElytraBot getElytraBot() {
        return elytraBot;
    }
}
