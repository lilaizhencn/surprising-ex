package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.*;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.model.CoreRiskStatus;
import com.surprising.aeron.service.state.risk.RiskSnapshotRuntime;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class OwnerPositionRetirementTest {
    @Test
    void deletingRiskOnlyKeepsPositionIdentityThenActualPositionDeletionReleasesIt() throws Exception {
        try (var fixture = new Fixture(); var guard = new OwnerAccountReadGuard(fixture.runtime)) {
            fixture.applyLane(() -> { fixture.runtime.removeRiskSnapshot(fixture.key); return null; });
            fixture.runtime.clearCommittedChanges(fixture.identities);
            assertThat(fixture.identities.findPositionKey(7, "1")).isEqualTo(fixture.key);
            fixture.applyLane(() -> { fixture.runtime.removePosition(fixture.key, 7); return null; });
            fixture.runtime.clearCommittedChanges(fixture.identities);
            assertThat(fixture.identities.findPositionKey(7, "1")).isNull();
        }
    }

    @Test
    void riskDeletionReleasesIdentityWhenPositionWasDeletedInPreviousCommit() throws Exception {
        try (var fixture = new Fixture(); var guard = new OwnerAccountReadGuard(fixture.runtime)) {
            fixture.applyLane(() -> { fixture.runtime.removePosition(fixture.key, 7); return null; });
            fixture.runtime.clearCommittedChanges(fixture.identities);
            assertThat(fixture.identities.findPositionKey(7, "1")).isEqualTo(fixture.key);
            fixture.applyLane(() -> { fixture.runtime.removeRiskSnapshot(fixture.key); return null; });
            fixture.runtime.clearCommittedChanges(fixture.identities);
            assertThat(fixture.identities.findPositionKey(7, "1")).isNull();
        }
    }

    @Test
    void riskRemovalProofReflectsReopenedPositionAtLaneCompletion() throws Exception {
        try (var fixture = new Fixture(); var guard = new OwnerAccountReadGuard(fixture.runtime)) {
            fixture.applyLane(() -> {
                fixture.runtime.removePosition(fixture.key, 7);
                fixture.runtime.removeRiskSnapshot(fixture.key);
                fixture.runtime.putPosition(fixture.key, fixture.position);
                return null;
            });
            fixture.runtime.clearCommittedChanges(fixture.identities);
            assertThat(fixture.identities.findPositionKey(7, "1")).isEqualTo(fixture.key);
            fixture.applyLane(() -> { fixture.runtime.removePosition(fixture.key, 7); return null; });
            fixture.runtime.clearCommittedChanges(fixture.identities);
            assertThat(fixture.identities.findPositionKey(7, "1")).isNull();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        final TradingRuntimeState runtime;
        final long key;
        final PositionRuntime position;

        Fixture() {
            var line = ProductLine.LINEAR_PERPETUAL;
            var instrument = CoreInstrument.from(line, new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                    "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
            var initial = new TradingCoreState(line, 0, Map.of(7L, CoreUserState.empty(line, 7)), Map.of(),
                    Map.of("1", instrument), CoreRiskState.empty(), CoreTreasuryState.empty());
            runtime = RuntimeStateProjector.project(initial, identities);
            int symbol = identities.symbolId("1"), asset = identities.assetId("USDT");
            key = identities.positionKey(7, instrument, CorePositionSide.NET);
            position = new PositionRuntime(7, symbol, asset, CoreMarginMode.CROSS, CorePositionSide.NET,
                    instrument, 2, 100, 200, 0, 0);
            runtime.putPosition(key, position);
            runtime.putRiskSnapshot(key, new RiskSnapshotRuntime(7, symbol, CorePositionSide.NET,
                    9, 1_000, 20, 100, 100_000, CoreRiskStatus.NORMAL));
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
        }

        void applyLane(java.util.function.Supplier<Object> mutation) {
            runtime.dispatchControlLanes(runtime.topology().accountLaneMask(7), ignored -> mutation.get());
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!runtime.pollControlLanes()) {
                if (System.nanoTime() >= deadline) throw new AssertionError("Lane deletion timeout");
                Thread.onSpinWait();
            }
        }

        @Override public void close() { runtime.close(); }
    }
}
