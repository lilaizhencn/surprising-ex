package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.config.CoreConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreMarkPriceState;
import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.model.CoreRiskSnapshot;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.PositionUserIndex;
import com.surprising.aeron.service.order.state.RuntimeTestStateTransitions;
import com.surprising.aeron.service.risk.RuntimeDerivativeRiskProcessor;
import com.surprising.aeron.service.snapshot.state.*;
import com.surprising.aeron.service.snapshot.state.FundsStateHash;
import com.surprising.aeron.service.snapshot.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.snapshot.state.TradingStateSnapshotCodec;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证风险估值、全仓共享权益、强平计划和分批扫描的恢复一致性，既覆盖不可变测试状态，也覆盖实际账户 Lane 的运行时状态。
 */
class CoreRiskStateTest {

    /** 通过真实状态转换辅助器准备业务状态并产生参照结果，供运行时逐步比对。 */
    private final RuntimeTestStateTransitions reducer = new RuntimeTestStateTransitions();

    /**
     * 验证默认每 25 毫秒调度、每批最多 64 项工作；修改调度配置后，快照恢复应保留新的控制值。
     */
    @Test
    void defaultRiskCadencePreservesBoundedWorkAndSnapshotControl() {
        // 从空风险状态开始，随后写入运营调整的调度间隔与工作量配置。
        TradingCoreState state = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        assertThat(state.riskState().scanControl().scanDelayMs()).isEqualTo(25);
        assertThat(state.riskState().scanControl().scanBatchSize()).isEqualTo(64);
        state = reducer.updateRiskScanControl(state, new UpdateRiskScanControlCommand(
                1, "Operator cadence", true, 500, 32, "admin", "configuration"), 2_000);
        // 将本次检查点编码成快照后再解码的状态，用来核对恢复结果。
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(state), ProductLine.LINEAR_PERPETUAL);
        assertThat(restored.riskState().scanControl()).isEqualTo(state.riskState().scanControl());
        assertThat(restored.riskState().scanControl().scanDelayMs()).isEqualTo(500);
    }

    /**
     * 停用风险扫描后再更新标记价，不应生成风险估值；同时确认控制版本增加、风险部分保持完成。
     */
    @Test
    void disabledRiskScanDoesNotCalculateRisk() {
        // 已配置持仓和余额、随后关闭风险扫描的账户状态。
        TradingCoreState state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 100));
        state = withPosition(state, new CorePositionState("1", "USDT", 10, 100, 1_000, 0, 100));
        state = reducer.updateRiskScanControl(state, new UpdateRiskScanControlCommand(
                1, "Paused scan", false, 1_000, 500, "admin", "maintenance"), 2_000);

        // 测试状态转换应用本次标记价后得到的状态，用来检查估值和强平计划。
        TradingCoreState marked = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("1", 80, 1, 2_001));

        assertThat(marked.riskState().snapshots()).isEmpty();
        assertThat(marked.riskState().scan().riskComplete()).isTrue();
        assertThat(marked.riskState().scanControl().version()).isEqualTo(2);
    }

    /**
     * 分别用线性和反向永续验证价格下跌后的强平风险、计划平仓量、运行时一致性，以及快照恢复后的业务状态与哈希。
     * @param productLine 这次参数化测试的产品线，必须与合约类型一致
     * @param contractType 本轮使用的线性或反向永续合约类型
     * @param entryPrice 构造测试持仓的入场价格 tick
     * @param markPrice 用于制造浮亏并触发风险计算的新标记价格 tick
     * @param settleScale 测试结算资产的缩放精度，反向合约需要用它换算金额
     */
    @ParameterizedTest
    @MethodSource("riskCases")
    void markPriceComputesRiskPlansLiquidationAndSurvivesSnapshot(
            ProductLine productLine,
            ContractType contractType,
            long entryPrice,
            long markPrice,
            long settleScale) {
        // 带有初始资金和多头持仓的测试状态，价格下跌后应达到强平条件。
        TradingCoreState state = reducer.registerInstrument(TradingCoreState.empty(productLine),
                instrument(contractType, settleScale));
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 100));
        state = withPosition(state, new CorePositionState("1", "USDT", 10, entryPrice, Math.multiplyExact(entryPrice, 10), 0, 100));

        // 这次下跌行情的统一命令，状态转换和运行时模拟都应用同一份输入。
        ApplyMarkPriceCommand markCommand = new ApplyMarkPriceCommand(
                "1", markPrice, 11, 1_700_000_000_000L);
        // 测试状态转换应用本次标记价后得到的状态，用来检查估值和强平计划。
        TradingCoreState marked = reducer.applyMarkPrice(state, markCommand);
        // 测试使用的字符串编号与运行时整数编号映射，投影和一致性比对必须共用它。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        RuntimeStateParityChecker.assertMatches(marked, identities,
                RuntimeDerivativeRiskFixture.simulateMarkPrice(
                        state, markCommand, identities));

        // 用户 7 在合约 1 的风险记录，应被评为需要强平。
        CoreRiskSnapshot risk = marked.riskState().snapshots().get("7:1");
        assertThat(risk.status()).isEqualTo(CoreRiskStatus.LIQUIDATION);
        assertThat(marked.riskState().liquidations()).hasSize(1);
        assertThat(marked.riskState().liquidations().get(1L).closeQuantitySteps()).isEqualTo(10);
        assertThat(marked.riskState().scan().complete()).isTrue();

        // 将本次检查点编码成快照后再解码的状态，用来核对恢复结果。
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(marked), productLine);
        assertThat(restored).isEqualTo(marked);
        assertThat(restored.businessStateHash()).isEqualTo(marked.businessStateHash());
    }

    /**
     * 提供线性、反向永续的入场价、下跌后标记价和结算精度，使两条产品线都能构造出需要强平的持仓。
     */
    private static Stream<Arguments> riskCases() {
        return Stream.of(
                Arguments.of(ProductLine.LINEAR_PERPETUAL, ContractType.LINEAR_PERPETUAL,
                        100, 80, 1),
                Arguments.of(ProductLine.INVERSE_PERPETUAL, ContractType.INVERSE_PERPETUAL,
                        100, 50, 10_000));
    }

    /**
     * 构造 1300 个用户，确认首批扫描不会超出预算，继续分批后能覆盖全部用户，并停在最后一个 Lane 的最后用户。
     */
    @Test
    void riskScanContinuesInBoundedBatches() {
        // 待批量加入 1300 个账户和持仓的测试状态，用来超过一批扫描预算。
        TradingCoreState state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        for (/* 批量构造或遍历风险账户时的用户编号，每个编号对应一份独立账户状态。 */ long userId = 1; userId <= 1_300; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 100));
            state = withPosition(state, userId, new CorePositionState("1", "USDT", 1, 100, 100, 0, 10));
        }

        // 应用新价格后只完成首批工作量的状态，必须仍有后续扫描。
        TradingCoreState firstBatch = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("1", 80, 1, 1_700_000_000_000L));
        assertThat(firstBatch.riskState().scan().complete()).isFalse();
        assertThat(firstBatch.riskState().snapshots()).hasSizeLessThanOrEqualTo(
                firstBatch.riskState().scanControl().scanBatchSize());
        assertThat(firstBatch.riskState().snapshots()).isNotEmpty();

        // 把首批状态继续扫描到结束后的结果，应有全部 1300 份风险估值。
        TradingCoreState completed = continueRiskScans(firstBatch);
        assertThat(completed.riskState().scan().complete()).isTrue();
        // 本地测试配置的 Lane 分配规则，用来独立算出最终游标应落在哪个用户。
        LaneTopology topology = CoreConfiguration.standaloneLaneTopology();
        // 属于最后一个账户 Lane 的最大用户编号，作为最终用户游标的期望值。
        long expectedLastUserId = 0;
        for (/* 批量构造或遍历风险账户时的用户编号，每个编号对应一份独立账户状态。 */ long userId = 1; userId <= 1_300; userId++) {
            if (topology.accountLaneId(userId) == topology.accountLaneCount() - 1) {
                expectedLastUserId = userId;
            }
        }
        assertThat(completed.riskState().scan().accountLaneId())
                .isEqualTo(topology.accountLaneCount() - 1);
        assertThat(completed.riskState().scan().laneProgress().get(topology.accountLaneCount() - 1).lastUserId())
                .isEqualTo(expectedLastUserId);
        assertThat(completed.riskState().snapshots()).hasSize(1_300);
    }

    /**
     * 在 260 个用户的风险扫描中途保存快照，分别从原状态和恢复状态继续，最终估值、业务状态和哈希必须相同。
     */
    @Test
    void laneRiskCursorSurvivesMidScanSnapshot() {
        // 需要跨批扫描的 260 个账户初始状态。
        TradingCoreState state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        for (/* 批量构造或遍历风险账户时的用户编号，每个编号对应一份独立账户状态。 */ long userId = 1; userId <= 260; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 100));
            state = withPosition(state, userId, new CorePositionState("1", "USDT", 1, 100, 100, 0, 10));
        }
        // 首批标记价处理后仍有未完成用户的状态，作为快照检查点。
        TradingCoreState partial = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("1", 80, 1, 1_700_000_000_000L));
        assertThat(partial.riskState().scan().riskComplete()).isFalse();
        // 将本次检查点编码成快照后再解码的状态，用来核对恢复结果。
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(partial), ProductLine.LINEAR_PERPETUAL);

        // 从原检查点继续扫描得到的参照结果。
        TradingCoreState expected = continueRiskScans(partial);
        // 从快照恢复的检查点继续扫描得到的结果，必须与参照结果完全相同。
        TradingCoreState actual = continueRiskScans(restored);

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.businessStateHash()).isEqualTo(expected.businessStateHash());
        assertThat(actual.riskState().snapshots()).hasSize(260);
    }

    /**
     * 运行时更新标记价只登记后续扫描，不立即给所有持仓估值；显式执行一次继续扫描后才应出现风险记录。
     */
    @Test
    void runtimeMarkPriceOnlySchedulesHeavyRiskWork() {
        // 用户 7 已有一份待估值持仓的初始状态。
        TradingCoreState state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 100));
        state = withPosition(state, new CorePositionState("1", "USDT", 10, 100, 1_000, 0, 100));
        // 测试使用的字符串编号与运行时整数编号映射，投影和一致性比对必须共用它。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 由测试状态投影出的可变运行时状态，接受标记价和分批风险处理。
        TradingAccountRuntime runtime = RuntimeStateProjector.project(state, identities);
        // 应用标记价前的全局修订号，用来确认行情登记只增加一次修订。
        long revisionBefore = runtime.revision();

        RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(
                new ApplyMarkPriceCommand("1", 80, 1, 1_700_000_000_000L),
                runtime, identities);

        assertThat(runtime.revision()).isEqualTo(revisionBefore + 1);
        assertThat(runtime.riskScan(identities.symbolId("1")).riskComplete()).isFalse();
        // 用户 7 的合约 1 持仓在运行时中的整数键，用来检查风险记录何时生成。
        long positionKey = identities.preparedPositionKey(7, "1");
        assertThat(runtime.riskSnapshot(positionKey)).isNull();

        RuntimeDerivativeRiskFixture.continueRiskBudget(64,
                new PositionUserIndex(state, runtime.topology()), runtime, identities);

        assertThat(runtime.riskSnapshot(positionKey)).isNotNull();
    }

    /**
     * 已处理币对再次更新价格时，应排到尚未扫描的币对后面；快照恢复后也必须保留这一调度顺序。
     */
    @Test
    void refreshedEarlierSymbolCannotStarveAnUnvisitedRiskScan() {
        // 注册两个币对但不放持仓的状态，使测试聚焦在扫描调度顺序。
        var state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), instrument("1"));
        state = reducer.registerInstrument(state, instrument("2"));
        // 测试使用的字符串编号与运行时整数编号映射，投影和一致性比对必须共用它。
        var identities = new RuntimeIdentityRegistry();
        // 由测试状态投影出的可变运行时状态，接受标记价和分批风险处理。
        var runtime = RuntimeStateProjector.project(state, identities);
        for (/* 本轮需要登记行情并安排风险扫描的测试币对编号。 */ String instrumentId : List.of("1", "2"))
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(new ApplyMarkPriceCommand(instrumentId, 100, 1, 1000), runtime, identities);
        // 调度器首先选中的币对整数编号，完成后会立刻再次刷新它的行情。
        int first = runtime.firstRiskIncompleteScan().symbolId();
        // 另一份尚未访问的币对编号，应该优先于刚刷新的已完成币对。
        int other = first == identities.symbolId("1") ? identities.symbolId("2") : identities.symbolId("1");
        RuntimeDerivativeRiskFixture.continueRiskBudget(1,
                new PositionUserIndex(state, runtime.topology()), runtime, identities);
        assertThat(runtime.riskScan(first).riskComplete()).isTrue();
        RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(new ApplyMarkPriceCommand(identities.instrumentId(first), 100, 2, 1001), runtime, identities);
        assertThat(runtime.firstRiskIncompleteScan().symbolId()).as("a refreshed completed instrumentId must go behind unvisited work").isEqualTo(other);
        // 保留当前扫描顺序和调度修订号的运行时状态物化结果。
        var materialized = RuntimeStateMaterializer.materialize(runtime, identities);
        // 将本次检查点编码成快照后再解码的状态，用来核对恢复结果。
        var restored = TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(materialized), ProductLine.LINEAR_PERPETUAL);
        assertThat(restored.businessStateHash()).isEqualTo(materialized.businessStateHash());
        // 快照恢复后重新建立的身份映射，不能假定整数编号与恢复前相同。
        var restoredIds = new RuntimeIdentityRegistry();
        // 恢复状态重新投影出的运行时，用业务币对编号核对下一份待扫描任务。
        var restoredRuntime = RuntimeStateProjector.project(restored, restoredIds);
        assertThat(restoredIds.instrumentId(restoredRuntime.firstRiskIncompleteScan().symbolId())).isEqualTo(identities.instrumentId(other));
        assertThat(restored.riskState().scans().get(identities.instrumentId(first)).lastScheduledRevision()).isPositive();
    }

    /**
     * 不断刷新另一个币对的价格，也不能饿死已有危险持仓的扫描；有限次继续处理后必须发现该持仓的强平计划。
     */
    @Test
    void continuousUnrelatedPriceRefreshStillAllowsLiquidationDiscovery() {
        // 两个币对中只有币对 2 放入危险持仓的状态，币对 1 专门制造持续价格刷新。
        var state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), instrument("1"));
        state = reducer.registerInstrument(state, instrument("2"));
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 100));
        state = withPosition(state, new CorePositionState("2", "USDT", 10, 100, 1000, 0, 100));
        // 本用例的币对、资产和持仓身份映射，用来读取运行时状态。
        var ids = new RuntimeIdentityRegistry();
        // 由测试状态投影出的可变运行时状态，接受标记价和分批风险处理。
        var runtime = RuntimeStateProjector.project(state, ids);
        RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(new ApplyMarkPriceCommand("2", 1, 1, 1000), runtime, ids);
        // 与运行时拓扑一致的持仓用户索引，续扫不枚举无持仓账户。
        var positions = new PositionUserIndex(state, runtime.topology());
        for (/* 模拟币对 1 连续行情刷新的次数，同时作为递增价格序号。 */ int i = 1; i <= 4; i++) {
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(new ApplyMarkPriceCommand("1", 100, i, 1000+i), runtime, ids);
            RuntimeDerivativeRiskFixture.continueRiskBudget(64, positions, runtime, ids);
        }
        // 风险处理后的运行时物化结果，便于按业务编号核对强平与资金事实。
        var result = RuntimeStateMaterializer.materialize(runtime, ids);
        assertThat(result.riskState().liquidations().values()).anySatisfy(/* 断言正在检查的强平记录，要求其中包含目标用户在目标币对的计划。 */ liquidation -> {
            assertThat(liquidation.userId()).isEqualTo(7);
            assertThat(liquidation.instrumentId()).isEqualTo("2");
        });
    }

    /**
     * 一批共享预算需要跨过没有持仓的币对并找到后面的强平风险，且只做风险判断时资金哈希不能变化。
     */
    @Test
    void sharedBudgetFindsLiquidationPastEmptySymbolsWithoutChangingFunds() {
        // 在多个空币对后仍包含币对 2 危险持仓的测试状态，用于检查共享预算跨币对推进。
        var state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), instrument("1"));
        state = reducer.registerInstrument(state, instrument("4"));
        state = reducer.registerInstrument(state, instrument("2"));
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 100));
        state = withPosition(state, new CorePositionState("2", "USDT", 10, 100, 1000, 0, 100));
        // 风险扫描前的资金哈希，处理结束后必须相同。
        long funds = FundsStateHash.compute(state);
        // 本用例的币对、资产和持仓身份映射，用来读取运行时状态。
        var ids = new RuntimeIdentityRegistry();
        // 由测试状态投影出的可变运行时状态，接受标记价和分批风险处理。
        var runtime = RuntimeStateProjector.project(state, ids);
        // 从初始持仓构造的用户索引，供分批风险扫描定位真正需要估值的账户。
        var positions = new PositionUserIndex(state, runtime.topology());
        for (/* 本轮需要登记行情并安排风险扫描的测试币对编号。 */ String instrumentId : List.of("1", "4", "2"))
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(new ApplyMarkPriceCommand(instrumentId, 1, 1, 1000), runtime, ids);
        // 这次共享预算实际消耗的工作项数量，不能超过 64。
        int work = RuntimeDerivativeRiskFixture.continueRiskBudget(64, positions, runtime, ids);
        assertThat(work).isBetween(3,64);
        assertThat(runtime.firstRiskIncompleteScan()).isNull();
        // 风险处理后的运行时物化结果，便于按业务编号核对强平与资金事实。
        var result = RuntimeStateMaterializer.materialize(runtime, ids);
        assertThat(FundsStateHash.compute(result)).isEqualTo(funds);
        assertThat(result.riskState().liquidations().values()).anySatisfy(/* 断言正在检查的强平记录，要求其中包含目标用户在目标币对的计划。 */ liquidation -> {
            assertThat(liquidation.userId()).isEqualTo(7);
            assertThat(liquidation.instrumentId()).isEqualTo("2");
        });
        // 将本次检查点编码成快照后再解码的状态，用来核对恢复结果。
        var restored = TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(result), result.productLine());
        assertThat(restored.businessStateHash()).isEqualTo(result.businessStateHash());
    }

    /**
     * 标记价使已有持仓规模超过最高风险档位上限时，仍应完成风险估值，不能被新开仓准入的超限拒绝拦住。
     */
    @Test
    void markPriceBeyondHighestRiskBracketStillProducesLiquidationSnapshot() {
        // 最高档名义金额上限只有 100 的合约注册命令，用来制造行情变化后的持仓超档。
        RegisterInstrumentCommand command = new RegisterInstrumentCommand(
                "1", "BTC", "USDT", "USDT", 1, 1, 1,
                new CoreInstrumentRiskLimits(
                        100_000, 10_000_000L, 100, 10_000_000L, 100,
                        List.of(new CoreRiskLimitBracket(100,
                        10_000_000L, 100_000, 50_000))),
                0, 0, 0, -1, 0);
        // 已有持仓规模原本处于档位内的状态，后续新价格会让风险名义金额超出上限。
        TradingCoreState state = reducer.registerInstrument(
                TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), command);
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 100));
        state = withPosition(state, new CorePositionState("1", "USDT", 10, 10, 100, 0, 100));

        // 测试状态转换应用本次标记价后得到的状态，用来检查估值和强平计划。
        TradingCoreState marked = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("1", 100, 1, 1_700_000_000_000L));

        assertThat(marked.riskState().scan().complete()).isTrue();
        assertThat(marked.riskState().snapshots()).containsKey("7:1");
    }

    /**
     * 两个币对同时有大量待扫用户时，应分别保存未完成游标；继续调度后两份扫描都必须完成。
     */
    @Test
    void pendingRiskScansRemainIndependentAcrossSymbols() {
        // 为两个币对分别加入同一批 1300 个用户的持仓，使两份扫描都无法一批完成。
        TradingCoreState state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument("1"));
        state = reducer.registerInstrument(state, instrument("2"));
        for (/* 批量构造或遍历风险账户时的用户编号，每个编号对应一份独立账户状态。 */ long userId = 1; userId <= 1_300; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 200));
            state = withPosition(state, userId, new CorePositionState("1", "USDT", 1, 100, 100, 0, 10));
            state = withPosition(state, userId, new CorePositionState("2", "USDT", 1, 100, 100, 0, 10));
        }

        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 80, 1,
                1_700_000_000_000L));
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("2", 80, 1,
                1_700_000_000_000L));

        assertThat(state.riskState().scans()).containsOnlyKeys("1", "2");
        assertThat(state.riskState().scans().get("1").complete()).isFalse();
        assertThat(state.riskState().scans().get("2").complete()).isFalse();
        state = continueRiskScans(state);
        assertThat(state.riskState().scans().values()).allMatch(CoreRiskState.RiskScan::complete);
    }

    /**
     * 扫描未结束时到达新价格，应记录新行情并完成必要的再扫描，最终所有风险记录都要对应新价格序号。
     */
    @Test
    void newerPriceDuringScanForcesACompleteSecondPass() {
        // 有 1300 份待估值持仓的状态，用两个价格序号制造扫描中途的新行情。
        TradingCoreState state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        for (/* 批量构造或遍历风险账户时的用户编号，每个编号对应一份独立账户状态。 */ long userId = 1; userId <= 1_300; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 100));
            state = withPosition(state, userId, new CorePositionState("1", "USDT", 1, 100, 100, 0, 10));
        }
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 90, 1,
                1_700_000_000_000L));

        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 80, 2,
                1_700_000_000_000L));

        // 第二个价格到达后保存的扫描进度，既记录最新序号，也保留尚未完成的用户遍历。
        CoreRiskState.RiskScan restarted = state.riskState().scans().get("1");
        assertThat(restarted.priceSequence()).isEqualTo(2);
        assertThat(restarted.laneProgress().get(restarted.accountLaneId()).lastUserId()).isPositive();
        assertThat(restarted.complete()).isFalse();
        state = continueRiskScans(state);
        assertThat(state.riskState().scans().get("1").complete()).isTrue();
        assertThat(state.riskState().snapshots().values())
                .allMatch(/* 最终逐个检查的风险快照，价格序号必须全部更新为 2。 */ snapshot -> snapshot.priceSequence() == 2);
    }

    /**
     * 同一结算资产下的全仓持仓共享组合权益，盈利可以抵消另一仓亏损；当共享权益归零时，两仓都应进入强平判断。
     */
    @Test
    void crossMarginUsesPortfolioEquityAcrossSameSettlementAsset() {
        // 用户 7 在两个 USDT 结算币对都有全仓多头的状态，用相反的价格变化制造盈亏抵消。
        TradingCoreState state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument("1"));
        state = reducer.registerInstrument(state, instrument("2"));
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 1_000));
        state = withPosition(state, new CorePositionState("1", "USDT", CoreMarginMode.CROSS,
                CorePositionSide.NET, 10, 100, 1_000, 0, 0));
        state = withPosition(state, new CorePositionState("2", "USDT", CoreMarginMode.CROSS,
                CorePositionSide.NET, 10, 100, 1_000, 0, 0));
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("2", 120, 1,
                1_700_000_000_000L));
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 80, 2,
                1_700_000_000_000L));

        // 币对 1 的风险估值，应共享组合权益而不是只看自身浮亏。
        CoreRiskSnapshot btc = state.riskState().snapshots().get("7:1");
        // 币对 2 的风险估值，应与币对 1 使用同一份组合权益和风险占比。
        CoreRiskSnapshot eth = state.riskState().snapshots().get("7:2");
        assertThat(btc.equityUnits()).isEqualTo(1_000);
        assertThat(eth.equityUnits()).isEqualTo(1_000);
        assertThat(btc.marginRatioPpm()).isEqualTo(200_000);
        assertThat(eth.marginRatioPpm()).isEqualTo(200_000);
        assertThat(state.riskState().liquidations()).isEmpty();

        // 币对 2 再次大跌后的状态，两个全仓持仓都应看到共享权益归零。
        TradingCoreState moved = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("2", 20, 3, 1_700_000_000_000L));
        assertThat(moved.riskState().snapshots().get("7:1").equityUnits()).isEqualTo(0);
        assertThat(moved.riskState().snapshots().get("7:1").status())
                .isEqualTo(CoreRiskStatus.LIQUIDATION);
        assertThat(moved.riskState().liquidations()).hasSize(2);
    }

    /**
     * 每次只允许处理一项工作，检查持仓游标和条件单进度能随快照保存；分批完成的风险及强平记录应等于一次大批量处理。
     */
    @Test
    void crossRiskPersistsPositionCursorAndCompletesWithTheSamePortfolioResult() {
        // 用户 7 有两个全仓持仓的状态，后续手工构造尚未结束的风险与条件单扫描。
        TradingCoreState state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument("1"));
        state = reducer.registerInstrument(state, instrument("2"));
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 1_000));
        state = withPosition(state, new CorePositionState("1", "USDT", CoreMarginMode.CROSS,
                CorePositionSide.NET, 10, 100, 1_000, 0, 0));
        state = withPosition(state, new CorePositionState("2", "USDT", CoreMarginMode.CROSS,
                CorePositionSide.NET, 10, 100, 1_000, 0, 0));
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("2", 120, 1,
                1_700_000_000_000L));
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 90, 2,
                1_700_000_000_000L));

        // 复制出来的行情映射，为币对 1 设置本轮扫描需要采用的新价格。
        Map<String, CoreMarkPriceState> marks = new TreeMap<>(state.riskState().markPrices());
        marks.put("1", new CoreMarkPriceState("1", 80, 3, 1_000));
        // 尚未完成、还带有条件单索引游标的风险扫描记录。
        CoreRiskState.RiskScan scan = new CoreRiskState.RiskScan("1", 0, 3, 3, false,
                false, 1, 80, 91, 100, 80, 1_700_000_000_001L, 0, 0, 0, java.util.List.of());
        // 把新行情和手工扫描游标装入的风险状态，其余风险与强平事实沿用原值。
        CoreRiskState risk = new CoreRiskState(marks, state.riskState().snapshots(),
                state.riskState().liquidations(), Map.of("1", scan),
                state.riskState().nextLiquidationId());
        // 包含新风险状态的根状态，分批与大批量两个计算路径都从这里出发。
        TradingCoreState pending = new TradingCoreState(state.productLine(), state.revision() + 1,
                state.users(), state.orders(), state.instruments(), risk,
                state.treasuryState(), state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(),
                state.clientOrderIndex(), state.triggerOrders());

        // 只推进一个工作项的状态，应保存正在处理的用户和持仓游标。
        TradingCoreState firstPage = reducer.continueRiskScan(pending, 1);
        var scanProgress = firstPage.riskState().scan();
        var laneProgress = scanProgress.laneProgress().get(scanProgress.accountLaneId());
        assertThat(laneProgress.userId()).isEqualTo(7);
        assertThat(laneProgress.positionCursor()).isEqualTo("1");
        assertThat(firstPage.riskState().scan().triggerComplete()).isFalse();
        // 将本次检查点编码成快照后再解码的状态，用来核对恢复结果。
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(firstPage), ProductLine.LINEAR_PERPETUAL);
        assertThat(restored.riskState().scan()).isEqualTo(firstPage.riskState().scan());
        assertThat(restored.businessStateHash()).isEqualTo(firstPage.businessStateHash());

        // 从快照恢复后反复按一个工作项推进的状态。
        TradingCoreState paged = restored;
        for (/* 限制分批继续次数的循环计数，防止游标不推进时测试无限运行。 */ int page = 0; page < 10 && !paged.riskState().scan().riskComplete(); page++) {
            paged = reducer.continueRiskScan(paged, 1);
        }
        // 从相同起点一次给足 4096 项预算的结果，作为分批算法的参照。
        TradingCoreState unpaged = reducer.continueRiskScan(pending, 4_096);
        assertThat(paged.riskState().scan().riskComplete()).isTrue();
        assertThat(paged.riskState().snapshots()).isEqualTo(unpaged.riskState().snapshots());
        assertThat(paged.riskState().liquidations()).isEqualTo(unpaged.riskState().liquidations());
    }

    /**
     * 启动实际账户 Lane，将运行时标记价及逐批继续结果与测试状态转换逐页比对，并在结束时关闭线程。
     */
    @Test
    void runtimeRiskMatchesAuthoritativeMarkPriceAndPagedContinuation() {
        // 待扫描的 260 个账户状态，用于逐批比对真实 Lane 运行时和测试状态转换。
        TradingCoreState state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        for (/* 批量构造或遍历风险账户时的用户编号，每个编号对应一份独立账户状态。 */ long userId = 1; userId <= 260; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 100));
            state = withPosition(state, userId, new CorePositionState("1", "USDT", 1, 100, 100, 0, 10));
        }
        // 统一用于两条处理路径的标记价下跌命令。
        ApplyMarkPriceCommand command = new ApplyMarkPriceCommand("1", 80, 1,
                1_700_000_000_000L);
        // 测试状态转换的当前参照结果，每批继续后都与运行时比对。
        TradingCoreState first = reducer.applyMarkPrice(state, command);
        // 测试使用的字符串编号与运行时整数编号映射，投影和一致性比对必须共用它。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 由测试状态投影出的可变运行时状态，接受标记价和分批风险处理。
        TradingAccountRuntime runtime = RuntimeStateProjector.project(state, identities);
        runtime.startAccountLanes();
        try {
            RuntimeTestStateTransitions.applyRiskMarkPrice(state, command, runtime, identities);
            assertThat(RuntimeStateMaterializer.materialize(runtime, identities))
                    .isEqualTo(first);
            RuntimeStateParityChecker.assertMatches(first, identities, runtime);

            while (!first.riskState().scan().riskComplete()) {
                // 本批继续扫描开始前的参照状态，状态转换和运行时都从同一业务进度推进。
                TradingCoreState before = first;
                first = reducer.continueRiskScan(before, 64);
                RuntimeTestStateTransitions.applyRiskContinuation(before, 64, runtime, identities);
                RuntimeStateParityChecker.assertMatches(first, identities, runtime);
            }
        } finally {
            runtime.close();
        }
        assertThat(first.riskState().snapshots()).hasSize(260);
        assertThat(first.riskState().liquidations()).isEmpty();
    }

    /**
     * 让 32 个账户触发强平，以每批 7 项继续处理；运行时与测试状态转换必须逐页一致，最终强平编号恰好消耗 1 到 32。
     */
    @Test
    void laneRiskAssignsDeterministicLiquidationIdsAcrossPages() {
        // 32 个用户都将达到强平条件的持仓状态，用来核对跨 Lane、跨批的编号分配。
        TradingCoreState state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        for (/* 批量构造或遍历风险账户时的用户编号，每个编号对应一份独立账户状态。 */ long userId = 1; userId <= 32; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 100));
            state = withPosition(state, userId, new CorePositionState("1", "USDT", 10, 100, 1_000, 0, 100));
        }
        // 同时让这批用户产生强平风险的标记价命令。
        ApplyMarkPriceCommand command = new ApplyMarkPriceCommand("1", 80, 1,
                1_700_000_000_000L);
        // 测试状态转换逐批给出的参照结果，包含确定顺序的强平编号。
        TradingCoreState authoritative = reducer.applyMarkPrice(state, command);
        // 测试使用的字符串编号与运行时整数编号映射，投影和一致性比对必须共用它。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 由测试状态投影出的可变运行时状态，接受标记价和分批风险处理。
        TradingAccountRuntime runtime = RuntimeStateProjector.project(state, identities);
        runtime.startAccountLanes();
        try {
            RuntimeTestStateTransitions.applyRiskMarkPrice(state, command, runtime, identities);
            RuntimeStateParityChecker.assertMatches(authoritative, identities, runtime);
            while (!authoritative.riskState().scan().riskComplete()) {
                // 本批继续扫描开始前的参照状态，状态转换和运行时都从同一业务进度推进。
                TradingCoreState before = authoritative;
                authoritative = reducer.continueRiskScan(before, 7);
                RuntimeTestStateTransitions.applyRiskContinuation(before, 7, runtime, identities);
                RuntimeStateParityChecker.assertMatches(authoritative, identities, runtime);
            }
        } finally {
            runtime.close();
        }
        assertThat(authoritative.riskState().liquidations()).hasSize(32);
        assertThat(authoritative.riskState().nextLiquidationId()).isEqualTo(33);
    }

    /**
     * 旧价格扫描未完成时收到新价格，运行时与测试状态转换要保留相同的起始序号、新价格序号及未完成状态。
     */
    @Test
    void runtimeRiskPreservesOldPassBeforeRestartingLatestPrice() {
        // 260 个账户在旧价格下尚未完成扫描的状态，新价格会在此时到达。
        TradingCoreState state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        for (/* 批量构造或遍历风险账户时的用户编号，每个编号对应一份独立账户状态。 */ long userId = 1; userId <= 260; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 100));
            state = withPosition(state, userId, new CorePositionState("1", "USDT", 1, 100, 100, 0, 10));
        }
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 90, 1,
                1_700_000_000_000L));
        // 序号为 2 的最新价格命令，需要保留旧扫描起始序号再推进到这份价格。
        ApplyMarkPriceCommand latest = new ApplyMarkPriceCommand("1", 80, 2,
                1_700_000_000_001L);
        // 测试状态转换接收新价格后的结果，用来比对运行时保留的扫描进度。
        TradingCoreState after = reducer.applyMarkPrice(state, latest);
        // 测试使用的字符串编号与运行时整数编号映射，投影和一致性比对必须共用它。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 由测试状态投影出的可变运行时状态，接受标记价和分批风险处理。
        TradingAccountRuntime runtime = RuntimeDerivativeRiskFixture.simulateMarkPrice(
                state, latest, identities);
        RuntimeStateParityChecker.assertMatches(after, identities, runtime);
        assertThat(after.riskState().scan().scanStartPriceSequence()).isEqualTo(1);
        assertThat(after.riskState().scan().priceSequence()).isEqualTo(2);
        assertThat(after.riskState().scan().riskComplete()).isFalse();
    }

    /**
     * 按每批 64 项反复推进未完成扫描，最多尝试一万批；返回最终状态，由调用测试继续断言是否完成。
     * @param state 要继续扫描或加入持仓的不可变测试状态
     */
    private TradingCoreState continueRiskScans(TradingCoreState state) {
        for (/* 已尝试推进的批次数量，到一万批就停止并交给测试断言判断。 */ int page = 0; page < 10_000 && state.riskState().scans().values().stream().anyMatch(scan -> !scan.complete()); page++) {
            state = reducer.continueRiskScan(state, 64);
        }
        return state;
    }

    /**
     * 创建固定编号为 1 的测试合约，明确传入合约类型及结算精度，用相同的基础风险参数比较线性和反向产品。
     * @param type 要注册的测试合约类型
     * @param settleScale 测试结算资产的缩放精度，反向合约需要用它换算金额
     */
    private static RegisterInstrumentCommand instrument(ContractType type, long settleScale) {
        return new RegisterInstrumentCommand(
                "1", "BTC", "USDT", "USDT", 1, 1, settleScale,
                (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 100_000)))),
                0, 0, 0, -1, 0);
    }

    /**
     * 创建指定编号的线性永续测试合约，编号 1 使用 BTC，其余使用 ETH，便于构造多币对组合。
     * @param instrumentId 需要创建的测试币对编号
     */
    private static RegisterInstrumentCommand instrument(String instrumentId) {
        return new RegisterInstrumentCommand(
                instrumentId, (instrumentId.equals("1") ? "BTC" : "ETH"), "USDT", "USDT", 1, 1, 1,
                new CoreInstrumentRiskLimits(
                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 100_000))),
                0, 0, 0, -1, 0);
    }

    /**
     * 为测试用户 7 加入一份持仓，并将其保证金从可用金额转入冻结金额。
     * @param state 要继续扫描或加入持仓的不可变测试状态
     * @param position 要放入测试账户的持仓及对应保证金金额
     */
    private static TradingCoreState withPosition(TradingCoreState state, CorePositionState position) {
        return withPosition(state, 7, position);
    }

    /**
     * 复制指定测试账户的余额和持仓映射，转入持仓保证金后替换用户及根状态，并增加对应修订号。
     * @param state 要继续扫描或加入持仓的不可变测试状态
     * @param userId 需要加入这份持仓的测试用户编号
     * @param position 要放入测试账户的持仓及对应保证金金额
     */
    private static TradingCoreState withPosition(
            TradingCoreState state,
            long userId,
            CorePositionState position) {
        // 加入测试持仓前的用户记录，余额和已有冻结、持仓都从它复制。
        CoreUserState current = state.user(userId);
        // 该用户的余额映射副本，将持仓保证金从可用转到冻结后装回新用户。
        Map<String, AssetBalance> balances = new TreeMap<>(current.balances());
        // 保证金资产原有的余额，新增持仓只调整这项资产的可用与冻结部分。
        AssetBalance marginBalance = balances.get(position.marginAsset());
        balances.put(position.marginAsset(), new AssetBalance(position.marginAsset(),
                Math.subtractExact(marginBalance.availableUnits(), position.positionMarginUnits()),
                Math.addExact(marginBalance.lockedUnits(), position.positionMarginUnits())));
        // 该用户的持仓映射副本，按持仓业务键放入新持仓。
        Map<String, CorePositionState> positions = new TreeMap<>(current.positions());
        positions.put(position.key(), position);
        // 包含新余额和持仓、修订号已增加的用户记录。
        CoreUserState user = new CoreUserState(state.productLine(), userId, current.revision() + 1,
                balances, current.reservations(), positions);
        // 根状态的用户映射副本，只替换本次需要加入持仓的用户。
        Map<Long, CoreUserState> users = new TreeMap<>(state.users());
        users.put(userId, user);
        return new TradingCoreState(state.productLine(), state.revision() + 1, users, state.orders(),
                state.instruments(), state.riskState(), state.treasuryState());
    }
}
