package com.basefinder.application.telemetry;

import com.basefinder.domain.event.BotTick;
import com.basefinder.domain.view.BaseFinderViewModel;

/**
 * Use case appelé périodiquement (≈1 Hz) par le module pour publier un snapshot
 * compact du ViewModel.
 *
 * N'émet rien quand le module est inactif (vm.active() == false).
 */
public final class EmitBotTickUseCase {

    private final TelemetrySink sink;
    private final EventSequenceCounter sequence;

    public EmitBotTickUseCase(TelemetrySink sink, EventSequenceCounter sequence) {
        this.sink = sink;
        this.sequence = sequence;
    }

    public void emit(BaseFinderViewModel vm) {
        if (!vm.active()) {
            return;
        }
        BotTick event = new BotTick(
                sequence.next(),
                System.currentTimeMillis(),
                vm.player().y(),
                vm.player().health(),
                vm.lag().estimatedTps(),
                vm.scan().scannedCount(),
                vm.scan().basesFound(),
                vm.flight().flying(),
                vm.flight().stateName(),
                vm.navigation().waypointIndex(),
                vm.navigation().waypointTotal());
        sink.publish(event);
    }
}
