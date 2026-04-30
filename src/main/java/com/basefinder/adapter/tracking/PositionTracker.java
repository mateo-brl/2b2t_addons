package com.basefinder.adapter.tracking;

import com.basefinder.application.telemetry.EmitBotTickUseCase;
import com.basefinder.domain.view.BaseFinderViewModel;
import net.minecraft.client.Minecraft;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.core.event.subscribe.Subscribe;

/**
 * Émet un BotTick minimal à 1 Hz tant que le joueur est en jeu, indépendamment
 * de l'état du module BaseHunter. Permet au dashboard d'afficher la position
 * du joueur même si BaseHunter n'est pas activé.
 *
 * Quand BaseHunter EST toggled, ce tracker s'efface — le module a son propre
 * emit avec un snapshot complet (flight, scan, navigation, etc.). On évite
 * ainsi le double-emit.
 *
 * Branché sur l'EventBus dans {@code BaseFinderPlugin.onLoad()}.
 */
public final class PositionTracker {

    private final EmitBotTickUseCase emit;
    private final Minecraft mc = Minecraft.getInstance();
    private int tickCounter = 0;

    public PositionTracker(EmitBotTickUseCase emit) {
        this.emit = emit;
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        tickCounter++;
        if (tickCounter % 20 != 0) return; // ~1 Hz
        if (mc.player == null || mc.level == null) return;
        if (isBaseFinderActive()) return; // BaseFinder émet déjà un snapshot complet

        String dim = currentDim();
        BaseFinderViewModel vm = new BaseFinderViewModel(
                true,
                "TRACKING",
                BaseFinderViewModel.FlightVm.OFF,
                null,
                BaseFinderViewModel.ScanVm.EMPTY,
                BaseFinderViewModel.NavigationVm.EMPTY,
                null,
                BaseFinderViewModel.SurvivalVm.EMPTY,
                BaseFinderViewModel.LagVm.OK,
                new BaseFinderViewModel.PlayerVm(
                        true,
                        (int) mc.player.getX(),
                        (int) mc.player.getY(),
                        (int) mc.player.getZ(),
                        dim,
                        (int) mc.player.getHealth()));
        emit.emit(vm);
    }

    private boolean isBaseFinderActive() {
        try {
            return RusherHackAPI.getModuleManager().getFeature("BaseHunter")
                    .map(m -> m instanceof ToggleableModule tm && tm.isToggled())
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private String currentDim() {
        if (mc.level == null) return "overworld";
        String location = mc.level.dimension().location().toString();
        return switch (location) {
            case "minecraft:the_nether" -> "nether";
            case "minecraft:the_end" -> "end";
            default -> "overworld";
        };
    }
}
