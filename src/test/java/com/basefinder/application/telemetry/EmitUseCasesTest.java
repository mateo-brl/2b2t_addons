package com.basefinder.application.telemetry;

import com.basefinder.domain.event.BaseFound;
import com.basefinder.domain.event.BotEvent;
import com.basefinder.domain.event.BotTick;
import com.basefinder.domain.scan.BaseType;
import com.basefinder.domain.view.BaseFinderViewModel;
import com.basefinder.domain.world.ChunkId;
import com.basefinder.domain.world.Dimension;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmitUseCasesTest {

    private static class CapturingSink implements TelemetrySink {
        final List<BotEvent> captured = new ArrayList<>();

        @Override
        public void publish(BotEvent event) {
            captured.add(event);
        }
    }

    @Test
    void emitBaseFound_assemblesEventWithSequenceAndKey() {
        CapturingSink sink = new CapturingSink();
        EventSequenceCounter seq = new EventSequenceCounter();
        EmitBaseFoundUseCase uc = new EmitBaseFoundUseCase(sink, seq);

        uc.emit(new ChunkId(10, 20, Dimension.OVERWORLD),
                BaseType.STORAGE, 1500.0, 168, 64, 320);

        assertEquals(1, sink.captured.size());
        BotEvent e = sink.captured.get(0);
        assertInstanceOf(BaseFound.class, e);
        BaseFound bf = (BaseFound) e;
        assertEquals(0L, bf.seq());
        assertEquals(BaseType.STORAGE, bf.baseType());
        assertEquals(1500.0, bf.score());
        assertEquals("overworld:10:20:STORAGE", bf.idempotencyKey());
    }

    @Test
    void emitBotTick_skipsWhenInactive() {
        CapturingSink sink = new CapturingSink();
        EventSequenceCounter seq = new EventSequenceCounter();
        EmitBotTickUseCase uc = new EmitBotTickUseCase(sink, seq);

        uc.emit(BaseFinderViewModel.INACTIVE);

        assertTrue(sink.captured.isEmpty());
        assertEquals(0L, seq.current(), "Le compteur ne doit pas avancer si rien n'est émis");
    }

    @Test
    void emitBotTick_publishesActiveSnapshot() {
        CapturingSink sink = new CapturingSink();
        EventSequenceCounter seq = new EventSequenceCounter();
        EmitBotTickUseCase uc = new EmitBotTickUseCase(sink, seq);

        BaseFinderViewModel vm = new BaseFinderViewModel(
                true, "SCANNING",
                new BaseFinderViewModel.FlightVm(true, "CRUISING", 1000, 5, false, 0, false),
                null,
                new BaseFinderViewModel.ScanVm(1234, 0, 7),
                new BaseFinderViewModel.NavigationVm(true, 42, 500, 8.4, 0),
                null,
                new BaseFinderViewModel.SurvivalVm(8, 60, false, false),
                new BaseFinderViewModel.LagVm(19.8, false),
                new BaseFinderViewModel.PlayerVm(true, 1500, 200, -2400, "overworld", 18));

        uc.emit(vm);

        assertEquals(1, sink.captured.size());
        BotTick t = (BotTick) sink.captured.get(0);
        assertEquals(0L, t.seq());
        assertEquals(1500, t.posX());
        assertEquals(200, t.posY());
        assertEquals(-2400, t.posZ());
        assertEquals("overworld", t.dimension());
        assertEquals(18, t.hp());
        assertEquals(19.8, t.tps());
        assertEquals(1234, t.scannedChunks());
        assertEquals(7, t.basesFound());
        assertTrue(t.flying());
        assertEquals("CRUISING", t.flightStateName());
        assertEquals(42, t.waypointIndex());
        assertEquals(500, t.waypointTotal());
    }

    @Test
    void sequence_isMonotonicAcrossUseCases() {
        CapturingSink sink = new CapturingSink();
        EventSequenceCounter seq = new EventSequenceCounter();
        EmitBaseFoundUseCase emitBase = new EmitBaseFoundUseCase(sink, seq);
        EmitBotTickUseCase emitTick = new EmitBotTickUseCase(sink, seq);

        BaseFinderViewModel vm = new BaseFinderViewModel(
                true, "SCANNING", BaseFinderViewModel.FlightVm.OFF, null,
                BaseFinderViewModel.ScanVm.EMPTY, BaseFinderViewModel.NavigationVm.EMPTY,
                null, BaseFinderViewModel.SurvivalVm.EMPTY, BaseFinderViewModel.LagVm.OK,
                BaseFinderViewModel.PlayerVm.UNKNOWN);

        emitTick.emit(vm); // seq=0
        emitBase.emit(new ChunkId(0, 0, Dimension.OVERWORLD), BaseType.STORAGE, 100, 0, 0, 0); // seq=1
        emitTick.emit(vm); // seq=2

        assertEquals(0L, sink.captured.get(0).seq());
        assertEquals(1L, sink.captured.get(1).seq());
        assertEquals(2L, sink.captured.get(2).seq());
    }
}
