package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.risk.RiskSnapshotRuntime;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证持仓身份在账户完成后才能退休：风险记录和持仓都已删除才释放，同一轮删除后重新开仓则继续保留，Owner 不跨线程读取账户表。
 */
class OwnerPositionRetirementTest {
    /**
     * 先删除风险并收集提交变化，持仓仍在就保留身份；随后删除实际持仓并完成收集，身份才释放。
     */
    @Test
    void deletingRiskOnlyKeepsPositionIdentityThenActualPositionDeletionReleasesIt() throws Exception {
        try (/* 本场景自动关闭的持仓退休夹具 */ var fixture = new Fixture(); /* 禁止 Owner 直接读账户状态的保护器 */ var guard = new OwnerAccountReadGuard(fixture.runtime)) {
            fixture.applyLane(() -> { fixture.runtime.removeRiskSnapshot(fixture.key); return null; });
            fixture.runtime.clearCommittedChanges(fixture.identities);
            assertThat(fixture.identities.findPositionKey(7, "1")).isEqualTo(fixture.key);
            fixture.applyLane(() -> { fixture.runtime.removePosition(fixture.key, 7); return null; });
            fixture.runtime.clearCommittedChanges(fixture.identities);
            assertThat(fixture.identities.findPositionKey(7, "1")).isNull();
        }
    }

    /**
     * 先删除持仓但保留风险记录，身份继续存在；后续提交再删除风险，身份才能退休。
     */
    @Test
    void riskDeletionReleasesIdentityWhenPositionWasDeletedInPreviousCommit() throws Exception {
        try (/* 本场景自动关闭的持仓退休夹具 */ var fixture = new Fixture(); /* 禁止 Owner 直接读账户状态的保护器 */ var guard = new OwnerAccountReadGuard(fixture.runtime)) {
            fixture.applyLane(() -> { fixture.runtime.removePosition(fixture.key, 7); return null; });
            fixture.runtime.clearCommittedChanges(fixture.identities);
            assertThat(fixture.identities.findPositionKey(7, "1")).isEqualTo(fixture.key);
            fixture.applyLane(() -> { fixture.runtime.removeRiskSnapshot(fixture.key); return null; });
            fixture.runtime.clearCommittedChanges(fixture.identities);
            assertThat(fixture.identities.findPositionKey(7, "1")).isNull();
        }
    }

    /**
     * 同一次 Lane 操作内删除持仓和风险后重新放回持仓，完成证明必须反映重新开仓；最终再删持仓才释放身份。
     */
    @Test
    void riskRemovalProofReflectsReopenedPositionAtLaneCompletion() throws Exception {
        try (/* 本场景自动关闭的持仓退休夹具 */ var fixture = new Fixture(); /* 禁止 Owner 直接读账户状态的保护器 */ var guard = new OwnerAccountReadGuard(fixture.runtime)) {
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

    /**
     * 本组测试共用的真实账户与持仓夹具，持有一个用户的持仓身份及其运行时。
     */
    private static final class Fixture implements AutoCloseable {
        /** Owner 持有的业务身份注册表。 */
        final RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        /** 执行实际 Lane 操作的账户运行时。 */
        final TradingAccountRuntime runtime;
        /** 本夹具持仓的规范身份键。 */
        final long key;
        /** 夹具初始及重新开仓时使用的持仓值。 */
        final PositionRuntime position;

        /**
         * 建立线性永续币对、账户 Lane、一个持仓身份及配对风险记录，清理初始化变化后启动账户工作线程。
         */
        Fixture() {
            // 夹具固定使用的线性永续产品线
            var line = ProductLine.LINEAR_PERPETUAL;
            // 用于建立持仓身份的测试合约
            var instrument = CoreInstrument.from(line, new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                    "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
            // 包含测试合约的初始业务状态
            var initial = new TradingCoreState(line, 0, Map.of(7L, CoreUserState.empty(line, 7)), Map.of(),
                    Map.of("1", instrument), CoreRiskState.empty(), CoreTreasuryState.empty());
            runtime = RuntimeStateProjector.project(initial, identities);
            // 合约注册后的稠密符号编号
            // 结算资产对应的稠密资产编号
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

        /**
         * 把指定状态变化交给用户所属控制 Lane，并等待真实控制回执；本方法不替测试自动收集提交变化。
         * @param mutation 必须在所属 Lane 执行的测试状态变化
         */
        void applyLane(java.util.function.Supplier<Object> mutation) {
            runtime.dispatchControlLanes(runtime.topology().accountLaneMask(7), /* 控制任务传入的 Lane 编号，夹具闭包已经固定目标用户 */ ignored -> mutation.get());
            // 等待控制 Lane 完成的五秒单调截止时间
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!runtime.pollControlLanes()) {
                if (System.nanoTime() >= deadline) throw new AssertionError("Lane deletion timeout");
                Thread.onSpinWait();
            }
        }

        /**
         * 关闭夹具实际账户运行时和所属资源。
         */
        @Override public void close() { runtime.close(); }
    }
}
