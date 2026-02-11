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

    private final NumberSetting<Integer> targetX = new NumberSetting<>("Target X", 0, -30000000, 30000000);
    private final NumberSetting<Integer> targetZ = new NumberSetting<>("Target Z", 0, -30000000, 30000000);
    private final NumberSetting<Double> cruiseAltitude = new NumberSetting<>("Cruise Altitude", 200.0, 50.0, 350.0);
    private final NumberSetting<Double> minAltitude = new NumberSetting<>("Min Altitude", 100.0, 30.0, 200.0);
    private final NumberSetting<Integer> fireworkInterval = new NumberSetting<>("Firework Interval", 40, 10, 100);
    private final NumberSetting<Integer> minDurability = new NumberSetting<>("Min Elytra Durability", 10, 1, 100);

    public ElytraBotModule() {
        super("ElytraBot", "Automated elytra flight to coordinates", ModuleCategory.EXTERNAL);

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
            ChatUtils.print("[ElytraBot] Must be in a world!");
            this.toggle();
            return;
        }

        // Check if wearing elytra
        var chest = mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
        if (!chest.is(net.minecraft.world.item.Items.ELYTRA)) {
            ChatUtils.print("[ElytraBot] ERROR: You must wear an Elytra!");
            this.toggle();
            return;
        }

        // Check if target is set (not 0,0)
        if (targetX.getValue() == 0 && targetZ.getValue() == 0) {
            ChatUtils.print("[ElytraBot] ERROR: Set Target X and Target Z first!");
            this.toggle();
            return;
        }

        // Check for fireworks
        int fireworks = elytraBot.getFireworkCount();
        if (fireworks == 0) {
            ChatUtils.print("[ElytraBot] WARNING: No fireworks found! You need fireworks to fly.");
        } else {
            ChatUtils.print("[ElytraBot] Found " + fireworks + " fireworks.");
        }

        // Show elytra count and durability info
        int elytraCount = elytraBot.getElytraCount();
        int durability = elytraBot.getEquippedElytraDurability();
        ChatUtils.print("[ElytraBot] Elytra: " + elytraCount + " usable | Current durability: " + durability);
        if (elytraCount > 1) {
            ChatUtils.print("[ElytraBot] Auto-swap enabled (swap at " + minDurability.getValue() + " durability)");
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
        ChatUtils.print(String.format("[ElytraBot] Flying to %d, %d (%.0f blocks away)",
            targetX.getValue(), targetZ.getValue(), distance));
        ChatUtils.print("[ElytraBot] Jump to take off!");
    }

    @Override
    public void onDisable() {
        elytraBot.stop();
        if (mc.level != null) {
            ChatUtils.print("[ElytraBot] Stopped.");
        }
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        elytraBot.tick();

        // Check if arrived
        double dist = elytraBot.getDistanceToDestination();
        if (dist >= 0 && dist < 50) {
            ChatUtils.print("[ElytraBot] Arrived at destination!");
            this.toggle();
        }

        // Check if we ran out of fireworks and are on ground
        if (!elytraBot.isFlying() && mc.player.onGround() && elytraBot.getFireworkCount() == 0) {
            ChatUtils.print("[ElytraBot] No fireworks remaining. Stopping.");
            this.toggle();
            return;
        }

        // Check if landed after emergency (no elytra left)
        if (!elytraBot.isFlying() && mc.player.onGround() && elytraBot.getElytraCount() == 0) {
            ChatUtils.print("[ElytraBot] No elytra remaining. Stopped.");
            this.toggle();
        }
    }

    public ElytraBot getElytraBot() {
        return elytraBot;
    }
}
