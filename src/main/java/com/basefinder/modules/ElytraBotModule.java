package com.basefinder.modules;

import com.basefinder.elytra.ElytraBot;
import net.minecraft.core.BlockPos;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.core.event.stage.Stage;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.NumberSetting;

/**
 * Standalone elytra flight module.
 * Can be used independently of BaseFinder to fly to specific coordinates.
 */
public class ElytraBotModule extends ToggleableModule {

    private final ElytraBot elytraBot = new ElytraBot();

    private final NumberSetting<Integer> targetX = new NumberSetting<>("Target X", 0, -30000000, 30000000);
    private final NumberSetting<Integer> targetZ = new NumberSetting<>("Target Z", 0, -30000000, 30000000);
    private final NumberSetting<Double> cruiseAltitude = new NumberSetting<>("Cruise Altitude", 200.0, 50.0, 350.0);
    private final NumberSetting<Double> minAltitude = new NumberSetting<>("Min Altitude", 100.0, 30.0, 200.0);
    private final NumberSetting<Integer> fireworkInterval = new NumberSetting<>("Firework Interval", 40, 10, 100);

    public ElytraBotModule() {
        super("ElytraBot", "Automated elytra flight to coordinates", ModuleCategory.MOVEMENT);

        this.registerSettings(
                targetX,
                targetZ,
                cruiseAltitude,
                minAltitude,
                fireworkInterval
        );
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) {
            ChatUtils.print("[ElytraBot] Must be in a world!");
            this.toggle();
            return;
        }

        elytraBot.setCruiseAltitude(cruiseAltitude.getValue());
        elytraBot.setMinAltitude(minAltitude.getValue());
        elytraBot.setFireworkInterval(fireworkInterval.getValue());

        BlockPos target = new BlockPos(targetX.getValue(), 200, targetZ.getValue());
        elytraBot.startFlight(target);

        ChatUtils.print(String.format("[ElytraBot] Flying to %d, %d", targetX.getValue(), targetZ.getValue()));
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
        if (event.getStage() != Stage.PRE) return;
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
        }
    }

    public ElytraBot getElytraBot() {
        return elytraBot;
    }
}
