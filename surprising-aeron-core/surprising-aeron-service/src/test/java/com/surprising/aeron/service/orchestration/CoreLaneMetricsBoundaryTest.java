package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.*;
import com.surprising.aeron.service.state.model.CoreRiskStatus;
import com.surprising.aeron.service.state.risk.RiskSnapshotRuntime;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CoreLaneMetricsBoundaryTest {
    private static final long TIME = 1_700_000_000_000L;

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void metricsCannotEnterAccountScopeOrCollectAnUnfinishedRiskDeletion() throws Exception {
        var release = new CountDownLatch(1);
        try (var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            var register = new CoreMessage(CoreMessageHeader.command(CoreMessageType.REGISTER_INSTRUMENT,
                    new UUID(990, 1), core.productLine, CommandSource.OPERATIONS, 990, 1, 0, TIME, 1),
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                            "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0)));
            assertThat(CoreTestCompletion.applyAsynchronously(core, register).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            int symbol = core.identities.findSymbolId("1");
            var instrument = core.runtimeState.instrument("1");
            long key = core.identities.positionKey(1, "1");
            long mask = core.runtimeState.topology().accountLaneMask(1);
            int laneId = Long.numberOfTrailingZeros(mask);
            var changedField = TradingRuntimeState.class.getDeclaredField("changedRiskSnapshots");
            changedField.setAccessible(true);
            var changed = changedField.get(core.runtimeState);
            var isEmpty = changed.getClass().getSuperclass().getDeclaredMethod("isEmpty");
            isEmpty.setAccessible(true);
            var entered = new CountDownLatch(1);
            core.runtimeState.dispatchControlLanes(mask, actualLane -> {
                core.runtimeState.putRiskSnapshot(key, new RiskSnapshotRuntime(1, symbol, CorePositionSide.NET,
                        1, 100, 0, 1, 1, CoreRiskStatus.NORMAL));
                core.runtimeState.removeRiskSnapshot(key);
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("control Lane timeout"); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                return null;
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // 控制任务仍持有Lane作用域；排队一项查询以验证深度不再恒为零。
            assertThat(core.runtimeState.dispatchAccountQuery(mask, ignored -> {})).isTrue();
            long handoff = core.runtimeState.laneHandoffEpoch();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat((Boolean) isEmpty.invoke(changed)).isTrue();
                var request = new CoreMessage(CoreMessageHeader.query(CoreMessageType.LANE_METRICS_QUERY,
                        new UUID(991, 1), core.productLine, CommandSource.OPERATIONS, 991, 1, 0, TIME, 2), new byte[0]);
                assertThat(core.requiresOwnerLaneAccessForPreparation(request)).isFalse();
                var response = core.apply(request);
                assertThat(response.status()).isEqualTo(ResponseStatus.OK);
                assertThat(release.getCount()).isOne();
                var metrics = CoreLaneMetricsCodec.decode(response.data());
                assertThat(metrics.accountLaneQueueDepths()[laneId]).isPositive();
                assertThat(metrics.accountLaneQueueHighWaterMarks()[laneId]).isGreaterThanOrEqualTo(metrics.accountLaneQueueDepths()[laneId]);
                assertThat(metrics.accountLaneQueueCapacities()[laneId]).isEqualTo(
                        core.runtimeState.topology().accountLaneQueueCapacity() * (2 + core.runtimeState.topology().matchingEngineCount()));
                assertThat((Boolean) isEmpty.invoke(changed)).isTrue();
                assertThat(core.runtimeState.currentLaneCommandScope()).isNull();
                assertThat(core.runtimeState.pollControlLanes()).isFalse();
                assertThat(core.identities.findPositionKeyValue(1, instrument, CorePositionSide.NET)).isEqualTo(key);
                assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!core.runtimeState.pollControlLanes()) {
                    if (System.nanoTime() >= deadline) throw new AssertionError("control completion timeout");
                    Thread.onSpinWait();
                }
                assertThat((Boolean) isEmpty.invoke(changed)).isFalse();
                core.runtimeState.clearCommittedChanges(core.identities);
                assertThat(core.identities.findPositionKeyValue(1, instrument, CorePositionSide.NET)).isZero();
            }
            core.assertClusterCallbackComplete();
        } finally { release.countDown(); }
    }
}
