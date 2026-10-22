package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.risk.RiskSnapshotRuntime;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 检查运行指标查询不会获取账户写权限、提前收集未完成风险删除或推进持仓身份退休；指标只是观测，不替代业务完成回执。
 */
class CoreLaneMetricsBoundaryTest {
    /** 全场景固定业务时间，避免把系统时钟引入命令裁决。 */
    private static final long TIME = 1_700_000_000_000L;

    /**
     * 阻塞真实 Lane 在风险记录增删之后，查询 LANE_METRICS 并核对积压、容量和高水位；此时 Owner 仍看不到未完成删除。放行并完成回执后才允许收集变化和释放身份。
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void metricsCannotEnterAccountScopeOrCollectAnUnfinishedRiskDeletion() throws Exception {
        // 最终必须放行的账户任务门闩
        var release = new CountDownLatch(1);
        try (/* 运行真实 PIPELINED 工作线程的测试 Core */ var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            // 注册测试合约的复制命令
            var register = new CoreMessage(CoreMessageHeader.command(CoreMessageType.REGISTER_INSTRUMENT,
                    new UUID(990, 1), core.productLine, CommandSource.OPERATIONS, 990, 1, 0, TIME, 1),
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                            "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0)));
            assertThat(CoreTestCompletion.applyAsynchronously(core, register).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            // 测试合约的运行时符号编号
            int symbol = core.identities.findSymbolId("1");
            // 已注册的规范合约定义
            var instrument = core.runtimeState.instrument("1");
            // 本次风险增删共用的规范持仓身份键
            long key = core.identities.positionKey(1, "1");
            // 目标用户所在账户 Lane 的位图
            long mask = core.runtimeState.topology().accountLaneMask(1);
            // 目标用户所属账户 Lane 索引
            int laneId = Long.numberOfTrailingZeros(mask);
            // Owner 风险变化缓冲的反射字段
            var changedField = TradingRuntimeState.class.getDeclaredField("changedRiskSnapshots");
            changedField.setAccessible(true);
            // 实际 Owner 风险变化缓冲，测试检查它何时可见
            var changed = changedField.get(core.runtimeState);
            // 变化缓冲基类的空检查方法，供测试反射断言
            var isEmpty = changed.getClass().getSuperclass().getDeclaredMethod("isEmpty");
            isEmpty.setAccessible(true);
            // 账户工作已改风险状态但尚未完成的门闩
            var entered = new CountDownLatch(1);
            core.runtimeState.dispatchControlLanes(mask, /* 控制任务传入的 Lane 编号，当前夹具使用已固定的运行时 */ actualLane -> {
                core.runtimeState.putRiskSnapshot(key, new RiskSnapshotRuntime(1, symbol, CorePositionSide.NET,
                        1, 100, 0, 1, 1, CoreRiskStatus.NORMAL));
                core.runtimeState.removeRiskSnapshot(key);
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("control Lane timeout"); }
                catch (/* 阻塞账户任务等待时的中断，转换为测试失败 */ InterruptedException failure) { throw new AssertionError(failure); }
                return null;
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // 控制任务仍持有Lane作用域；排队一项查询以验证深度不再恒为零。
            assertThat(core.runtimeState.dispatchAccountQuery(mask, /* 排在阻塞任务后面的只读任务参数，任务本身不读取 */ ignored -> {})).isTrue();
            // 指标查询前保存的账户所有权交接代次
            long handoff = core.runtimeState.laneHandoffEpoch();
            try (/* 禁止 Owner 跨线程读取账户状态的保护器 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat((Boolean) isEmpty.invoke(changed)).isTrue();
                // 不要求 Owner 账户访问权的运行指标查询
                var request = new CoreMessage(CoreMessageHeader.query(CoreMessageType.LANE_METRICS_QUERY,
                        new UUID(991, 1), core.productLine, CommandSource.OPERATIONS, 991, 1, 0, TIME, 2), new byte[0]);
                assertThat(core.requiresOwnerLaneAccessForPreparation(request)).isFalse();
                // 仍在账户任务阻塞时得到的指标终态响应
                var response = core.apply(request);
                assertThat(response.status()).isEqualTo(ResponseStatus.OK);
                assertThat(release.getCount()).isOne();
                // 解码后的运行指标，用于核对 Lane 积压及容量
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
                // 放行后等待真实控制完成的五秒截止时间
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
