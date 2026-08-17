package com.surprising.aeron.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreExportStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdaptiveExportLoopTest {

    @Test
    void drainsActiveBacklogImmediately() throws Exception {
        ArrayDeque<ReliableCoreExporter.ExportCycleResult> results = new ArrayDeque<>(List.of(
                result(2, 1), result(1, 0), result(0, 0)));
        FakeClock clock = new FakeClock();
        AdaptiveExportLoop loop = new AdaptiveExportLoop(results::removeFirst, clock::sleep);

        loop.runOnce();
        loop.runOnce();
        loop.runOnce();

        assertThat(clock.sleeps).containsExactly(25L);
        assertThat(clock.nowMillis).isEqualTo(25L);
    }

    @Test
    void capsIdleAndReconnectPolling() throws Exception {
        FakeClock clock = new FakeClock();
        AdaptiveExportLoop loop = new AdaptiveExportLoop(() -> result(0, 0), clock::sleep);

        for (int cycle = 0; cycle < 9; cycle++) {
            loop.runOnce();
        }

        assertThat(clock.sleeps).containsExactly(25L, 50L, 100L, 200L, 400L, 800L, 1_000L, 1_000L, 1_000L);
        assertThat(AdaptiveExportLoop.nextIdleMillis(1_000L, 1_000L)).isEqualTo(1_000L);
        assertThat(ExporterConfiguration.idleMillis()).isEqualTo(25L);
    }

    private static ReliableCoreExporter.ExportCycleResult result(int published, int pending) {
        return new ReliableCoreExporter.ExportCycleResult(published,
                new CoreExportStatus(0, pending + 1L, pending, pending * 64L, 1_000, 1_000_000));
    }

    private static final class FakeClock {

        private long nowMillis;
        private final List<Long> sleeps = new ArrayList<>();

        private void sleep(long millis) {
            sleeps.add(millis);
            nowMillis += millis;
        }
    }
}
