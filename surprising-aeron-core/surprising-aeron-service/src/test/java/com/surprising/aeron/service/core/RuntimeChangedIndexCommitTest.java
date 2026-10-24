package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.core.index.AdlPositionIndex;
import com.surprising.aeron.service.core.index.AlgoOrderIndex;
import com.surprising.aeron.service.core.index.CancelAllAfterIndex;
import com.surprising.aeron.service.core.index.LiquidationIndex;
import com.surprising.aeron.service.core.index.OpenInterestIndex;
import com.surprising.aeron.service.core.index.PositionUserIndex;
import com.surprising.aeron.service.core.index.RuntimeFactIndexes;
import com.surprising.aeron.service.core.index.TriggerOrderIndex;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.risk.*;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import com.surprising.aeron.service.risk.RiskSnapshotRuntime;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检查直接变更 ID 和 Lane 冻结发布值如何更新 Owner 及持仓派生索引。零数量持仓可以仍保留用户路由，只有真正删除持仓才移除路由；身份回收在已提交变化清理后检查。
 */
class RuntimeChangedIndexCommitTest {

    /**
     * 依次把净仓设为二、负二、零、三、零，验证零总量不出现在未平仓量列表，但持仓对象仍在时用户路由保留；显式移除持仓后路由才清空。
     */
    @Test
    void closeReopenAndPublicationRecyclingKeepZeroTotalsOutOfQueries() {
        // 本用例 U 本位永续的空冷状态
        var initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        // 原始身份表，供内部编号转换和最终身份回收断言
        var identities = new RuntimeIdentityRegistry();
        try (/* 本用例由冷状态投影的交易运行时，真实 Lane 用例会显式启动账户资源 */ var runtime = RuntimeStateProjector.project(initial, identities)) {
            // USDT 的内部资产编号
            // 永久标的一的内部稠密编号
            int symbol = identities.symbolId("1"), asset = identities.assetId("USDT");
            // 用户七净仓的确定性持仓键
            long key = identities.positionKey(7, "1:NET");
            // 四项可断言索引及共同事实应用入口组成的测试记录
            var indexes = indexes(initial, identities);
            for (long /* 当前循环赋给同一持仓的带符号数量，零不等于删除对象 */ quantity : new long[]{2, -2, 0, 3, 0}) {
                runtime.putPosition(key, new PositionRuntime(7, symbol, asset, CoreMarginMode.CROSS,
                        CorePositionSide.NET, CoreStateTestFixtures.runtimeInstrument(), quantity,
                        quantity == 0 ? 0 : 100, Math.abs(quantity) * 100, 0, Math.abs(quantity) * 10));
                indexes.coordinator.applyCurrent(runtime, identities);
                runtime.clearChangedKeys();
                assertThat(indexes.openInterest.openInterestSteps("1")).isEqualTo(Math.abs(quantity));
                if (quantity == 0) assertThat(indexes.openInterest.totals()).isEmpty();
                else assertThat(indexes.openInterest.totals()).containsExactly(org.assertj.core.api.Assertions.entry(
                        "1", new OpenInterestIndex.Totals(Math.max(quantity, 0), Math.max(-quantity, 0))));
                assertThat(indexes.positionUsers.usersAfter("1", 0)).containsExactly(7L);
            }
            runtime.removePosition(key, 7);
            indexes.coordinator.applyCurrent(runtime, identities);
            assertThat(indexes.openInterest.totals()).isEmpty();
            assertThat(indexes.positionUsers.usersAfter("1", 0)).isEmpty();
        }
    }

    /**
     * 直接修改冷运行时订单与持仓，沿变更 ID 应用索引而不构造事实帧；检查新增、数量变化和删除，并核对账户准入数量。
     */
    @Test
    void appliesAuthoritativeChangedIdsWithoutFactFrame() {
        // 本用例 U 本位永续的空冷状态
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        // 原始身份表，供内部编号转换和最终身份回收断言
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 本用例由冷状态投影的交易运行时，真实 Lane 用例会显式启动账户资源
        TradingAccountRuntime runtime = RuntimeStateProjector.project(initial, identities);
        // 永久标的一对应内部稠密标的编号
        int symbolId = identities.symbolId("1");
        // USDT 内部资产编号
        int assetId = identities.assetId("USDT");
        // 本用例持仓和风险快照共用的确定性身份键
        long positionKey = identities.positionKey(7, "1:NET");
        runtime.putPosition(positionKey, new PositionRuntime(7, symbolId, assetId,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreStateTestFixtures.runtimeInstrument(),
                2, 100, 200, 0, 40));
        runtime.putOrder(CoreStateTestFixtures.order(11, 7, symbolId, 2));

        // 四项可断言索引及共同事实应用入口组成的测试记录
        IndexSet indexes = indexes(initial, identities);
        if (runtime.accountLanesStarted()) LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
        indexes.coordinator.applyCurrent(runtime, identities);

        assertThat(indexes.activeOrders.page(0, null, 0, 1024).orderIds()).containsExactly(11L);
        assertThat(indexes.positionUsers.usersAfter("1", 0)).containsExactly(7L);
        assertThat(indexes.openInterest.openInterestSteps("1")).isEqualTo(2);

        runtime.clearChangedKeys();
        runtime.putPosition(positionKey, new PositionRuntime(7, symbolId, assetId,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreStateTestFixtures.runtimeInstrument(),
                3, 100, 300, 0, 60));
        runtime.putOrder(CoreStateTestFixtures.order(11, 7, symbolId, 5));
        if (runtime.accountLanesStarted()) LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
        indexes.coordinator.applyCurrent(runtime, identities);

        assertThat(runtime.admissionOrders(7, symbolId).inspect(7, "1", CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreOrderSide.BUY, CoreMarginMode.ISOLATED).pendingQuantity()).isEqualTo(5);
        assertThat(indexes.openInterest.openInterestSteps("1")).isEqualTo(3);

        runtime.clearChangedKeys();
        runtime.removeOrder(11);
        runtime.removePosition(positionKey, 7);
        if (runtime.accountLanesStarted()) LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
        indexes.coordinator.applyCurrent(runtime, identities);

        assertThat(indexes.activeOrders.page(0, null, 0, 1024).orderIds()).isEmpty();
        assertThat(indexes.positionUsers.usersAfter("1", 0)).isEmpty();
        assertThat(indexes.openInterest.openInterestSteps("1")).isZero();
        runtime.close();
    }

    /**
     * 启动真实账户 Lane 后在所属 Lane 写入、删除风险快照及持仓；应用发布变化并清理已提交记录后，检查账户风险为空、持仓身份退休且投影不脏。
     */
    @Test
    void commitsLanePublishedRiskValuesAndReleasesRetiredPositionIdentity() {
        // 本用例 U 本位永续的空冷状态
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        // 原始身份表，供内部编号转换和最终身份回收断言
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 本用例由冷状态投影的交易运行时，真实 Lane 用例会显式启动账户资源
        TradingAccountRuntime runtime = RuntimeStateProjector.project(initial, identities);
        // 永久标的一对应内部稠密标的编号
        int symbolId = identities.symbolId("1");
        // USDT 内部资产编号
        int assetId = identities.assetId("USDT");
        // 本用例账户用户七
        long userId = 7;
        // 本用例持仓和风险快照共用的确定性身份键
        long positionKey = identities.positionKey(userId, "1:NET");
        runtime.putPosition(positionKey, new PositionRuntime(userId, symbolId, assetId,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreStateTestFixtures.runtimeInstrument(),
                2, 100, 200, 0, 40));
        runtime.clearChangedKeys();
        runtime.startAccountLanes();

        // 四项可断言索引及共同事实应用入口组成的测试记录
        IndexSet indexes = indexes(initial, identities);
        // 当前写入并随后删除的 NORMAL 风险快照
        RiskSnapshotRuntime risk = new RiskSnapshotRuntime(userId, symbolId, CorePositionSide.NET,
                9, 1_000, 20, 100, 100_000);
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), /* 实际执行此控制工作的所属 Lane，闭包按原用户与键操作 */ ignored -> {
            runtime.putRiskSnapshot(positionKey, risk);
            return null;
        });
        if (runtime.accountLanesStarted()) LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
        indexes.coordinator.applyCurrent(runtime, identities);

        assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.riskSnapshot(positionKey))).isEqualTo(risk);

        runtime.clearChangedKeys();
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), /* 实际执行此控制工作的所属 Lane，闭包按原用户与键操作 */ ignored -> {
            runtime.removeRiskSnapshot(positionKey);
            runtime.removePosition(positionKey, userId);
            return null;
        });
        if (runtime.accountLanesStarted()) LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
        indexes.coordinator.applyCurrent(runtime, identities);
        runtime.clearCommittedChanges(identities);

        assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.riskSnapshot(positionKey))).isNull();
        assertThat(identities.findPositionKey(userId, "1:NET")).isNull();
        assertThat(runtime.snapshotProjectionStateDirty()).isFalse();
        runtime.close();
    }

    /**
     * 通过真实账户 Lane 新增再移除计划中强平，应用冻结变化后核对账户正文和 Owner 未结束 ID 集合同步。
     */
    @Test
    void commitsLanePublishedLiquidationValuesAndDeletion() {
        // 本用例 U 本位永续的空冷状态
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        // 原始身份表，供内部编号转换和最终身份回收断言
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 本用例由冷状态投影的交易运行时，真实 Lane 用例会显式启动账户资源
        TradingAccountRuntime runtime = RuntimeStateProjector.project(initial, identities);
        // 永久标的一对应内部稠密标的编号
        int symbolId = identities.symbolId("1");
        // 本用例账户用户七
        long userId = 7;
        // 用户七待执行的 PLANNED 强平任务
        LiquidationRuntime planned = new LiquidationRuntime(1, userId, symbolId,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreStateTestFixtures.runtimeInstrument(), 9, 2, 2,
                0, 0, 0, 0, CoreLiquidationState.Status.PLANNED, 0);
        runtime.startAccountLanes();
        // 四项可断言索引及共同事实应用入口组成的测试记录
        IndexSet indexes = indexes(initial, identities);

        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), /* 实际执行此控制工作的所属 Lane，闭包按原用户与键操作 */ ignored -> {
            runtime.putLiquidation(planned);
            return null;
        });
        if (runtime.accountLanesStarted()) LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
        indexes.coordinator.applyCurrent(runtime, identities);

        assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.liquidation(1))).isEqualTo(planned);
        assertThat(indexes.liquidations.activeIds()).containsExactly(1L);

        runtime.clearChangedKeys();
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), /* 实际执行此控制工作的所属 Lane，闭包按原用户与键操作 */ ignored -> {
            runtime.removeLiquidation(1);
            return null;
        });
        if (runtime.accountLanesStarted()) LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
        indexes.coordinator.applyCurrent(runtime, identities);

        assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.liquidation(1))).isNull();
        assertThat(indexes.liquidations.activeIds()).isEmpty();
        runtime.close();
    }

    /**
     * 在同一冷状态及身份表上建立八个派生索引，返回需断言的四项及共用事实应用入口。
     * @param state 用于冷建各索引的本产品线已提交状态
     * @param identities 本用例原资产及标的身份表
     */
    private static IndexSet indexes(TradingCoreState state, RuntimeIdentityRegistry identities) {
        // 由初始冷状态构造的持仓用户索引
        PositionUserIndex positionUsers = new PositionUserIndex(state);
        // 由初始冷状态构造的未平仓量索引
        OpenInterestIndex openInterest = new OpenInterestIndex(state);
        // 由初始冷状态构造的触发单索引
        TriggerOrderIndex triggers = new TriggerOrderIndex(state);
        // 由初始冷状态构造的算法单索引
        AlgoOrderIndex algos = new AlgoOrderIndex(state);
        // 由初始冷状态构造的强平索引
        LiquidationIndex liquidations = new LiquidationIndex(state);
        // 由初始冷状态构造的延时全撤索引
        CancelAllAfterIndex timers = new CancelAllAfterIndex(state);
        // 由初始冷状态构造的活跃订单路由
        ActiveOrderIndex activeOrders = new ActiveOrderIndex(state, identities);
        // 由初始冷状态构造的 ADL 持仓索引
        AdlPositionIndex adlPositions = new AdlPositionIndex(state, identities);
        return new IndexSet(positionUsers, openInterest, activeOrders, liquidations,
                new RuntimeFactIndexes(positionUsers, openInterest, triggers, algos, liquidations,
                        timers, activeOrders, adlPositions));
    }

    /**
     * 本测试要直接断言的四个派生索引以及它们共用的事实应用入口，不新增权威业务状态。
     * @param positionUsers 持仓用户路由索引
     * @param openInterest 未平仓量业务索引
     * @param activeOrders Owner 的 OPEN 订单路由
     * @param liquidations Owner 的强平路由及未结束 ID 集合
     * @param coordinator 同时持有全部八项既有索引的事实应用入口
     */
    private record IndexSet(PositionUserIndex positionUsers, OpenInterestIndex openInterest,
                            ActiveOrderIndex activeOrders, LiquidationIndex liquidations,
                            RuntimeFactIndexes coordinator) { }
}
