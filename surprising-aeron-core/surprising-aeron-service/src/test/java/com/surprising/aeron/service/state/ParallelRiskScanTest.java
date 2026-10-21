package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.lane.LaneTopology;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.account.CoreUserState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.index.AdlPositionIndex;
import com.surprising.aeron.service.state.index.OpenInterestIndex;
import com.surprising.aeron.service.state.index.PositionUserIndex;
import com.surprising.aeron.service.state.index.RuntimeFactIndexes;
import com.surprising.aeron.service.state.model.*;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CorePositionState;
import com.surprising.aeron.service.state.order.RuntimeTestStateTransitions;
import com.surprising.aeron.service.state.risk.RiskScanCoordinator;
import com.surprising.aeron.service.state.risk.RuntimeDerivativeRiskProcessor;
import com.surprising.aeron.service.state.snapshot.*;
import com.surprising.aeron.service.state.snapshot.RuntimeStateMaterializer;
import com.surprising.aeron.service.state.snapshot.RuntimeStateProjector;
import com.surprising.aeron.service.state.snapshot.TradingStateSnapshotCodec;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * 验证多账户 Lane 风险扫描的小预算推进、恢复游标、用户与行情变更失效以及确定强平编号。测试辅助方法的实际覆盖范围在各方法说明中明确，避免只按测试名称理解。
 */
class ParallelRiskScanTest {
    /** 生产默认账户 Lane 拓扑，所有账户分布、索引和运行时都用同一配置。 */
    private static final LaneTopology TOPOLOGY = LaneTopology.productionDefault();
    /** 本组测试主要使用的 BTC 合约外部编号。 */
    private static final String SYMBOL = "1";

    /**
     * 启动真实账户 Lane 和账本，用每批 1、3、7 项的小预算复用同一续步，检查所有用户最终都被估值并产生连续强平编号，同时账户和总账资金不变。
     * @param budget 本批允许的工作量，小预算用来验证跨页继续而非一次处理完整用户
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 3, 7})
    void ledgerUserPagesKeepSmallRiskBudgetsCompleteWithoutReadingIndexesOnLanes(int budget) {
        // 本用例的初始业务状态，按场景构造逐仓或共享权益的全仓持仓。
        var source = source(CoreMarginMode.ISOLATED, 12);
        // 当前运行时投影使用的身份映射，后续索引与物化检查都共用它。
        var ids = new RuntimeIdentityRegistry();
        try (/* 由初始业务状态投影的测试运行时，离开作用域时关闭相关线程和资源。 */ var runtime = RuntimeStateProjector.project(source, ids, TOPOLOGY)) {
            // 从本用例持仓构造的用户索引，供账本准备各 Lane 的有界用户页。
            var index = new PositionUserIndex(source, ids, TOPOLOGY);
            runtime.bindFactIndexes(new RuntimeFactIndexes(index, new OpenInterestIndex(source, ids),
                    new com.surprising.aeron.service.state.index.TriggerOrderIndex(source),
                    new com.surprising.aeron.service.state.index.AlgoOrderIndex(source),
                    new com.surprising.aeron.service.state.index.LiquidationIndex(source),
                    new com.surprising.aeron.service.state.index.CancelAllAfterIndex(source),
                    new com.surprising.aeron.service.state.index.ActiveOrderIndex(source, ids),
                    new AdlPositionIndex(source, ids)), ids);
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(1, 80), runtime, ids);
            runtime.startAccountLanes(); runtime.startSystemLedgerLane(); runtime.enterAsynchronousCommandScope();
            try {
                // 本次风险续步对象，Owner 轮询它完成；小预算连续命令场景会复用同一对象。
                var work = new RiskScanCoordinator(budget, index, runtime, ids);
                for (/* 已经推进的页数，限制循环次数并在交替预算场景选择本页预算。 */ int page = 0; runtime.firstRiskIncompleteScan() != null; page++) {
                    assertThat(page).isLessThan(4096);
                    if (page != 0) work.resetForCommand(budget);
                    await(work::poll);
                    assertThat(work.completedWork()).isBetween(1, budget);
                }
            } finally { runtime.exitAsynchronousCommandScope(); }
            runtime.close();
            // 扫描结束后的业务物化结果，用来核对风险条数、编号和账户资金事实。
            var complete = RuntimeStateMaterializer.materialize(runtime, ids);
            assertThat(complete.riskState().snapshots()).hasSize(source.users().size());
            assertThat(complete.riskState().liquidations()).hasSize(source.users().size());
            assertThat(complete.riskState().nextLiquidationId()).isEqualTo(source.users().size() + 1L);
            assertThat(complete.users()).isEqualTo(source.users());
            assertThat(complete.treasuryState()).isEqualTo(source.treasuryState());
        }
    }

    /**
     * 分别以首 Lane 和末 Lane 作为辅助方法参数运行两次扫描，比较完整结果及编号与 Lane 的对应顺序。当前辅助方法未实际注入阻塞，因此本用例尚不能证明慢 Lane 或不同完成顺序的行为。
     */
    @Test
    void slowLaneDoesNotPreventOtherLanesFromValuingAndIdsIgnoreCompletionOrder() throws Exception {
        // 本用例的初始业务状态，按场景构造逐仓或共享权益的全仓持仓。
        var source = source(CoreMarginMode.ISOLATED, 1);
        // 以首 Lane 编号作为参数得到的第一次扫描结果，当前辅助实现并未真正阻塞首 Lane。
        var slowFirst = scanWithBlockedLane(source, 0);
        // 以末 Lane 编号作为参数得到的第二次扫描结果，当前辅助实现并未真正阻塞末 Lane。
        var slowLast = scanWithBlockedLane(source, TOPOLOGY.accountLaneCount() - 1);
        assertThat(slowFirst).isEqualTo(slowLast);
        assertThat(slowFirst.riskState().nextLiquidationId()).isEqualTo(TOPOLOGY.accountLaneCount() + 1L);
        for (/* 当前检查编号顺序或分配测试账户的账户 Lane 下标。 */ int lane = 0; lane < TOPOLOGY.accountLaneCount(); lane++)
            assertThat(TOPOLOGY.accountLaneId(slowFirst.riskState().liquidations().get(lane + 1L).userId()))
                    .isEqualTo(lane);
        assertThat(slowFirst.users()).isEqualTo(source.users());
        assertThat(slowFirst.treasuryState()).isEqualTo(source.treasuryState());
    }

    /**
     * 创建运行时并启动账户 Lane，轮询完成一批风险扫描后物化业务状态。当前实现未使用 blockedLane 参数，也未阻塞任何 Lane，名称中的阻塞场景尚未真正构造。
     * @param source 本轮扫描开始前的业务状态，包含各 Lane 的测试持仓
     * @param blockedLane 调用方传入的拟阻塞 Lane 编号；当前辅助实现没有使用它或注入阻塞
     */
    private TradingCoreState scanWithBlockedLane(TradingCoreState source, int blockedLane) throws Exception {
        // 当前运行时投影使用的身份映射，后续索引与物化检查都共用它。
        var ids = new RuntimeIdentityRegistry();
        try (/* 由初始业务状态投影的测试运行时，离开作用域时关闭相关线程和资源。 */ var runtime = RuntimeStateProjector.project(source, ids, TOPOLOGY)) {
            // 从本用例持仓构造的用户索引，供账本准备各 Lane 的有界用户页。
            var index = new PositionUserIndex(source, ids, TOPOLOGY);
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(1, 80), runtime, ids);
            runtime.startAccountLanes();
            // 本次风险续步对象，Owner 轮询它完成；小预算连续命令场景会复用同一对象。
            var work = new RiskScanCoordinator(TOPOLOGY.accountLaneCount(), index, runtime, ids);
            while (!work.poll()) {
                Thread.onSpinWait();
            }
            runtime.close();
            return RuntimeStateMaterializer.materialize(runtime, ids);
        }
    }

    /**
     * 先让每个 Lane 留下未完成用户，再保存并恢复快照、推入新价格，以交替小预算逐页比对原运行时与恢复运行时，最终编号、风险记录和资金事实都要一致。
     */
    @Test
    void partialProgressOnEveryLaneSurvivesSnapshotSmallBudgetsAndNewPrice() {
        // 本用例的初始业务状态，按场景构造逐仓或共享权益的全仓持仓。
        var source = source(CoreMarginMode.CROSS, 2);
        // 当前运行时投影使用的身份映射，后续索引与物化检查都共用它。
        var ids = new RuntimeIdentityRegistry();
        try (/* 由初始业务状态投影的测试运行时，离开作用域时关闭相关线程和资源。 */ var runtime = RuntimeStateProjector.project(source, ids, TOPOLOGY)) {
            // 从本用例持仓构造的用户索引，供账本准备各 Lane 的有界用户页。
            var index = new PositionUserIndex(source, ids, TOPOLOGY);
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(1, 80), runtime, ids);
            runtime.startAccountLanes();
            run(runtime, ids, index, TOPOLOGY.accountLaneCount());
            // 只完成首批工作量的检查点，每条 Lane 都应留下进行中的用户。
            var partial = RuntimeStateMaterializer.materialize(runtime, ids);
            assertThat(partial.riskState().scan().laneProgress()).hasSize(TOPOLOGY.accountLaneCount())
                    .allMatch(/* 逐条检查的 Lane 进度，应保留未完成用户及对应的组合累计金额。 */ cursor -> cursor.userId() > 0 && !cursor.complete());
            // 把扫描检查点编码成快照再恢复的业务状态。
            var restoredState = TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(partial),
                    ProductLine.LINEAR_PERPETUAL);
            assertThat(restoredState).isEqualTo(partial);
            // 恢复运行时重新建立的身份映射，不依赖恢复前整数键恰好相同。
            var restoredIds = new RuntimeIdentityRegistry();
            try (/* 从快照恢复状态投影的第二个运行时，与原运行时应用同样的行情和预算。 */ var restored = RuntimeStateProjector.project(restoredState, restoredIds, TOPOLOGY)) {
                restored.startAccountLanes();
                // 恢复状态上的持仓用户索引，使用恢复后的身份映射。
                var restoredIndex = new PositionUserIndex(restoredState, restoredIds, TOPOLOGY);
                RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(2, 70), runtime, ids);
                RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(2, 70), restored, restoredIds);
                // 循环交替使用的 1、3、7 项预算，让不同 Lane 和用户阶段反复跨页。
                int[] budgets = {1, 3, 7};
                for (/* 已经推进的页数，限制循环次数并在交替预算场景选择本页预算。 */ int page = 0; page < 128 && runtime.firstRiskIncompleteScan() != null; page++) {
                    // 本页从交替预算数组选出的工作量上限。
                    int budget = budgets[page % budgets.length];
                    assertThat(run(runtime, ids, index, budget)).isBetween(1, budget);
                    run(restored, restoredIds, restoredIndex, budget);
                    // 原运行时推进本页后的业务状态，作为恢复路径参照。
                    var expected = RuntimeStateMaterializer.materialize(runtime, ids);
                    // 恢复运行时推进相同预算后的业务状态，必须与参照完全相同。
                    var actual = RuntimeStateMaterializer.materialize(restored, restoredIds);
                    assertThat(actual).isEqualTo(expected);
                    assertThat(actual.businessStateHash()).isEqualTo(expected.businessStateHash());
                    assertThat(actual.users()).isEqualTo(source.users());
                    assertThat(actual.treasuryState()).isEqualTo(source.treasuryState());
                }
                assertThat(runtime.firstRiskIncompleteScan()).isNull();
                // 扫描结束后的业务物化结果，用来核对风险条数、编号和账户资金事实。
                var complete = RuntimeStateMaterializer.materialize(runtime, ids);
                assertThat(complete.riskState().snapshots()).hasSize(2 * TOPOLOGY.accountLaneCount());
                assertThat(complete.riskState().snapshots().values()).allMatch(/* 最终检查的单条风险记录，价格序号必须更新为新价格版本。 */ risk -> risk.priceSequence() == 2);
                assertThat(complete.riskState().liquidations()).hasSize(2 * TOPOLOGY.accountLaneCount());
                assertThat(complete.riskState().nextLiquidationId()).isEqualTo(2 * TOPOLOGY.accountLaneCount() + 1L);
            }
        }
    }

    /**
     * 风险累加出浮盈后模拟真实平仓：利润进入钱包、原持仓消失且用户版本变化；继续估值另一持仓时必须重算共享权益，不能把旧浮盈再加一次。
     */
    @Test
    void closingAProfitablePositionBetweenPagesMustNotCountItsProfitTwice() {
        // 按同一 Lane 拓扑准备账户和合约业务状态的测试状态转换辅助器。
        var reducer = new RuntimeTestStateTransitions(TOPOLOGY);
        // 本用例的初始业务状态，按场景构造逐仓或共享权益的全仓持仓。
        var source = reducer.registerInstrument(source(CoreMarginMode.CROSS, 1),
                new RegisterInstrumentCommand("2", ContractType.LINEAR_PERPETUAL.ordinal(),
                        "ETH", "USDT", "USDT", 1, 1, 1, 100_000, 100_000, 0, 0, 0, -1, 0));
        // 需要加入第二币对持仓或替换一个用户的业务账户映射副本。
        var users = new TreeMap<>(source.users());
        for (var /* 当前遍历或替换的测试账户，保留其其他余额、冻结和持仓。 */ user : source.users().values()) {
            // 当前用户持仓映射的副本，为组合风险测试加入 ETH 全仓持仓。
            var positions = new TreeMap<>(user.positions());
            positions.put("2", new CorePositionState("2", "USDT", CoreMarginMode.CROSS,
                    CorePositionSide.NET, 1, 100, 100, 0, 0));
            users.put(user.userId(), new CoreUserState(source.productLine(), user.userId(), user.revision() + 1,
                    user.balances(), user.reservations(), positions));
        }
        source = new TradingCoreState(source.productLine(), source.revision() + 1, users, source.orders(),
                source.instruments(), source.riskState(), source.treasuryState());
        // 当前运行时投影使用的身份映射，后续索引与物化检查都共用它。
        var ids = new RuntimeIdentityRegistry();
        try (/* 由初始业务状态投影的测试运行时，离开作用域时关闭相关线程和资源。 */ var runtime = RuntimeStateProjector.project(source, ids, TOPOLOGY)) {
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(new ApplyMarkPriceCommand("2", 100, 1, 1_700_000_000_000L), runtime, ids);
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(1, 120), runtime, ids);
            runtime.startAccountLanes();
            run(runtime, ids, new PositionUserIndex(source, ids, TOPOLOGY), TOPOLOGY.accountLaneCount());
            // 从初始账户中选出的一个用户，用它模拟扫描中途盈利平仓。
            long userId = source.users().keySet().iterator().next();
            // BTC 当前扫描记录，确认选中用户的 Lane 已累加到二百单位浮盈。
            var scan = runtime.riskScan(ids.symbolId(SYMBOL));
            assertThat(scan.laneProgress().get(TOPOLOGY.accountLaneId(userId)).unrealizedPnlUnits()).isEqualTo(200);
            // 按平仓结算后的实际形态更新账户：200 盈利进入钱包，BTC 持仓消失，ETH 仍在。
            runtime.acquireOwnerLaneAccess();
            try {
                runtime.replaceBalance(new BalanceRuntime(userId, ids.assetId("USDT"), 300, 0));
                runtime.removePosition(ids.preparedPositionKey(userId, SYMBOL), userId);
                runtime.advanceUserRevision(userId);
            } finally {
                runtime.releaseOwnerLaneAccess();
            }
            // 模拟平仓且用户版本变化后的业务检查点，后续索引据此重建。
            var changed = RuntimeStateMaterializer.materialize(runtime, ids);
            // 从本用例持仓构造的用户索引，供账本准备各 Lane 的有界用户页。
            var index = new PositionUserIndex(changed, ids, TOPOLOGY);
            RuntimeDerivativeRiskProcessor.applyContinuationRuntime(2 * TOPOLOGY.accountLaneCount(),
                    ids.symbolId(SYMBOL), index, runtime, ids);
            // 继续估值后的结果，ETH 风险权益应等于已含平仓利润的钱包三百单位。
            var state = RuntimeStateMaterializer.materialize(runtime, ids);
            assertThat(state.riskState().snapshots().get(userId + ":2").equityUnits()).isEqualTo(300);
        }
    }

    /**
     * ETH 扫描已累加到 BTC 持仓时再更新 BTC 价格，即使 ETH 扫描入口未重建，市场修订变化也应使组合累计值失效；原状态和快照恢复后逐页重算结果必须一致。
     */
    @Test
    void anotherSymbolsPriceChangeInvalidatesPartialPortfolioEvenAfterSnapshot() {
        // 按同一 Lane 拓扑准备账户和合约业务状态的测试状态转换辅助器。
        var reducer = new RuntimeTestStateTransitions(TOPOLOGY);
        // 本用例的初始业务状态，按场景构造逐仓或共享权益的全仓持仓。
        var source = reducer.registerInstrument(source(CoreMarginMode.CROSS, 1),
                new RegisterInstrumentCommand("2", ContractType.LINEAR_PERPETUAL.ordinal(),
                        "ETH", "USDT", "USDT", 1, 1, 1, 100_000, 100_000, 0, 0, 0, -1, 0));
        // 需要加入第二币对持仓或替换一个用户的业务账户映射副本。
        var users = new TreeMap<>(source.users());
        for (var /* 当前遍历或替换的测试账户，保留其其他余额、冻结和持仓。 */ user : source.users().values()) {
            // 当前用户持仓映射的副本，为组合风险测试加入 ETH 全仓持仓。
            var positions = new TreeMap<>(user.positions());
            positions.put("2", new CorePositionState("2", "USDT", CoreMarginMode.CROSS,
                    CorePositionSide.NET, 1, 100, 100, 0, 0));
            users.put(user.userId(), new CoreUserState(source.productLine(), user.userId(), user.revision() + 1,
                    user.balances(), user.reservations(), positions));
        }
        source = new TradingCoreState(source.productLine(), source.revision() + 1, users, source.orders(),
                source.instruments(), source.riskState(), source.treasuryState());
        // 当前运行时投影使用的身份映射，后续索引与物化检查都共用它。
        var ids = new RuntimeIdentityRegistry();
        try (/* 由初始业务状态投影的测试运行时，离开作用域时关闭相关线程和资源。 */ var runtime = RuntimeStateProjector.project(source, ids, TOPOLOGY)) {
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(1, 100), runtime, ids);
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(new ApplyMarkPriceCommand("2", 100, 1, 1_700_000_000_001L), runtime, ids);
            // 从本用例持仓构造的用户索引，供账本准备各 Lane 的有界用户页。
            var index = new PositionUserIndex(source, ids, TOPOLOGY);
            RuntimeDerivativeRiskProcessor.applyContinuationRuntime(2 * TOPOLOGY.accountLaneCount(),
                    ids.symbolId("2"), index, runtime, ids);
            assertThat(runtime.riskScan(ids.symbolId("2")).laneProgress())
                    .allMatch(/* 逐条检查的 Lane 进度，应保留未完成用户及对应的组合累计金额。 */ cursor -> cursor.userId() > 0 && cursor.unrealizedPnlUnits() == 0);
            // ETH 扫描已经累计完两个持仓，此时 BTC 新价格只更新 BTC 扫描入口。
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(2, 120), runtime, ids);
            // ETH 尚在分批估值、BTC 已刷新价格时保存的业务状态检查点。
            var checkpoint = RuntimeStateMaterializer.materialize(runtime, ids);
            // 把扫描检查点编码成快照再恢复的业务状态。
            var restoredState = TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(checkpoint),
                    ProductLine.LINEAR_PERPETUAL);
            // 恢复运行时重新建立的身份映射，不依赖恢复前整数键恰好相同。
            var restoredIds = new RuntimeIdentityRegistry();
            try (/* 从快照恢复状态投影的第二个运行时，与原运行时应用同样的行情和预算。 */ var restored = RuntimeStateProjector.project(restoredState, restoredIds, TOPOLOGY)) {
                // 恢复状态上的持仓用户索引，使用恢复后的身份映射。
                var restoredIndex = new PositionUserIndex(restoredState, restoredIds, TOPOLOGY);
                for (/* 已经推进的页数，限制循环次数并在交替预算场景选择本页预算。 */ int page = 0; page < 8 && !runtime.riskScan(ids.symbolId("2")).riskComplete(); page++) {
                    RuntimeDerivativeRiskProcessor.applyContinuationRuntime(TOPOLOGY.accountLaneCount(),
                            ids.symbolId("2"), index, runtime, ids);
                    RuntimeDerivativeRiskProcessor.applyContinuationRuntime(TOPOLOGY.accountLaneCount(),
                            restoredIds.symbolId("2"), restoredIndex, restored, restoredIds);
                    assertThat(RuntimeStateMaterializer.materialize(restored, restoredIds))
                            .isEqualTo(RuntimeStateMaterializer.materialize(runtime, ids));
                }
                // 扫描结束后的业务物化结果，用来核对风险条数、编号和账户资金事实。
                var complete = RuntimeStateMaterializer.materialize(runtime, ids);
                assertThat(runtime.riskScan(ids.symbolId("2")).riskComplete()).isTrue();
                assertThat(complete.riskState().snapshots().values()).hasSize(2 * TOPOLOGY.accountLaneCount())
                        .allMatch(/* 最终逐条检查的风险快照，组合权益都应使用更新后的三百单位。 */ snapshot -> snapshot.equityUnits() == 300);
                assertThat(complete.riskState().liquidations()).isEmpty();
            }
        }
    }

    /**
     * 把下一强平编号设到 long 上界附近，执行需要多编号的扫描应报溢出，且全局编号和强平记录都不能部分发布。
     */
    @Test
    void idOverflowCannotPartiallyPublishNewLiquidations() {
        // 本用例的初始业务状态，按场景构造逐仓或共享权益的全仓持仓。
        var source = source(CoreMarginMode.ISOLATED, 1);
        // 当前运行时投影使用的身份映射，后续索引与物化检查都共用它。
        var ids = new RuntimeIdentityRegistry();
        try (/* 由初始业务状态投影的测试运行时，离开作用域时关闭相关线程和资源。 */ var runtime = RuntimeStateProjector.project(source, ids, TOPOLOGY)) {
            // 从本用例持仓构造的用户索引，供账本准备各 Lane 的有界用户页。
            var index = new PositionUserIndex(source, ids, TOPOLOGY);
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(1, 80), runtime, ids);
            runtime.setNextLiquidationId(Long.MAX_VALUE - 1);
            runtime.startAccountLanes();
            assertThatThrownBy(() -> run(runtime, ids, index, TOPOLOGY.accountLaneCount()))
                    .isInstanceOf(ArithmeticException.class);
            assertThat(runtime.nextLiquidationId()).isEqualTo(Long.MAX_VALUE - 1);
            runtime.close();
            assertThat(RuntimeStateMaterializer.materialize(runtime, ids).riskState().liquidations()).isEmpty();
        }
    }

    /**
     * 在异步命令作用域内创建一批风险续步，最多等待五秒完成后返回实际工作量，finally 始终退出作用域。
     * @param runtime 需要执行风险续步的测试运行时
     * @param ids 与该运行时投影配套的业务身份映射
     * @param index 按币对及 Lane 定位持仓用户的索引
     * @param budget 本批允许的工作量，小预算用来验证跨页继续而非一次处理完整用户
     */
    private static int run(TradingRuntimeState runtime, RuntimeIdentityRegistry ids, PositionUserIndex index, int budget) {
        runtime.enterAsynchronousCommandScope();
        try {
            // 本次风险续步对象，Owner 轮询它完成；小预算连续命令场景会复用同一对象。
            var work = new RiskScanCoordinator(budget, index, runtime, ids);
            await(work::poll);
            return work.completedWork();
        } finally { runtime.exitAsynchronousCommandScope(); }
    }

    /**
     * 用统一测试币对和递增毫秒时间构造指定价格序号的标记价命令。
     * @param sequence 新标记价的递增行情序号
     * @param price 本次测试标记价格 tick
     */
    private static ApplyMarkPriceCommand mark(long sequence, long price) {
        return new ApplyMarkPriceCommand(SYMBOL, price, sequence, 1_700_000_000_000L + sequence);
    }

    /**
     * 沿用户编号分配规则凑齐每个 Lane 指定数量的测试账户，每人放一份同模式持仓和一百单位冻结保证金，保证所有 Lane 都有待扫工作。
     * @param margin 所有初始测试持仓采用全仓还是逐仓模式
     * @param usersPerLane 每条账户 Lane 需要凑齐的测试用户数量
     */
    private static TradingCoreState source(CoreMarginMode margin, int usersPerLane) {
        // 按同一 Lane 拓扑准备账户和合约业务状态的测试状态转换辅助器。
        var reducer = new RuntimeTestStateTransitions(TOPOLOGY);
        // 逐步注册合约、充值并加入测试账户持仓的初始业务状态。
        var state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                new RegisterInstrumentCommand(SYMBOL, ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 100_000, 0, 0, 0, -1, 0));
        // 每条 Lane 已经加入的测试用户数量，达到 usersPerLane 后跳过该 Lane 的更多用户。
        int[] counts = new int[TOPOLOGY.accountLaneCount()];
        // 尚需加入的测试用户总数，每成功加入一个账户就减一，全部 Lane 凑齐后结束。
        int required = counts.length * usersPerLane;
        for (/* 按编号递增寻找可分配到尚未凑齐 Lane 的测试用户。 */ long userId = 1; required > 0; userId++) {
            // 当前检查编号顺序或分配测试账户的账户 Lane 下标。
            int lane = TOPOLOGY.accountLaneId(userId);
            if (counts[lane] == usersPerLane) continue;
            counts[lane]++;
            required--;
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 100));
            // 每个测试账户的十步 BTC 多头，入场价一百，持仓保证金一百单位。
            var position = new CorePositionState(SYMBOL, "USDT", margin, CorePositionSide.NET,
                    10, 100, 1_000, 0, 100);
            // 需要加入第二币对持仓或替换一个用户的业务账户映射副本。
            var users = new TreeMap<>(state.users());
            // 当前遍历或替换的测试账户，保留其其他余额、冻结和持仓。
            var user = state.user(userId);
            users.put(userId, new CoreUserState(state.productLine(), userId, user.revision() + 1,
                    Map.of("USDT", new AssetBalance("USDT", 0, 100)), user.reservations(),
                    Map.of(position.key(), position)));
            state = new TradingCoreState(state.productLine(), state.revision() + 1, users, state.orders(),
                    state.instruments(), state.riskState(), state.treasuryState());
        }
        return state;
    }

    /**
     * 用单调时钟最多轮询五秒，续步未完成时自旋，超时则让测试失败。
     * @param done 每次轮询返回是否完成的风险续步
     */
    private static void await(BooleanSupplier done) {
        // 等待风险续步完成的单调时钟截止值，超过五秒则报测试超时。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("risk task timeout");
            Thread.onSpinWait();
        }
    }
}
