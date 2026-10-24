package com.surprising.aeron.service.core;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreMarkPriceState;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.model.CoreRiskSnapshot;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.adl.RuntimeAdlExecution;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.funding.RuntimePerpetualFundingProcessor;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor;
import com.surprising.aeron.service.liquidation.RuntimeLiquidationResolution;
import com.surprising.aeron.service.order.state.RuntimeTestStateTransitions;
import com.surprising.aeron.service.snapshot.state.*;
import com.surprising.aeron.service.snapshot.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.snapshot.state.TradingStateSnapshotCodec;
import com.surprising.aeron.service.treasury.RuntimeInsuranceFundStateTransitions;
import com.surprising.aeron.support.math.*;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.surprising.aeron.service.matcher.MatcherEventFixtures.trade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 永续资金矩阵：正反向各配全仓、逐仓，固定七十二场景行核对订单、成交、资金费、强平、保险、ADL、抵押隔离和快照。另有账户 Lane 与原 System Ledger 的异步用例。大多数路径通过冷转换或显式事件，不是集群/网络端到端测试；对照复用现有业务实现，手工期望金额与状态一致性检查各有自己的覆盖范围。
 */
class CorePerpetualFinancialMatrixTest {

    /** 矩阵主用户一百零一，通常持有多头或作为吃单方。 */
    private static final long USER_ID = 101;
    /** 第一做市用户二百零二，通常作为对向持仓或挂单方。 */
    private static final long MAKER_ID = 202;
    /** 第二做市用户二百零三，供跨 Lane 和多个 ADL 候选测试。 */
    private static final long SECOND_MAKER_ID = 203;
    /** 本矩阵默认数字标的文本一。 */
    private static final String SYMBOL = "1";
    /** 默认入场价一百，按价格 tick 保存。 */
    private static final long ENTRY_PRICE = 100;
    /** 默认持仓或成交数量十，按数量步保存。 */
    private static final long QUANTITY = 10;
    /** 手工建立默认持仓时冻结的保证金一百。 */
    private static final long POSITION_MARGIN = 100;
    /** 常规场景每个用户注入的默认结算资产一千。 */
    private static final long DEFAULT_WALLET = 1_000;

    /** 正反向永续分别搭配全仓和逐仓的四个测试组合。 */
    private static final List<Variant> VARIANTS = List.of(
            new Variant(ContractType.LINEAR_PERPETUAL, CoreMarginMode.CROSS, "BTC", "USDT", "USDT", 1, 1),
            new Variant(ContractType.LINEAR_PERPETUAL, CoreMarginMode.ISOLATED, "BTC", "USDT", "USDT", 1, 1),
            new Variant(ContractType.INVERSE_PERPETUAL, CoreMarginMode.CROSS, "BTC", "USD", "BTC", 100, 100),
            new Variant(ContractType.INVERSE_PERPETUAL, CoreMarginMode.ISOLATED, "BTC", "USD", "BTC", 100, 100));

    /** 每个组合必须执行的十八种资金或拒绝/隔离场景名。 */
    private static final List<String> SCENARIOS = List.of(
            "OPEN_CLOSE_REVERSAL",
            "TIER_CHANGE",
            "MAKER_TAKER_FEES",
            "FUNDING_POSITIVE",
            "FUNDING_NEGATIVE",
            "STALE_MARK",
            "RISK_SCAN",
            "LIQUIDATION_PARTIAL",
            "LIQUIDATION_FULL",
            "LIQUIDATION_FEE_CAP",
            "INSURANCE_FULL",
            "INSURANCE_PARTIAL",
            "ADL_ORDER",
            "ADL_COVERAGE",
            "SNAPSHOT_CONTINUATION",
            "CROSS_LINE_REJECTED",
            "ISOLATED_COLLATERAL_LEAKAGE",
            "ISOLATED_FREE_COLLATERAL_LEAKAGE");

    /** 清单前十五种场景，用于常规资金链路完整性断言，其中也包含旧价格拒绝。 */
    private static final List<String> POSITIVE_SCENARIOS = SCENARIOS.subList(0, 15);
    /** 清单末三种跨线拒绝及抵押隔离场景，用于拒绝边界完整性断言。 */
    private static final List<String> NEGATIVE_SCENARIOS = SCENARIOS.subList(15, SCENARIOS.size());

    /** 显式固定的四组合乘十八场景共七十二个必需键，独立于运行时遍历得到的行集合。 */
    private static final Set<String> REQUIRED_ROWS = Set.of(
            "LINEAR_PERPETUAL:CROSS:OPEN_CLOSE_REVERSAL",
            "LINEAR_PERPETUAL:CROSS:TIER_CHANGE",
            "LINEAR_PERPETUAL:CROSS:MAKER_TAKER_FEES",
            "LINEAR_PERPETUAL:CROSS:FUNDING_POSITIVE",
            "LINEAR_PERPETUAL:CROSS:FUNDING_NEGATIVE",
            "LINEAR_PERPETUAL:CROSS:STALE_MARK",
            "LINEAR_PERPETUAL:CROSS:RISK_SCAN",
            "LINEAR_PERPETUAL:CROSS:LIQUIDATION_PARTIAL",
            "LINEAR_PERPETUAL:CROSS:LIQUIDATION_FULL",
            "LINEAR_PERPETUAL:CROSS:LIQUIDATION_FEE_CAP",
            "LINEAR_PERPETUAL:CROSS:INSURANCE_FULL",
            "LINEAR_PERPETUAL:CROSS:INSURANCE_PARTIAL",
            "LINEAR_PERPETUAL:CROSS:ADL_ORDER",
            "LINEAR_PERPETUAL:CROSS:ADL_COVERAGE",
            "LINEAR_PERPETUAL:CROSS:SNAPSHOT_CONTINUATION",
            "LINEAR_PERPETUAL:CROSS:CROSS_LINE_REJECTED",
            "LINEAR_PERPETUAL:CROSS:ISOLATED_COLLATERAL_LEAKAGE",
            "LINEAR_PERPETUAL:CROSS:ISOLATED_FREE_COLLATERAL_LEAKAGE",
            "LINEAR_PERPETUAL:ISOLATED:OPEN_CLOSE_REVERSAL",
            "LINEAR_PERPETUAL:ISOLATED:TIER_CHANGE",
            "LINEAR_PERPETUAL:ISOLATED:MAKER_TAKER_FEES",
            "LINEAR_PERPETUAL:ISOLATED:FUNDING_POSITIVE",
            "LINEAR_PERPETUAL:ISOLATED:FUNDING_NEGATIVE",
            "LINEAR_PERPETUAL:ISOLATED:STALE_MARK",
            "LINEAR_PERPETUAL:ISOLATED:RISK_SCAN",
            "LINEAR_PERPETUAL:ISOLATED:LIQUIDATION_PARTIAL",
            "LINEAR_PERPETUAL:ISOLATED:LIQUIDATION_FULL",
            "LINEAR_PERPETUAL:ISOLATED:LIQUIDATION_FEE_CAP",
            "LINEAR_PERPETUAL:ISOLATED:INSURANCE_FULL",
            "LINEAR_PERPETUAL:ISOLATED:INSURANCE_PARTIAL",
            "LINEAR_PERPETUAL:ISOLATED:ADL_ORDER",
            "LINEAR_PERPETUAL:ISOLATED:ADL_COVERAGE",
            "LINEAR_PERPETUAL:ISOLATED:SNAPSHOT_CONTINUATION",
            "LINEAR_PERPETUAL:ISOLATED:CROSS_LINE_REJECTED",
            "LINEAR_PERPETUAL:ISOLATED:ISOLATED_COLLATERAL_LEAKAGE",
            "LINEAR_PERPETUAL:ISOLATED:ISOLATED_FREE_COLLATERAL_LEAKAGE",
            "INVERSE_PERPETUAL:CROSS:OPEN_CLOSE_REVERSAL",
            "INVERSE_PERPETUAL:CROSS:TIER_CHANGE",
            "INVERSE_PERPETUAL:CROSS:MAKER_TAKER_FEES",
            "INVERSE_PERPETUAL:CROSS:FUNDING_POSITIVE",
            "INVERSE_PERPETUAL:CROSS:FUNDING_NEGATIVE",
            "INVERSE_PERPETUAL:CROSS:STALE_MARK",
            "INVERSE_PERPETUAL:CROSS:RISK_SCAN",
            "INVERSE_PERPETUAL:CROSS:LIQUIDATION_PARTIAL",
            "INVERSE_PERPETUAL:CROSS:LIQUIDATION_FULL",
            "INVERSE_PERPETUAL:CROSS:LIQUIDATION_FEE_CAP",
            "INVERSE_PERPETUAL:CROSS:INSURANCE_FULL",
            "INVERSE_PERPETUAL:CROSS:INSURANCE_PARTIAL",
            "INVERSE_PERPETUAL:CROSS:ADL_ORDER",
            "INVERSE_PERPETUAL:CROSS:ADL_COVERAGE",
            "INVERSE_PERPETUAL:CROSS:SNAPSHOT_CONTINUATION",
            "INVERSE_PERPETUAL:CROSS:CROSS_LINE_REJECTED",
            "INVERSE_PERPETUAL:CROSS:ISOLATED_COLLATERAL_LEAKAGE",
            "INVERSE_PERPETUAL:CROSS:ISOLATED_FREE_COLLATERAL_LEAKAGE",
            "INVERSE_PERPETUAL:ISOLATED:OPEN_CLOSE_REVERSAL",
            "INVERSE_PERPETUAL:ISOLATED:TIER_CHANGE",
            "INVERSE_PERPETUAL:ISOLATED:MAKER_TAKER_FEES",
            "INVERSE_PERPETUAL:ISOLATED:FUNDING_POSITIVE",
            "INVERSE_PERPETUAL:ISOLATED:FUNDING_NEGATIVE",
            "INVERSE_PERPETUAL:ISOLATED:STALE_MARK",
            "INVERSE_PERPETUAL:ISOLATED:RISK_SCAN",
            "INVERSE_PERPETUAL:ISOLATED:LIQUIDATION_PARTIAL",
            "INVERSE_PERPETUAL:ISOLATED:LIQUIDATION_FULL",
            "INVERSE_PERPETUAL:ISOLATED:LIQUIDATION_FEE_CAP",
            "INVERSE_PERPETUAL:ISOLATED:INSURANCE_FULL",
            "INVERSE_PERPETUAL:ISOLATED:INSURANCE_PARTIAL",
            "INVERSE_PERPETUAL:ISOLATED:ADL_ORDER",
            "INVERSE_PERPETUAL:ISOLATED:ADL_COVERAGE",
            "INVERSE_PERPETUAL:ISOLATED:SNAPSHOT_CONTINUATION",
            "INVERSE_PERPETUAL:ISOLATED:CROSS_LINE_REJECTED",
            "INVERSE_PERPETUAL:ISOLATED:ISOLATED_COLLATERAL_LEAKAGE",
            "INVERSE_PERPETUAL:ISOLATED:ISOLATED_FREE_COLLATERAL_LEAKAGE");

    /** 复用现有业务转换的冷状态辅助；用于入口一致性对照，不是独立财务公式实现。 */
    private final RuntimeTestStateTransitions reducer = new RuntimeTestStateTransitions();

    /**
     * 为默认正向全仓组合建立分属不同账户 Lane 的对向持仓，直接应用资金费并比较支付事实、完整物化状态和哈希；本用例未启动账户或账本工作线程。
     */
    @Test
    void crossLaneFundingReturnsOwnerContributionsBeforeSequencerTreasuryApply() {
        // 当前执行的永续类型与保证金模式组合
        Variant variant = VARIANTS.getFirst();
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE,
                DEFAULT_WALLET, POSITION_MARGIN);
        opening = withPosition(opening, variant, SECOND_MAKER_ID, -QUANTITY, ENTRY_PRICE,
                DEFAULT_WALLET, POSITION_MARGIN);
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = mark(opening, variant, ENTRY_PRICE, 1);
        // 同标的资金费结算七百零一、费率百分之十的命令
        ApplyFundingCommand command = new ApplyFundingCommand(701, SYMBOL, 100_000);
        // 冷辅助返回的资金费状态、支付事实及进度，作为直接入口对照
        RuntimeTestStateTransitions.FundingApplication expected = reducer.applyFundingWithFacts(marked, command);
        // 本次运行时投影独用的整数身份表，物化与对照使用同一份
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (/* 本次验证直接转换或异步控制使用的投影运行时 */ TradingAccountRuntime runtime = RuntimeStateProjector.project(marked, identities)) {
            assertThat(runtime.topology().accountLaneId(USER_ID))
                    .isNotEqualTo(runtime.topology().accountLaneId(SECOND_MAKER_ID));
            // 直接资金费处理返回的支付事实和进度，不是另一个运行时快照
            RuntimePerpetualFundingProcessor.FundingResult actual =
                    RuntimePerpetualFundingFixture.applyRuntime(command, List.of(USER_ID, SECOND_MAKER_ID), runtime,
                            identities);

            RuntimeStateParityChecker.assertMatches(expected.state(), identities, runtime);
            assertThat(RuntimeStateMaterializer.materialize(runtime, identities))
                    .isEqualTo(expected.state());
            assertThat(actual.payments()).isEqualTo(expected.payments());
        }
    }

    /**
     * 四种永续组合先强平并由保险覆盖部分亏空，再启动账户 Lane 异步减第二做市用户仓位，五秒内完成后与冷路径完整状态对比，最后关闭运行时。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("adlVariants")
    void crossLaneAdlMutatesOnlyTheTargetAndLiquidationOwners(Variant variant) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = adlSetup(variant);
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = mark(opening, variant, 1, 1);
        // 强平已执行且持仓资金已更新的冷状态，后续继续保险或 ADL
        TradingCoreState liquidated = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, liquidationSequence(marked), 1, 0));
        // 本轮先注入保险、再由分配政策算定的实际覆盖量
        long insuranceCoverage = variant.type() == ContractType.LINEAR_PERPETUAL ? 25 : 50_000;
        // 强平状态上已向保险基金注资后的冷状态
        TradingCoreState funded = reducer.adjustInsuranceFund(liquidated,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand(
                        variant.settleAsset(), insuranceCoverage));
        insuranceCoverage = RuntimeTestStateTransitions.expectedInsuranceCoverage(funded, 1);
        // 保险确认后仍需 ADL 补亏的冷状态，作为自动减仓起点
        TradingCoreState beforeAdl = reducer.resolveLiquidation(funded,
                new ResolveLiquidationCommand(1, ResolveLiquidationCommand.Resolution.INSURANCE,
                        insuranceCoverage));
        // 本次需要由 ADL 覆盖或纳入保险统计扣项的剩余强平亏空
        long residual = linearOrInverse(variant, 700, 40_000);
        // 对第二做市用户空头减五份并覆盖本场景剩余亏空的 ADL 命令
        ExecuteAdlCommand command = new ExecuteAdlCommand(1, SECOND_MAKER_ID, SYMBOL, variant.marginMode(),
                CorePositionSide.NET, -QUANTITY, 150,
                beforeAdl.riskState().markPrices().get(SYMBOL).priceSequence(), 5, residual);
        // 冷路径执行同一 ADL 后的完整预期状态
        TradingCoreState expected = reducer.executeAdl(beforeAdl, command);
        // 本次运行时投影独用的整数身份表，物化与对照使用同一份
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 本次验证直接转换或异步控制使用的投影运行时
        TradingAccountRuntime runtime = RuntimeStateProjector.project(beforeAdl, identities);
        runtime.startAccountLanes();
        try {
            // 已派到账户 Lane 的 ADL 可轮询工作
            var work = RuntimeAdlExecution.begin(null, command, runtime, identities);
            // 该异步 ADL 工作必须完成的单调时钟五秒期限
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!work.getAsBoolean()) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.onSpinWait();
            }

            RuntimeStateParityChecker.assertMatches(expected, identities, runtime);
        } finally {
            runtime.close();
        }
    }

    /**
     * 四种永续组合分别用单 Lane 和双 Lane 推进资金费并与冷结果比较，覆盖同 Lane 及跨 Lane 收付抵消；随后确认 Owner 不能直接读取财库资金费余额。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param accountLaneCount 本场景账户 Lane 数量
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("accountLaneVariants")
    void fundingRunsOnOriginalSystemLedgerWithoutOwnerMoneyAccess(Variant variant, int accountLaneCount) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, DEFAULT_WALLET, POSITION_MARGIN);
        opening = withPosition(opening, variant, SECOND_MAKER_ID, -QUANTITY, ENTRY_PRICE, DEFAULT_WALLET, POSITION_MARGIN);
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = mark(opening, variant, ENTRY_PRICE, 1);
        // 交给原账本任务的资金费结算七百零一、费率百分之十命令
        var command = new ApplyFundingCommand(701, SYMBOL, 100_000);
        // 冷辅助返回的资金费状态及支付事实，作为账本路径对照
        var expected = reducer.applyFundingWithFacts(marked, command);
        // 本次运行时投影独用的整数身份表，物化与对照使用同一份
        var topology = new LaneTopology(1, accountLaneCount, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED,
                LaneTopology.DEFAULT_MATCHER_WINDOW_SIZE, LaneTopology.DEFAULT_QUEUE_CAPACITY,
                LaneTopology.DEFAULT_QUEUE_CAPACITY);
        assertThat(topology.accountLaneId(USER_ID) == topology.accountLaneId(SECOND_MAKER_ID))
                .as("funding payer and receiver share a lane only in the single-lane fixture")
                .isEqualTo(accountLaneCount == 1);
        var identities = new RuntimeIdentityRegistry(topology);
        try (/* 本次验证直接转换或异步控制使用的投影运行时 */ var runtime = RuntimeStateProjector.project(marked, identities, topology)) {
            runtime.startAccountLanes(); runtime.startSystemLedgerLane(); runtime.enterAsynchronousCommandScope();
            try {
                // 在原 System Ledger 作用域内准备的资金费续步工作
                var work = RuntimePerpetualFundingProcessor.prepare(null, command, List.of(USER_ID, SECOND_MAKER_ID), runtime,
                        identities);
                LedgerControlTestSupport.await(runtime, work::poll);
                assertThat(work.poll()).isTrue();
                assertThat(work.result().payments()).isEqualTo(expected.payments());
                assertThat(LedgerControlTestSupport.commit(runtime, identities, false)).isEqualTo(expected.state());
                assertThatThrownBy(() -> runtime.treasury().fundingResidual(identities.assetId(variant.settleAsset())))
                        .isInstanceOf(IllegalStateException.class);
            } finally { runtime.exitAsynchronousCommandScope(); }
        }
    }

    /**
     * 四种永续组合分别用单 Lane 和双 Lane 推进强平执行、保险注资、保险确认及 ADL，每步提交后都与冷转换完整状态比较，并检查账户 Lane 健康。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param accountLaneCount 账户 Lane 数量，单 Lane 验证同 Lane 减仓，双 Lane 验证跨 Lane 交接
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("accountLaneVariants")
    void liquidationInsuranceAndAdlUseOriginalSystemLedgerAcrossAccountLanes(Variant variant, int accountLaneCount) {
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = mark(adlSetup(variant), variant, 1, 1);
        // 本场景强平计划一的执行命令，采用原触发序列和固定执行价
        var execute = new ExecuteLiquidationCommand(1, liquidationSequence(marked), 1, 0);
        // 随强平、保险注资、保险确认和 ADL 逐步更新的冷路径预期状态
        TradingCoreState expected = reducer.executeLiquidation(marked, execute);
        // 本次运行时投影独用的整数身份表，物化与对照使用同一份
        var topology = new LaneTopology(1, accountLaneCount, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED,
                LaneTopology.DEFAULT_MATCHER_WINDOW_SIZE, LaneTopology.DEFAULT_QUEUE_CAPACITY,
                LaneTopology.DEFAULT_QUEUE_CAPACITY);
        assertThat(topology.accountLaneId(USER_ID) == topology.accountLaneId(SECOND_MAKER_ID))
                .as("ADL source and target share a lane only in the single-lane fixture")
                .isEqualTo(accountLaneCount == 1);
        var identities = new RuntimeIdentityRegistry(topology);
        try (/* 本次验证直接转换或异步控制使用的投影运行时 */ var runtime = RuntimeStateProjector.project(marked, identities, topology)) {
            runtime.startAccountLanes(); runtime.startSystemLedgerLane(); runtime.enterAsynchronousCommandScope();
            try {
                // 按原账户及账本路径准备的可轮询强平执行工作
                var execution = RuntimeDerivativeLiquidationProcessor.beginExecution(null, execute, List.of(), 0, runtime, identities);
                LedgerControlTestSupport.await(runtime, execution);
                assertThat(LedgerControlTestSupport.commit(runtime, identities, false)).isEqualTo(expected);
                // 给保险外部注入的数量，随后按分配政策确定实际覆盖
                long coverage = variant.type() == ContractType.LINEAR_PERPETUAL ? 25 : 50_000;
                // 本次向结算资产保险基金增加指定数量的命令
                var adjustment = new com.surprising.aeron.protocol.AdjustInsuranceFundCommand(variant.settleAsset(), coverage);
                expected = reducer.adjustInsuranceFund(expected, adjustment);
                LedgerControlTestSupport.await(runtime, RuntimeInsuranceFundStateTransitions.prepare(runtime, identities, adjustment));
                assertThat(LedgerControlTestSupport.commit(runtime, identities, true)).isEqualTo(expected);
                coverage = RuntimeTestStateTransitions.expectedInsuranceCoverage(expected, 1);
                // 按政策金额确认保险覆盖的强平结果命令
                var resolve = new ResolveLiquidationCommand(1, ResolveLiquidationCommand.Resolution.INSURANCE, coverage);
                expected = reducer.resolveLiquidation(expected, resolve);
                LedgerControlTestSupport.await(runtime, RuntimeLiquidationResolution.begin(null, resolve, runtime, identities, List.of(1L)));
                assertThat(LedgerControlTestSupport.commit(runtime, identities, false)).isEqualTo(expected);
                // 覆盖剩余亏空的 ADL 命令，目标为第二做市用户空头
                var adl = new ExecuteAdlCommand(1, SECOND_MAKER_ID, SYMBOL, variant.marginMode(), CorePositionSide.NET,
                        -QUANTITY, 150, expected.riskState().markPrices().get(SYMBOL).priceSequence(), 5, linearOrInverse(variant, 700, 40_000));
                expected = reducer.executeAdl(expected, adl);
                LedgerControlTestSupport.await(runtime, RuntimeAdlExecution.begin(null, adl, runtime, identities));
                assertThat(LedgerControlTestSupport.commit(runtime, identities, false)).isEqualTo(expected);
                runtime.assertAccountLanesHealthy();
            } finally { runtime.exitAsynchronousCommandScope(); }
        }
    }

    /** 四种永续组合分别在同 Lane、跨 Lane 批量强平两个账户，核对原批费率、实际费用上限和完整账本结果。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("accountLaneVariants")
    void batchLiquidationKeepsNonzeroFeeAcrossAccountLanes(Variant variant, int accountLaneCount) {
        var opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, 180, POSITION_MARGIN);
        opening = withPosition(opening, variant, SECOND_MAKER_ID, QUANTITY, ENTRY_PRICE, 180, POSITION_MARGIN);
        var marked = mark(opening, variant, 90, 1);
        var actions = marked.riskState().liquidations().values().stream()
                .sorted(Comparator.comparingLong(CoreLiquidationState::liquidationId))
                .map(plan -> new com.surprising.aeron.protocol.ExecuteLiquidationBatchAction(plan.liquidationId(),
                        plan.userId(), plan.instrumentId(), plan.triggerPriceSequence(), 90, 0)).toList();
        assertThat(actions).hasSize(2);
        var batch = new com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand(actions, 1_024, 100_000, null, 0);
        TradingCoreState expected = marked;
        for (var action : actions) expected = reducer.executeLiquidation(expected, new ExecuteLiquidationCommand(
                action.liquidationId(), action.triggerPriceSequence(), action.executionPriceTicks(), batch.liquidationFeeRatePpm()));
        var topology = new LaneTopology(1, accountLaneCount, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED,
                LaneTopology.DEFAULT_MATCHER_WINDOW_SIZE, LaneTopology.DEFAULT_QUEUE_CAPACITY,
                LaneTopology.DEFAULT_QUEUE_CAPACITY);
        assertThat(topology.accountLaneId(USER_ID) == topology.accountLaneId(SECOND_MAKER_ID))
                .isEqualTo(accountLaneCount == 1);
        var identities = new RuntimeIdentityRegistry(topology);
        try (var runtime = RuntimeStateProjector.project(marked, identities, topology)) {
            runtime.startAccountLanes(); runtime.startSystemLedgerLane(); runtime.enterAsynchronousCommandScope();
            try {
                var requests = actions.stream().map(action ->
                        new RuntimeDerivativeLiquidationProcessor.ExecutionRequest(action, List.of(), 0)).toList();
                var work = RuntimeDerivativeLiquidationProcessor.beginExecutionBatch(
                        requests, batch.liquidationFeeRatePpm(), runtime, identities);
                LedgerControlTestSupport.await(runtime, work);
                assertThat(work.getAsBoolean()).isTrue();
                var actual = LedgerControlTestSupport.commit(runtime, identities, false);
                assertThat(actual).isEqualTo(expected);
                boolean isolated = variant.marginMode() == CoreMarginMode.ISOLATED;
                long fee = isolated ? 0 : linearOrInverse(variant, 80, 69);
                assertThat(actual.riskState().liquidations().values()).allSatisfy(value -> {
                    assertThat(value.liquidationFeeRatePpm()).isEqualTo(100_000);
                    assertThat(value.liquidationFeeUnits()).isEqualTo(fee);
                });
                assertThat(actual.treasuryState().insuranceBalances()).containsEntry(variant.settleAsset(), isolated ? 200L : 360L);
                assertThatThrownBy(() -> runtime.treasury().insurance(identities.assetId(variant.settleAsset())))
                        .isInstanceOf(IllegalStateException.class);
                runtime.assertAccountLanesHealthy();
            } finally { runtime.exitAsynchronousCommandScope(); }
        }
    }

    /**
     * 返回正反向永续与全仓逐仓的四种组合，供账本和 ADL 参数化测试使用。
     */
    static java.util.stream.Stream<Variant> adlVariants() { return VARIANTS.stream(); }

    /** 四种合约及保证金组合分别覆盖源、目标同 Lane 和跨 Lane 的原账本交接。 */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> accountLaneVariants() {
        return VARIANTS.stream().flatMap(variant -> java.util.stream.Stream.of(1, 2)
                .map(laneCount -> org.junit.jupiter.params.provider.Arguments.of(variant, laneCount)));
    }

    /**
     * 构建全部矩阵行，检查四种组合各十八场景的七十二个固定键没有遗漏、额外项或重复。名称虽含 failingFirst，当前断言目标仍是清单全部满足。
     */
    @Test
    void failingFirstCompletenessManifestReportsEveryMissingRow() {
        // 本次生成的完整资金矩阵行，用于逐行金额与场景完整性断言
        List<Row> rows = allRows();
        // 按矩阵键统计出现次数的有序表，用于找遗漏、额外项和重复项
        Map<String, Integer> counts = new TreeMap<>();
        for (Row /* 当前需要检查场景身份或资金前后值的一行 */ row : rows) {
            counts.merge(row.key(), 1, Integer::sum);
        }
        // 生成行实际出现的矩阵键集合
        Set<String> actual = counts.keySet();
        // 从显式要求清单选择出的当前必需矩阵键
        Set<String> required = REQUIRED_ROWS;
        // 要求存在但实际没有生成的矩阵键集合
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(actual);
        // 实际生成但不在要求清单中的矩阵键集合
        Set<String> unexpected = new LinkedHashSet<>(actual);
        unexpected.removeAll(required);
        // 出现超过一次的矩阵键集合
        Set<String> duplicate = new LinkedHashSet<>();
        counts.forEach((/* 当前统计或筛选的矩阵场景键 */ key, /* 当前场景键实际出现次数，超过一表示重复 */ count) -> {
            if (count > 1) duplicate.add(key);
        });

        assertThat(missing).as("missing perpetual financial matrix rows").isEmpty();
        assertThat(unexpected).as("unexpected perpetual financial matrix rows").isEmpty();
        assertThat(duplicate).as("duplicate perpetual financial matrix rows").isEmpty();
        assertThat(actual).containsExactlyInAnyOrderElementsOf(required);
        assertThat(rows).hasSize(required.size());
    }

    /**
     * 执行全部矩阵场景的资金断言，并核对前十五类场景覆盖四种永续组合。
     */
    @Test
    void coversLinearInverseCrossIsolated() {
        // 本次生成的完整资金矩阵行，用于逐行金额与场景完整性断言
        List<Row> rows = allRows();

        assertRows(rows);
        assertManifest(rows, POSITIVE_SCENARIOS);
    }

    /**
     * 执行全部矩阵行并核对旧价、跨产品线及抵押隔离场景清单；具体拒绝和金额断言在各行构造方法执行。
     */
    @Test
    void rejectsStaleCrossLineAndCollateralLeakage() {
        // 本次生成的完整资金矩阵行，用于逐行金额与场景完整性断言
        List<Row> rows = allRows();

        assertRows(rows);
        assertManifest(rows, List.of("STALE_MARK"));
        assertManifest(rows, NEGATIVE_SCENARIOS);
    }

    /**
     * 建立已标记强平的减仓单，分别比较分段撤单游标推进及撤完执行的直接运行时结果与冷转换结果；使用显式撤单路由，没有真实 Matcher 撤单线程。
     */
    @Test
    void persistentLiquidationCancellationAdvanceMatchesReducer() {
        // 当前执行的永续类型与保证金模式组合
        Variant variant = VARIANTS.getFirst();
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE,
                101, POSITION_MARGIN);
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = mark(opening, variant, 90, 1);
        // 价格变化后生成的原强平计划，后续手工标成已有执行订单
        CoreLiquidationState planned = marked.riskState().liquidations().get(1L);
        // 本次按用例顺序挂入订单后的冷状态
        TradingCoreState placed = reducer.placeOrder(marked, USER_ID,
                order(701, variant, CoreOrderSide.SELL, 1, true, 0, 0));
        // 强平计划已关联订单七百零一的冷状态
        TradingCoreState ordered = replaceLiquidation(placed, planned.ordered(701));
        // 带强平单七百零一及每页十笔撤单边界的执行命令
        ExecuteLiquidationCommand command = new ExecuteLiquidationCommand(
                1, planned.triggerPriceSequence(), 90, 0, 701, 10);
        // 本例显式提供的已撤订单状态清单，用于生成真实业务所需的撤单路由
        List<CoreOrderState> canceled = List.of(ordered.order(701));
        // 本段撤单并推进下一游标后的冷路径预期状态
        TradingCoreState expected = reducer.advanceLiquidationCancellation(ordered, command, canceled, 702);
        // 本次运行时投影独用的整数身份表，物化与对照使用同一份
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 本次验证直接转换或异步控制使用的投影运行时
        TradingAccountRuntime runtime = RuntimeStateProjector.project(ordered, identities);

        assertThat(RuntimeTestStateTransitions.applyLiquidationCancellationAdvance(ordered,
                command,
                RuntimeTestStateTransitions.cancellationRoutes(canceled),
                702,
                runtime,
                identities)).isSameAs(runtime);
        RuntimeStateParityChecker.assertMatches(expected, identities, runtime);

        // 仅应用所列撤单后的冷状态，供撤完再执行的预期结果使用
        TradingCoreState canceledState = reducer.cancelLifecycleOrders(ordered, canceled);
        // 先撤单再执行强平的冷路径完整预期状态
        TradingCoreState executionExpected = reducer.executeLiquidationAfterCancellation(canceledState, command);
        // 撤单后执行分支单独投影使用的身份表
        RuntimeIdentityRegistry executionIdentities = new RuntimeIdentityRegistry();
        // 从同一 ordered 起点投影、供撤单加执行一步对照的运行时
        TradingAccountRuntime executionRuntime = RuntimeStateProjector.project(ordered, executionIdentities);
        assertThat(RuntimeTestStateTransitions.applyLiquidationExecution(ordered,
                command,
                RuntimeTestStateTransitions.cancellationRoutes(canceled),
                executionRuntime,
                executionIdentities)).isSameAs(executionRuntime);
        RuntimeStateParityChecker.assertMatches(executionExpected, executionIdentities, executionRuntime);
    }

    /**
     * 正反向全仓各挂价格一百与一百零一的卖单，再依次显式成交，核对预冻结、逐笔保证金、双方手续费及财库净收入。
     */
    @Test
    void mixedPricePendingOrdersReserveForTheirActualFillPrices() {
        for (Variant /* 当前执行的永续类型与保证金模式组合 */ variant : List.of(VARIANTS.getFirst(), VARIANTS.get(2))) {
            // 本场景建立的原冷状态，作为后续业务转换或资金行基线
            TradingCoreState opening = pairFunded(
                    variant, false, DEFAULT_WALLET, DEFAULT_WALLET, 100_000, 100_000);
            // 本次按用例顺序挂入订单后的冷状态
            TradingCoreState placed = reducer.placeOrder(opening, USER_ID,
                    pricedOrder(801, variant, CoreOrderSide.SELL, 1, 100, false, 100_000, 100_000));
            placed = reducer.placeOrder(placed, USER_ID,
                    pricedOrder(802, variant, CoreOrderSide.SELL, 1, 101, false, 100_000, 100_000));
            // 两笔不同价格成交后用户持仓保证金的独立列出期望
            long expectedMargin = variant.type().isInverse() ? 20 : 21;
            // 两笔不同价格卖单成交后主用户应付的普通手续费合计
            long expectedFee = variant.type().isInverse() ? 20 : 21;
            // 两笔卖单被接受时需要锁定的总金额期望
            long expectedLocked = variant.type().isInverse() ? 40 : 44;
            assertThat(placed.user(USER_ID).balances().get(variant.settleAsset()).lockedUnits())
                    .isEqualTo(expectedLocked);

            placed = reducer.placeOrder(placed, MAKER_ID,
                    pricedOrder(803, variant, CoreOrderSide.BUY, 1, 100));
            // 价格一百的第一笔显式成交完成后的冷状态
            TradingCoreState afterFirstFill = reducer.applyMatches(placed, 803, variant.baseAsset(),
                    variant.quoteAsset(), List.of(trade(801, USER_ID, 100, 1, true, true)));
            afterFirstFill = reducer.placeOrder(afterFirstFill, MAKER_ID,
                    pricedOrder(804, variant, CoreOrderSide.BUY, 1, 101));
            // 本场景业务步骤完成后的冷状态，交给局部断言与资金行使用
            TradingCoreState ending = reducer.applyMatches(afterFirstFill, 804, variant.baseAsset(),
                    variant.quoteAsset(), List.of(trade(802, USER_ID, 101, 1, true, true)));

            assertThat(ending.user(USER_ID).positions().get(SYMBOL).signedQuantitySteps()).isEqualTo(-2);
            assertThat(ending.user(USER_ID).positions().get(SYMBOL).positionMarginUnits())
                    .isEqualTo(expectedMargin);
            assertThat(ending.user(USER_ID).totalUnits(variant.settleAsset()))
                    .isEqualTo(DEFAULT_WALLET - expectedFee);
            assertThat(ending.user(MAKER_ID).totalUnits(variant.settleAsset()))
                    .isEqualTo(DEFAULT_WALLET - expectedFee);
            assertThat(ending.treasuryState().feeBalances())
                    .containsEntry(variant.settleAsset(), Math.multiplyExact(expectedFee, 2));
        }
    }

    /**
     * 逐一执行四种组合下的十八个场景并收集矩阵行；场景方法本身已经含局部断言，调用并非只生成静态数据。
     */
    private List<Row> allRows() {
        // 本次生成的完整资金矩阵行，用于逐行金额与场景完整性断言
        List<Row> rows = new ArrayList<>();
        for (Variant /* 当前执行的永续类型与保证金模式组合 */ variant : VARIANTS) {
            rows.add(openCloseReversal(variant));
            rows.add(tierChange(variant));
            rows.add(makerTakerFees(variant));
            rows.add(funding(variant, 100_000, "FUNDING_POSITIVE"));
            rows.add(funding(variant, -100_000, "FUNDING_NEGATIVE"));
            rows.add(staleMark(variant));
            rows.add(riskScan(variant));
            rows.add(liquidation(variant, 5, "LIQUIDATION_PARTIAL"));
            rows.add(liquidation(variant, QUANTITY, "LIQUIDATION_FULL"));
            rows.add(cappedLiquidation(variant));
            rows.add(insurance(variant, true));
            rows.add(insurance(variant, false));
            rows.add(adlOrder(variant));
            rows.add(adlCoverage(variant));
            rows.add(snapshotContinuation(variant));
            rows.add(crossLineRejected(variant));
            rows.add(isolatedCollateralLeakage(variant));
            rows.add(isolatedFreeCollateralLeakage(variant));
        }
        return rows;
    }

    /**
     * 先开对向十份再反向成交十五份，确认双方剩余反向五份及保证金五十，总资金不因开平与反手改变。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    private Row openCloseReversal(Variant variant) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = pairFunded(variant, false, DEFAULT_WALLET, DEFAULT_WALLET);
        // 双方默认数量十的首次成交后状态
        TradingCoreState opened = match(variant, opening, 1, 2, CoreOrderSide.BUY,
                CoreOrderSide.SELL, QUANTITY);
        // 反向再成交十五后的状态，双方方向反转且各剩五份
        TradingCoreState reversed = match(variant, opened, 3, 4, CoreOrderSide.SELL,
                CoreOrderSide.BUY, 15);

        assertThat(reversed.user(USER_ID).positions().get(SYMBOL).signedQuantitySteps()).isEqualTo(-5);
        assertThat(reversed.user(MAKER_ID).positions().get(SYMBOL).signedQuantitySteps()).isEqualTo(5);
        assertThat(reversed.user(USER_ID).positions().get(SYMBOL).positionMarginUnits()).isEqualTo(50);
        assertThat(reversed.user(MAKER_ID).positions().get(SYMBOL).positionMarginUnits()).isEqualTo(50);
        return row(variant, "OPEN_CLOSE_REVERSAL", opening, reversed, List.of(MAKER_ID), false, false,
                funds(1_000, 1_000, 0, 0, 0, 0, 0, 0, 1_000, 1_000, 0, 0));
    }

    /**
     * 先成交九份，再追加十份跨入第二风险档，确认双方持仓保证金都为三百八十，并生成对应资金行。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    private Row tierChange(Variant variant) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = pairFunded(variant, true, DEFAULT_WALLET, DEFAULT_WALLET);
        // 双方首次成交九份、仍处于低档风险范围的状态
        TradingCoreState first = match(variant, opening, 11, 12, CoreOrderSide.BUY,
                CoreOrderSide.SELL, 9);
        // 双方追加十份、进入第二风险档后的状态
        TradingCoreState second = match(variant, first, 13, 14, CoreOrderSide.BUY,
                CoreOrderSide.SELL, QUANTITY);

        var instrument = second.instruments().get(SYMBOL);
        assertThat(CoreContractMath.riskBracket(instrument, 1_900))
                .isSameAs(instrument.riskLimitBrackets().get(1));
        assertThat(second.user(USER_ID).positions().get(SYMBOL).positionMarginUnits()).isEqualTo(380);
        assertThat(second.user(MAKER_ID).positions().get(SYMBOL).positionMarginUnits()).isEqualTo(380);
        return row(variant, "TIER_CHANGE", opening, second, List.of(MAKER_ID), false, false,
                funds(1_000, 1_000, 0, 0, 0, 0, 0, 0, 1_000, 1_000, 0, 0));
    }

    /**
     * 配置标的挂单返佣百分之五、吃单收费百分之十，成交后核对财库净五十及双方余额；订单辅助的费用入参最终未写入意图，实际采用标的配置。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    private Row makerTakerFees(Variant variant) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = pairFunded(
                variant, false, DEFAULT_WALLET, DEFAULT_WALLET, -50_000, 100_000);
        // 本辅助按配置、注资、挂单或手工持仓顺序推进的冷状态
        TradingCoreState state = reducer.placeOrder(opening, MAKER_ID,
                order(21, variant, CoreOrderSide.SELL, QUANTITY, false, -50_000, 200_000));
        state = reducer.placeOrder(state, USER_ID,
                order(22, variant, CoreOrderSide.BUY, QUANTITY, false, 0, 100_000));
        // 本场景业务步骤完成后的冷状态，交给局部断言与资金行使用
        TradingCoreState ending = reducer.applyMatches(state, 22, variant.baseAsset(), variant.quoteAsset(),
                List.of(trade(21, MAKER_ID, ENTRY_PRICE, QUANTITY, true, true)));

        assertThat(ending.treasuryState().feeBalances()).containsEntry(variant.settleAsset(), 50L);
        return row(variant, "MAKER_TAKER_FEES", opening, ending, List.of(MAKER_ID), false, false,
                funds(1_000, 1_000, 0, 0, -100, 50, 50, 0, 900, 1_050, 50, 0));
    }

    /**
     * 对向持仓在当前价格下结算正或负百分之十资金费，核对双方支付事实为相反的一百，并记录对应余额变化。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param fundingRatePpm 本次资金费率，按百万分之一保存，正负决定双方支付方向
     * @param scenario 本行需要登记的场景名，用于固定清单与诊断
     */
    private Row funding(Variant variant, long fundingRatePpm, String scenario) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = oppositePositions(variant, DEFAULT_WALLET, DEFAULT_WALLET);
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = mark(opening, variant, ENTRY_PRICE, 1);
        // 本次资金费处理返回的状态、支付事实及进度
        RuntimeTestStateTransitions.FundingApplication application = reducer.applyFundingWithFacts(marked,
                new ApplyFundingCommand(300 + (fundingRatePpm > 0 ? 1 : 2), SYMBOL, fundingRatePpm));
        // 本场景业务步骤完成后的冷状态，交给局部断言与资金行使用
        TradingCoreState ending = application.state();

        // 按正负资金费或强平模式独立列出的期末用户金额
        long userEnding = fundingRatePpm > 0 ? 900 : 1_100;
        // 按正负资金费方向列出的第一做市用户期末金额
        long makerEnding = fundingRatePpm > 0 ? 1_100 : 900;
        // 主用户本次资金费流量，正费率付出一百，负费率收入一百
        long userFlow = fundingRatePpm > 0 ? -100 : 100;
        // 做市用户与主用户正好相反的资金费流量
        long makerFlow = Math.negateExact(userFlow);
        assertThat(application.payments()).extracting(/* 本次依次检查金额的资金费支付事实 */ payment -> payment.amountUnits())
                .containsExactly(userFlow, makerFlow);
        return row(variant, scenario, opening, ending, List.of(MAKER_ID), false, false,
                funds(1_000, 1_000, 0, 0, userFlow, makerFlow, 0, 0,
                        userEnding, makerEnding, 0, 0));
    }

    /**
     * 在已有价格序列一后再次提交序列一，确认按旧价拒绝且原业务哈希不变，生成资金未变行。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    private Row staleMark(Variant variant) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, DEFAULT_WALLET,
                POSITION_MARGIN);
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = mark(opening, variant, ENTRY_PRICE, 1);
        // 拒绝或计算溢出前的业务哈希，失败后必须不变
        long hash = marked.businessStateHash();
        assertThatThrownBy(() -> reducer.applyMarkPrice(marked,
                new ApplyMarkPriceCommand(SYMBOL, ENTRY_PRICE, 1, 1_700_000_000_000L)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用固定代码核对原因 */ exception -> assertThat(exception.code()).isEqualTo("STALE_MARK_PRICE"));
        assertThat(marked.businessStateHash()).isEqualTo(hash);
        return row(variant, "STALE_MARK", opening, marked, List.of(), false, false,
                funds(1_000, 0, 0, 0, 0, 0, 0, 0, 1_000, 0, 0, 0));
    }

    /**
     * 把低抵押多头从一百标记到九十，核对正反向的浮亏、维持保证金、权益和已计划强平，但此行尚不执行减仓或资金划拨。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    private Row riskScan(Variant variant) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, 100, POSITION_MARGIN);
        // 本场景业务步骤完成后的冷状态，交给局部断言与资金行使用
        TradingCoreState ending = mark(opening, variant, 90, 1);
        // 用户本标的风险快照，核对浮亏、维持要求及权益
        CoreRiskSnapshot snapshot = ending.riskState().snapshots().get(USER_ID + ":" + SYMBOL);
        // 本次标记价生成的强平计划，状态应为 PLANNED
        CoreLiquidationState liquidation = ending.riskState().liquidations().get(1L);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(CoreRiskStatus.LIQUIDATION);
        assertThat(snapshot.unrealizedPnlUnits()).isEqualTo(linearOrInverse(variant, -100, -111));
        assertThat(snapshot.maintenanceMarginUnits()).isEqualTo(linearOrInverse(variant, 90, 112));
        assertThat(snapshot.equityUnits()).isEqualTo(linearOrInverse(variant, 0, -11));
        assertThat(liquidation.status()).isEqualTo(CoreLiquidationState.Status.PLANNED);
        return row(variant, "RISK_SCAN", opening, ending, List.of(), false, false,
                funds(100, 0, 0, 0, 0, 0, 0, 0, 100, 0, 0, 0));
    }

    /**
     * 全平场景直接使用低抵押持仓，部分强平先结资金费再手工把计划量改成五；分别对照直接运行时及冷结果，核对剩仓、保证金、已实现盈亏、费用与保险亏空。部分场景还调用空头、非整除及溢出边界断言。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param closeQuantity 本场景希望执行的强平数量；部分强平会据此手工替换计划量
     * @param scenario 本行需要登记的场景名，用于固定清单与诊断
     */
    private Row liquidation(Variant variant, long closeQuantity, String scenario) {
        // 本行指定的强平数量是否小于默认全部持仓
        boolean partial = closeQuantity != QUANTITY;
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening;
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked;
        // 部分强平前资金费处理返回的状态和支付事实；全平分支保持为空
        RuntimeTestStateTransitions.FundingApplication funding = null;
        // 本场景强平执行价，部分为七十、全平为九十
        long executionPrice;
        // 本场景强平费率，部分为百分之十、全平为零
        long feeRate;
        if (partial) {
            opening = oppositePositions(variant, 450, 300);
            // 仍按入场价估值的冷状态，先在该价格结资金费
            TradingCoreState markedAtEntry = mark(opening, variant, ENTRY_PRICE, 1);
            funding = reducer.applyFundingWithFacts(markedAtEntry,
                    new ApplyFundingCommand(500, SYMBOL, 100_000));
            marked = mark(funding.state(), variant, 70, 2);
            executionPrice = 70;
            feeRate = 100_000;
        } else {
            opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, 100, POSITION_MARGIN);
            marked = mark(opening, variant, 90, 1);
            executionPrice = 90;
            feeRate = 0;
        }
        // 本场景强平计划，部分或舍入测试手工替换需要减仓的计划量
        CoreLiquidationState plan = marked.riskState().liquidations().get(1L);
        if (partial) {
            plan = new CoreLiquidationState(plan.liquidationId(), plan.userId(), plan.instrumentId(), plan.marginMode(),
                    plan.positionSide(), plan.triggerPriceSequence(),
                    plan.signedQuantitySteps(), closeQuantity, 0, 0, 0, 0, CoreLiquidationState.Status.PLANNED);
            marked = replaceLiquidation(marked, plan);
        }
        // 与选定强平计划触发序列、执行价及费率一致的执行命令
        ExecuteLiquidationCommand liquidationCommand = new ExecuteLiquidationCommand(
                1, plan.triggerPriceSequence(), executionPrice, feeRate);
        // 直接强平运行时投影和物化对比用的整数身份表
        RuntimeIdentityRegistry liquidationIdentities = new RuntimeIdentityRegistry();
        // 执行同一强平命令的直接投影运行时，与冷状态结果逐项比较
        TradingAccountRuntime runtimeLiquidated = RuntimeStateProjector.project(marked, liquidationIdentities);
        assertThat(RuntimeTestStateTransitions.applyLiquidationExecution(marked,
                liquidationCommand,
                List.of(),
                runtimeLiquidated,
                liquidationIdentities))
                .isSameAs(runtimeLiquidated);
        // 本场景业务步骤完成后的冷状态，交给局部断言与资金行使用
        TradingCoreState ending = reducer.executeLiquidation(marked, liquidationCommand);
        RuntimeStateParityChecker.assertMatches(ending, liquidationIdentities, runtimeLiquidated);

        // 本场景已执行或确认后的强平状态，用于检查亏空、费用和后续阶段
        CoreLiquidationState result = ending.riskState().liquidations().get(1L);
        // 本场景主用户强平后的持仓，或溢出构造前需要保留其他字段的原持仓
        CorePositionState position = ending.user(USER_ID).positions().get(SYMBOL);
        if (partial) {
            // 本次强平按正反向公式独立列出的已实现盈亏期望
            long realizedPnl = linearOrInverse(variant, -150, -214);
            // 本组合是否逐仓，决定可用于强平费用及亏损的抵押范围
            boolean isolated = variant.marginMode() == CoreMarginMode.ISOLATED;
            // 按逐仓边界与可收资金上限列出的实际强平费期望
            long liquidationFee = isolated ? 0 : linearOrInverse(variant, 35, 72);
            // 强平后应记入保险余额的金额期望，包含本场景实际收取的相应资金
            long insurance = isolated ? 50 : linearOrInverse(variant, 185, 286);
            // 按正负资金费或强平模式独立列出的期末用户金额
            long userEnding = isolated ? 300 : linearOrInverse(variant, 165, 64);
            assertThat(funding.payments()).extracting(/* 本次依次检查金额的资金费支付事实 */ payment -> payment.amountUnits())
                    .containsExactly(-100L, 100L);
            assertThat(ending.treasuryState().fundingSettlements()).containsEntry(SYMBOL, 500L);
            assertThat(position.signedQuantitySteps()).isEqualTo(5);
            assertThat(position.positionMarginUnits()).isEqualTo(50);
            assertThat(position.entryPriceTicks()).isEqualTo(100);
            assertThat(position.entryValueTicks()).isEqualTo(500);
            assertThat(position.realizedPnlUnits()).isEqualTo(realizedPnl);
            assertThat(result.deficitUnits()).isEqualTo(isolated ? linearOrInverse(variant, 100, 164) : 0);
            assertThat(result.status()).isEqualTo(isolated
                    ? CoreLiquidationState.Status.INSURANCE_REQUIRED : CoreLiquidationState.Status.COMPLETED);
            assertThat(result.liquidationFeeUnits()).isEqualTo(liquidationFee);
            assertThat(ending.user(USER_ID).totalUnits(variant.settleAsset())).isEqualTo(userEnding);
            assertThat(ending.treasuryState().feeBalances()).doesNotContainKey(variant.settleAsset());
            assertThat(ending.treasuryState().insuranceBalances())
                    .containsEntry(variant.settleAsset(), insurance);
            assertShortPartialLiquidation(variant);
            assertNonDivisiblePartial(variant);
            assertLiquidationBoundaries(variant);
            return row(variant, scenario, opening, ending, List.of(MAKER_ID), false, false,
                    funds(450, 300, 0, 0, userEnding - 450, 100, 0, insurance,
                            userEnding, 400, 0, insurance));
        }

        // 本次强平按正反向公式独立列出的已实现盈亏期望
        long realizedPnl = linearOrInverse(variant, -100, -111);
        assertThat(position.signedQuantitySteps()).isZero();
        assertThat(position.positionMarginUnits()).isZero();
        assertThat(position.entryPriceTicks()).isZero();
        assertThat(position.entryValueTicks()).isZero();
        assertThat(position.realizedPnlUnits()).isEqualTo(realizedPnl);
        // 本场景全平后待补的剩余亏空期望
        long deficit = linearOrInverse(variant, 0, 11);
        assertThat(result.deficitUnits()).isEqualTo(deficit);
        assertThat(result.status()).isEqualTo(deficit == 0
                ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.INSURANCE_REQUIRED);
        assertThat(result.liquidationFeeUnits()).isZero();
        assertThat(ending.treasuryState().insuranceBalances())
                .containsEntry(variant.settleAsset(), 100L);
        return row(variant, scenario, opening, ending, List.of(), false, false,
                funds(100, 0, 0, 0, -100, 0, 0, 100, 0, 0, 0, 100));
    }

    /**
     * 空头先结负资金费再在价格一百五十强平五份，核对剩余空头、保证金、盈亏、费用、保险及双方总额，区分全仓和逐仓可用抵押。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    private void assertShortPartialLiquidation(Variant variant) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = withPosition(variant, USER_ID, -QUANTITY, ENTRY_PRICE, 400, POSITION_MARGIN);
        opening = withPosition(opening, variant, MAKER_ID, QUANTITY, ENTRY_PRICE, 300, POSITION_MARGIN);
        // 仍按入场价估值的冷状态，先在该价格结资金费
        TradingCoreState markedAtEntry = mark(opening, variant, ENTRY_PRICE, 1);
        // 空头部分强平前的负资金费处理结果，后续核对双方各一百的反向支付
        RuntimeTestStateTransitions.FundingApplication funding = reducer.applyFundingWithFacts(markedAtEntry,
                new ApplyFundingCommand(501, SYMBOL, -100_000));
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = mark(funding.state(), variant, 150, 2);
        // 本场景强平计划，部分或舍入测试手工替换需要减仓的计划量
        CoreLiquidationState plan = marked.riskState().liquidations().get(1L);
        plan = new CoreLiquidationState(plan.liquidationId(), plan.userId(), plan.instrumentId(), plan.marginMode(),
                plan.positionSide(), plan.triggerPriceSequence(),
                plan.signedQuantitySteps(), 5, 0, 0, 0, 0, CoreLiquidationState.Status.PLANNED);
        marked = replaceLiquidation(marked, plan);
        // 本场景业务步骤完成后的冷状态，交给局部断言与资金行使用
        TradingCoreState ending = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, plan.triggerPriceSequence(), 150, 100_000));

        // 本次强平按正反向公式独立列出的已实现盈亏期望
        long realizedPnl = linearOrInverse(variant, -250, -167);
        // 本组合是否逐仓，决定可用于强平费用及亏损的抵押范围
        boolean isolated = variant.marginMode() == CoreMarginMode.ISOLATED;
        // 按逐仓边界与可收资金上限列出的实际强平费期望
        long liquidationFee = isolated ? 0 : linearOrInverse(variant, 0, 34);
        // 强平后应记入保险余额的金额期望，包含本场景实际收取的相应资金
        long insurance = isolated ? 50 : linearOrInverse(variant, 250, 201);
        // 按正负资金费或强平模式独立列出的期末用户金额
        long userEnding = isolated ? 250 : linearOrInverse(variant, 50, 99);
        // 本场景主用户强平后的持仓，或溢出构造前需要保留其他字段的原持仓
        CorePositionState position = ending.user(USER_ID).positions().get(SYMBOL);
        // 本场景已执行或确认后的强平状态，用于检查亏空、费用和后续阶段
        CoreLiquidationState result = ending.riskState().liquidations().get(1L);
        assertThat(funding.payments()).extracting(/* 本次依次检查金额的资金费支付事实 */ payment -> payment.amountUnits())
                .containsExactly(-100L, 100L);
        assertThat(ending.treasuryState().fundingSettlements()).containsEntry(SYMBOL, 501L);
        assertThat(position.signedQuantitySteps()).isEqualTo(-5);
        assertThat(position.positionMarginUnits()).isEqualTo(50);
        assertThat(position.entryPriceTicks()).isEqualTo(100);
        assertThat(position.entryValueTicks()).isEqualTo(500);
        assertThat(position.realizedPnlUnits()).isEqualTo(realizedPnl);
        assertThat(result.deficitUnits()).isEqualTo(isolated ? linearOrInverse(variant, 200, 117) : 0);
        assertThat(result.status()).isEqualTo(isolated
                ? CoreLiquidationState.Status.INSURANCE_REQUIRED : CoreLiquidationState.Status.COMPLETED);
        assertThat(result.liquidationFeeUnits()).isEqualTo(liquidationFee);
        assertThat(ending.user(USER_ID).totalUnits(variant.settleAsset())).isEqualTo(userEnding);
        assertThat(ending.user(MAKER_ID).totalUnits(variant.settleAsset())).isEqualTo(400);
        assertThat(ending.treasuryState().feeBalances()).doesNotContainKey(variant.settleAsset());
        assertThat(ending.treasuryState().insuranceBalances())
                .containsEntry(variant.settleAsset(), insurance);
        assertThat(userEnding + 400 + insurance - 700).isZero();
    }

    /**
     * 对三份多头和三份空头各强平一份，交给通用断言检查保证金非整除时的剩余量、舍入及亏空。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    private void assertNonDivisiblePartial(Variant variant) {
        assertRoundedPartial(variant, 3, 60, 2, linearOrInverse(variant, -40, -67),
                linearOrInverse(variant, 7, 34), linearOrInverse(variant, 68, 68),
                linearOrInverse(variant, 33, 33));
        assertRoundedPartial(variant, -3, 140, -2, linearOrInverse(variant, -40, -29),
                linearOrInverse(variant, 7, 0), linearOrInverse(variant, 68, 72),
                linearOrInverse(variant, 33, 29));
    }

    /**
     * 从保证金一百零一的三份持仓强平一份，剩余保证金应为六十八；其余数量、损益、亏空、用户和保险值采用调用方独立列出的期望。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param signedQuantity 手工建立的带方向数量，正为多头、负为空头
     * @param markPrice 用于触发和执行本场景强平的价格 tick
     * @param expectedQuantity 部分强平后预期带方向剩余数量
     * @param expectedPnl 本次部分强平预期累计已实现盈亏
     * @param expectedDeficit 强平后预期尚需保险或 ADL 补足的亏空
     * @param expectedUser 强平后预期用户结算资产总额
     * @param expectedInsurance 强平后预期保险余额
     */
    private void assertRoundedPartial(Variant variant, long signedQuantity, long markPrice,
                                      long expectedQuantity, long expectedPnl, long expectedDeficit,
                                      long expectedUser, long expectedInsurance) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = withPosition(variant, USER_ID, signedQuantity, ENTRY_PRICE, 101, 101);
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = mark(opening, variant, markPrice, 1);
        // 本场景强平计划，部分或舍入测试手工替换需要减仓的计划量
        CoreLiquidationState plan = marked.riskState().liquidations().get(1L);
        plan = new CoreLiquidationState(plan.liquidationId(), plan.userId(), plan.instrumentId(), plan.marginMode(),
                plan.positionSide(), plan.triggerPriceSequence(),
                plan.signedQuantitySteps(), 1, 0, 0, 0, 0, CoreLiquidationState.Status.PLANNED);
        marked = replaceLiquidation(marked, plan);
        // 本场景业务步骤完成后的冷状态，交给局部断言与资金行使用
        TradingCoreState ending = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, plan.triggerPriceSequence(), markPrice, 0));

        // 本场景主用户强平后的持仓，或溢出构造前需要保留其他字段的原持仓
        CorePositionState position = ending.user(USER_ID).positions().get(SYMBOL);
        // 本场景已执行或确认后的强平状态，用于检查亏空、费用和后续阶段
        CoreLiquidationState result = ending.riskState().liquidations().get(1L);
        assertThat(position.signedQuantitySteps()).isEqualTo(expectedQuantity);
        assertThat(position.positionMarginUnits()).isEqualTo(68);
        assertThat(position.entryPriceTicks()).isEqualTo(100);
        assertThat(position.entryValueTicks()).isEqualTo(200);
        assertThat(position.realizedPnlUnits()).isEqualTo(expectedPnl);
        assertThat(result.deficitUnits()).isEqualTo(expectedDeficit);
        assertThat(result.status()).isEqualTo(expectedDeficit == 0
                ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.INSURANCE_REQUIRED);
        assertThat(ending.user(USER_ID).totalUnits(variant.settleAsset())).isEqualTo(expectedUser);
        assertThat(ending.treasuryState().insuranceBalances())
                .containsEntry(variant.settleAsset(), expectedInsurance);
        assertThat(expectedUser + expectedInsurance - 101).isZero();
    }

    /**
     * 拒绝数量取负溢出和超出总仓的计划；再手工放入极大持仓入场价值，确认部分强平计算溢出时原业务哈希保持不变。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    private void assertLiquidationBoundaries(Variant variant) {
        assertThatThrownBy(() -> new CoreLiquidationState(1, USER_ID, SYMBOL, variant.marginMode(),
                CorePositionSide.NET, 1, Long.MIN_VALUE, 1, 0, 0, 0, 0,
                CoreLiquidationState.Status.PLANNED)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new CoreLiquidationState(1, USER_ID, SYMBOL, variant.marginMode(),
                CorePositionSide.NET, 1, QUANTITY, QUANTITY + 1, 0, 0, 0, 0,
                CoreLiquidationState.Status.PLANNED)).isInstanceOf(IllegalArgumentException.class);

        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = withPosition(variant, USER_ID, 3, ENTRY_PRICE, 100, 100);
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = mark(opening, variant, 50, 1);
        // 本场景主用户强平后的持仓，或溢出构造前需要保留其他字段的原持仓
        CorePositionState position = marked.user(USER_ID).positions().get(SYMBOL);
        // 手工把原持仓入场价值换成 long 最大值后的冷状态，故意触发后续乘算溢出
        TradingCoreState overflow = replacePosition(marked, USER_ID,
                new CorePositionState(SYMBOL, variant.settleAsset(), variant.marginMode(), CorePositionSide.NET,
                        3, ENTRY_PRICE, Long.MAX_VALUE, position.realizedPnlUnits(),
                        position.positionMarginUnits()));
        // 本场景强平计划，部分或舍入测试手工替换需要减仓的计划量
        CoreLiquidationState plan = overflow.riskState().liquidations().get(1L);
        plan = new CoreLiquidationState(plan.liquidationId(), plan.userId(), plan.instrumentId(), plan.marginMode(),
                plan.positionSide(), plan.triggerPriceSequence(),
                plan.signedQuantitySteps(), 1, 0, 0, 0, 0, CoreLiquidationState.Status.PLANNED);
        overflow = replaceLiquidation(overflow, plan);
        // 供异常断言闭包固定捕获的溢出场景状态
        TradingCoreState overflowState = overflow;
        // 手工计划保留的原触发价格序列，执行时必须匹配
        long triggerPriceSequence = plan.triggerPriceSequence();
        // 拒绝或计算溢出前的业务哈希，失败后必须不变
        long hash = overflowState.businessStateHash();
        assertThatThrownBy(() -> reducer.executeLiquidation(overflowState,
                new ExecuteLiquidationCommand(1, triggerPriceSequence, 50, 0)))
                .isInstanceOf(ArithmeticException.class);
        assertThat(overflowState.businessStateHash()).isEqualTo(hash);
    }

    /**
     * 余额一百八十、保证金一百的多头在九十强平且收费百分之十，核对实际可收费用上限、逐仓未动自由余额及对应保险/亏空，再对照运行时结果。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    private Row cappedLiquidation(Variant variant) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, 180, POSITION_MARGIN);
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = mark(opening, variant, 90, 1);
        // 对计划一在九十执行、强平费率百分之十的命令
        ExecuteLiquidationCommand command = new ExecuteLiquidationCommand(
                1, liquidationSequence(marked), 90, 100_000);
        // 本次运行时投影独用的整数身份表，物化与对照使用同一份
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 直接执行业务的投影运行时，与同输入冷路径完整结果比较
        TradingAccountRuntime runtimeEnding = RuntimeStateProjector.project(marked, identities);
        assertThat(RuntimeTestStateTransitions.applyLiquidationExecution(marked,
                command,
                List.of(),
                runtimeEnding,
                identities)).isSameAs(runtimeEnding);
        // 本场景业务步骤完成后的冷状态，交给局部断言与资金行使用
        TradingCoreState ending = reducer.executeLiquidation(marked, command);
        RuntimeStateParityChecker.assertMatches(ending, identities, runtimeEnding);
        // 本场景已执行或确认后的强平状态，用于检查亏空、费用和后续阶段
        CoreLiquidationState result = ending.riskState().liquidations().get(1L);
        // 本组合是否逐仓，决定可用于强平费用及亏损的抵押范围
        boolean isolated = variant.marginMode() == CoreMarginMode.ISOLATED;
        // 按抵押可收金额封顶的强平费期望，逐仓此场景不再收取
        long expectedFee = isolated ? 0 : linearOrInverse(variant, 80, 69);
        // 按全仓或逐仓边界列出的强平后用户总额
        long expectedUser = isolated ? 80 : 0;
        // 按全仓或逐仓边界列出的强平后保险余额
        long expectedInsurance = isolated ? 100 : 180;
        // 按正反向及逐仓边界列出的剩余亏空期望
        long expectedDeficit = isolated ? linearOrInverse(variant, 0, 11) : 0;
        assertThat(result.liquidationFeeUnits()).isEqualTo(expectedFee);
        assertThat(result.deficitUnits()).isEqualTo(expectedDeficit);
        assertThat(ending.user(USER_ID).totalUnits(variant.settleAsset())).isEqualTo(expectedUser);
        assertThat(ending.treasuryState().insuranceBalances())
                .containsEntry(variant.settleAsset(), expectedInsurance);
        return row(variant, "LIQUIDATION_FEE_CAP", opening, ending, List.of(), false, false,
                funds(180, 0, 0, 0, expectedUser - 180, 0, 0, expectedInsurance,
                        expectedUser, 0, 0, expectedInsurance));
    }

    /**
     * 低价强平制造亏空，外部给保险注资后用分配政策确定实际覆盖；快照往返后比较保险确认结果，足额应完成，不足应进入 ADL_REQUIRED。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param full 是否给保险注入本场景全部亏空；否则只注入二十五
     */
    private Row insurance(Variant variant, boolean full) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, 100, POSITION_MARGIN);
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = mark(opening, variant, 1, 1);
        // 强平已执行且持仓资金已更新的冷状态，后续继续保险或 ADL
        TradingCoreState liquidated = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, liquidationSequence(marked), 1, 0));
        // 本场景全平后待补的剩余亏空期望
        long deficit = linearOrInverse(variant, 890, 98_900);
        // 给保险外部注入的数量，随后按分配政策确定实际覆盖
        long coverage = full ? deficit : 25;
        // 强平状态上已向保险基金注资后的冷状态
        TradingCoreState funded = reducer.adjustInsuranceFund(liquidated,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand(variant.settleAsset(), coverage));
        coverage = RuntimeTestStateTransitions.expectedInsuranceCoverage(funded, 1);
        // 按政策算定的实际覆盖量确认强平保险结果的命令
        ResolveLiquidationCommand command = new ResolveLiquidationCommand(
                1, ResolveLiquidationCommand.Resolution.INSURANCE, coverage);
        // 本次运行时投影独用的整数身份表，物化与对照使用同一份
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 保险已注资状态快照往返后的冷状态，投影到直接确认路径前先核对一致
        TradingCoreState restoredFunded = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(funded), variant.productLine());
        assertThat(restoredFunded).isEqualTo(funded);
        // 直接执行业务的投影运行时，与同输入冷路径完整结果比较
        TradingAccountRuntime runtimeEnding = RuntimeStateProjector.project(restoredFunded, identities);
        assertThat(RuntimeTestStateTransitions.applyLiquidationResolution(restoredFunded,
                command,
                runtimeEnding,
                identities)).isSameAs(runtimeEnding);
        // 本场景业务步骤完成后的冷状态，交给局部断言与资金行使用
        TradingCoreState ending = reducer.resolveLiquidation(funded, command);
        RuntimeStateParityChecker.assertMatches(ending, identities, runtimeEnding);

        // 本场景已执行或确认后的强平状态，用于检查亏空、费用和后续阶段
        CoreLiquidationState result = ending.riskState().liquidations().get(1L);
        assertThat(result.deficitUnits()).isEqualTo(deficit - coverage);
        assertThat(result.status()).isEqualTo(full
                ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.ADL_REQUIRED);
        // 保险确认前余额减去实际覆盖后的期末保险余额期望
        long expectedInsuranceBalance = funded.treasuryState().insuranceBalances()
                .getOrDefault(variant.settleAsset(), 0L) - coverage;
        assertThat(ending.treasuryState().insuranceBalances().getOrDefault(variant.settleAsset(), 0L))
                .isEqualTo(expectedInsuranceBalance);
        return row(variant, full ? "INSURANCE_FULL" : "INSURANCE_PARTIAL", opening, ending, List.of(), false, false,
                funds(100, 0, 0, 0, -100, 0, 0, expectedInsuranceBalance,
                        0, 0, 0, expectedInsuranceBalance));
    }

    /**
     * 强平并部分保险覆盖后查询盈利候选，检查两个做市用户的排名顺序；该资金行以同一待补亏状态作为期初期末，按含未实现盈亏和亏空口径统计，不执行 ADL。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    private Row adlOrder(Variant variant) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = adlSetup(variant);
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = mark(opening, variant, 1, 1);
        // 强平已执行且持仓资金已更新的冷状态，后续继续保险或 ADL
        TradingCoreState liquidated = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, liquidationSequence(marked), 1, 0));
        // 强平状态上已向保险基金注资后的冷状态
        TradingCoreState funded = reducer.adjustInsuranceFund(liquidated,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand(variant.settleAsset(), 25));
        // 本轮先注入保险、再由分配政策算定的实际覆盖量
        long insuranceCoverage = RuntimeTestStateTransitions.expectedInsuranceCoverage(funded, 1);
        // 本场景业务步骤完成后的冷状态，交给局部断言与资金行使用
        TradingCoreState ending = reducer.resolveLiquidation(funded,
                new ResolveLiquidationCommand(1, ResolveLiquidationCommand.Resolution.INSURANCE,
                        insuranceCoverage));
        // 按当前盈利及排序规则查询出的 ADL 候选，预期第一做市用户先于第二用户
        List<com.surprising.aeron.protocol.CoreAdlCandidateView> candidates =
                reducer.adlCandidates(ending, variant.settleAsset(), 10);

        assertThat(candidates).extracting(/* 当前抽取用户 ID 的 ADL 候选 */ candidate -> candidate.userId()).containsExactly(MAKER_ID, SECOND_MAKER_ID);
        // 本次需要由 ADL 覆盖或纳入保险统计扣项的剩余强平亏空
        long residual = ending.riskState().liquidations().get(1L).deficitUnits();
        // 包含两位候选未实现盈利的期初做市侧经济价值期望
        long makerOpening = linearOrInverse(variant, 5_480, 200_833);
        return row(variant, "ADL_ORDER", ending, ending, List.of(MAKER_ID, SECOND_MAKER_ID), true, true,
                funds(0, makerOpening, 0, -residual, 0, 0, 0, 0,
                        0, makerOpening, 0, -residual));
    }

    /**
     * 先制造亏空并按政策分保险，再对第一做市用户减五份覆盖剩余亏空；快照恢复后的直接运行时和冷路径一致，检查强平完成及目标剩仓，资金行采用经济价值和待补亏口径。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    private Row adlCoverage(Variant variant) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = adlSetup(variant);
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = mark(opening, variant, 1, 1);
        // 强平已执行且持仓资金已更新的冷状态，后续继续保险或 ADL
        TradingCoreState liquidated = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, liquidationSequence(marked), 1, 0));
        // 本场景显式向保险注入的数量，正向二十五、反向五万
        long requestedInsuranceCoverage = variant.type() == ContractType.LINEAR_PERPETUAL ? 25 : 50_000;
        // 强平状态上已向保险基金注资后的冷状态
        TradingCoreState funded = reducer.adjustInsuranceFund(liquidated,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand(
                        variant.settleAsset(), requestedInsuranceCoverage));
        // 本轮先注入保险、再由分配政策算定的实际覆盖量
        long insuranceCoverage = RuntimeTestStateTransitions.expectedInsuranceCoverage(funded, 1);
        // 分配政策实际覆盖比显式外部注资多出的部分，来源为场景已有保险余额
        long extraInsuranceCoverage = insuranceCoverage - requestedInsuranceCoverage;
        // 保险确认后仍需 ADL 补亏的冷状态，作为自动减仓起点
        TradingCoreState beforeAdl = reducer.resolveLiquidation(funded,
                new ResolveLiquidationCommand(1, ResolveLiquidationCommand.Resolution.INSURANCE,
                        insuranceCoverage));
        // 本次需要由 ADL 覆盖或纳入保险统计扣项的剩余强平亏空
        long residual = beforeAdl.riskState().liquidations().get(1L).deficitUnits();
        // 对第一做市用户空头减五份并覆盖全部剩余亏空的 ADL 命令
        ExecuteAdlCommand command = new ExecuteAdlCommand(1, MAKER_ID, SYMBOL, variant.marginMode(),
                CorePositionSide.NET, -QUANTITY, 200,
                beforeAdl.riskState().markPrices().get(SYMBOL).priceSequence(), 5, residual);
        // 本次运行时投影独用的整数身份表，物化与对照使用同一份
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 待补亏状态快照往返后的冷状态，供直接 ADL 路径使用
        TradingCoreState restoredBeforeAdl = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(beforeAdl), variant.productLine());
        assertThat(restoredBeforeAdl).isEqualTo(beforeAdl);
        // 直接执行业务的投影运行时，与同输入冷路径完整结果比较
        TradingAccountRuntime runtimeEnding = RuntimeStateProjector.project(restoredBeforeAdl, identities);
        assertThat(RuntimeTestStateTransitions.applyAdlExecution(restoredBeforeAdl, command, runtimeEnding, identities)).isSameAs(runtimeEnding);
        // 本场景业务步骤完成后的冷状态，交给局部断言与资金行使用
        TradingCoreState ending = reducer.executeAdl(beforeAdl, command);
        RuntimeStateParityChecker.assertMatches(ending, identities, runtimeEnding);

        // 记录的目标现金流期望，当前局部变量之后没有参与断言
        long targetCashFlow = linearOrInverse(variant, 130, 850);
        // 目标做市用户期末现金加未实现盈亏的经济价值期望，另计额外保险覆盖
        long targetEndingEconomic = linearOrInverse(variant, 2_125, 51_600) + extraInsuranceCoverage;
        assertThat(ending.user(MAKER_ID).positions().get(SYMBOL).signedQuantitySteps()).isEqualTo(-5);
        assertThat(ending.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.COMPLETED);
        assertThat(ending.riskState().liquidations().get(1L).deficitUnits()).isZero();
        assertThat(ending.user(MAKER_ID).totalUnits(variant.settleAsset()))
                .isEqualTo(linearOrInverse(variant, 1_130, 1_850) + extraInsuranceCoverage);
        return row(variant, "ADL_COVERAGE", beforeAdl, ending, List.of(MAKER_ID), true, true,
                funds(0, linearOrInverse(variant, 2_990, 100_500), 0, -residual,
                        0, Math.negateExact(residual), 0, residual,
                        0, targetEndingEconomic, 0, 0));
    }

    /**
     * 资金费按每页一个用户处理，首段快照往返后从用户游标继续，确认首段未完、第二段完成且结算 ID 已登记，最终双方余额九百与一千一百。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    private Row snapshotContinuation(Variant variant) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = oppositePositions(variant, DEFAULT_WALLET, DEFAULT_WALLET);
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = mark(opening, variant, ENTRY_PRICE, 1);
        // 资金费首段命令的固定 UUID，后续段使用不同 UUID
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        // 资金费首段处理一个用户后的状态、支付事实和未完成进度
        RuntimeTestStateTransitions.FundingApplication first = reducer.applyFundingWithFacts(marked,
                new ApplyFundingCommand(301, SYMBOL, 100_000, 0, 1), List.of(USER_ID, MAKER_ID));
        // 资金费首段结果快照往返后的冷状态，保留用户续步游标
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(first.state()), variant.productLine());
        assertThat(restored).isEqualTo(first.state());
        // 恢复后继续处理下一用户返回的完整资金费结果
        RuntimeTestStateTransitions.FundingApplication second = reducer.applyFundingWithFacts(restored,
                new ApplyFundingCommand(301, SYMBOL, 100_000, USER_ID, 1), List.of(USER_ID, MAKER_ID));
        assertThat(first.progress().complete()).isFalse();
        assertThat(second.progress().complete()).isTrue();
        assertThat(second.state().treasuryState().fundingSettlements()).containsEntry(SYMBOL, 301L);
        return row(variant, "SNAPSHOT_CONTINUATION", opening, second.state(), List.of(MAKER_ID), false, false,
                funds(1_000, 1_000, 0, 0, -100, 100, 0, 0, 900, 1_100, 0, 0));
    }

    /**
     * 在一种永续运行时提交另一正反向产品线的注册配置，确认产品线不符并保持原哈希，资金行不变。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    private Row crossLineRejected(Variant variant) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = fundedState(variant, USER_ID, DEFAULT_WALLET);
        // 和当前组合相反的正反向永续合约类型，故意构造跨产品线合约
        ContractType otherType = variant.type() == ContractType.LINEAR_PERPETUAL
                ? ContractType.INVERSE_PERPETUAL : ContractType.LINEAR_PERPETUAL;
        // 用于构造外线合约的参数；类型由外线产品决定
        RegisterInstrumentCommand wrongLine = new RegisterInstrumentCommand(
                SYMBOL, "BTC", variant.quoteAsset(), variant.settleAsset(), variant.notionalMultiplierUnits(), 1,
                variant.settleScaleUnits(),
                (otherType == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                        100_000, 10_000_000, 1_000_000, 0, 1_000_000,
                        List.of(new CoreRiskLimitBracket(1_000_000, 10_000_000, 100_000, 100_000)))),
                0, 0, 0, -1, 0);
        // 拒绝或计算溢出前的业务哈希，失败后必须不变
        long hash = opening.businessStateHash();
        assertThatThrownBy(() -> com.surprising.aeron.service.product.ProductTradingRulesRegistry
                .forProductLine(variant.type().productLine())
                .requireInstrument(CoreInstrument.from(otherType.productLine(), wrongLine)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用固定代码核对原因 */ exception -> assertThat(exception.code()).isEqualTo("PRODUCT_LINE_UNSUPPORTED"));
        assertThat(opening.businessStateHash()).isEqualTo(hash);
        return row(variant, "CROSS_LINE_REJECTED", opening, opening, List.of(), false, false,
                funds(1_000, 0, 0, 0, 0, 0, 0, 0, 1_000, 0, 0, 0));
    }

    /**
     * 同用户建立目标仓、其他标的全仓及逐仓三部分抵押，强平目标后核对另外两仓的数量与保证金仍保留，不能挪用其一百二十五锁定资金。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    private Row isolatedCollateralLeakage(Variant variant) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = stateWithInstrument(variant, false);
        opening = reducer.registerInstrument(opening, instrument(variant, "2", "ETH", false));
        opening = reducer.registerInstrument(opening, instrument(variant, "3", "SOL", false));
        opening = fundedState(opening, USER_ID, 175);
        opening = addPosition(opening, variant, USER_ID, SYMBOL, QUANTITY, ENTRY_PRICE,
                50, variant.marginMode());
        opening = addPosition(opening, variant, USER_ID, "2", 1, ENTRY_PRICE,
                75, CoreMarginMode.CROSS);
        opening = addPosition(opening, variant, USER_ID, "3", 1, ENTRY_PRICE,
                50, CoreMarginMode.ISOLATED);
        // 给附加全仓标的二设置价格后的状态，用于抵押隔离估值
        TradingCoreState markedEth = reducer.applyMarkPrice(opening,
                new ApplyMarkPriceCommand("2", ENTRY_PRICE, 1, 1_700_000_000_000L));
        // 给附加逐仓标的三设置价格后的状态，用于抵押隔离估值
        TradingCoreState markedSol = reducer.applyMarkPrice(markedEth,
                new ApplyMarkPriceCommand("3", ENTRY_PRICE, 1, 1_700_000_000_000L));
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = reducer.applyMarkPrice(markedSol,
                new ApplyMarkPriceCommand(SYMBOL, 90, 2, 1_700_000_000_000L));
        // 本场景业务步骤完成后的冷状态，交给局部断言与资金行使用
        TradingCoreState ending = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, liquidationSequence(marked), 90, 0));

        // 当前需要检查抵押隔离或统计资金的主用户状态
        CoreUserState user = ending.user(USER_ID);
        assertThat(user.positions().get(SYMBOL).marginMode()).isEqualTo(variant.marginMode());
        assertThat(user.balances().get(variant.settleAsset()).lockedUnits()).isEqualTo(125);
        assertThat(user.positions().get("2").marginMode()).isEqualTo(CoreMarginMode.CROSS);
        assertThat(user.positions().get("2").signedQuantitySteps()).isEqualTo(1);
        assertThat(user.positions().get("2").positionMarginUnits()).isEqualTo(75);
        assertThat(user.positions().get("3").marginMode()).isEqualTo(CoreMarginMode.ISOLATED);
        assertThat(user.positions().get("3").signedQuantitySteps()).isEqualTo(1);
        assertThat(user.positions().get("3").positionMarginUnits()).isEqualTo(50);
        assertThat(user.positions().get(SYMBOL).signedQuantitySteps()).isZero();
        assertThat(ending.treasuryState().insuranceBalances())
                .containsEntry(variant.settleAsset(), 50L);
        return row(variant, "ISOLATED_COLLATERAL_LEAKAGE", opening, ending, List.of(), false, false,
                funds(175, 0, 0, 0, -50, 0, 0, 50, 125, 0, 0, 50));
    }

    /**
     * 目标始终为逐仓，另保留全仓七十五及自由资金五十；目标强平后这两部分不变，亏空进入保险需求，不能偷用可用余额补逐仓损失。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    private Row isolatedFreeCollateralLeakage(Variant variant) {
        // 本场景建立的原冷状态，作为后续业务转换或资金行基线
        TradingCoreState opening = stateWithInstrument(variant, false);
        opening = reducer.registerInstrument(opening, instrument(variant, "2", "ETH", false));
        opening = fundedState(opening, USER_ID, 175);
        opening = addPosition(opening, variant, USER_ID, SYMBOL, QUANTITY, ENTRY_PRICE,
                50, CoreMarginMode.ISOLATED);
        opening = addPosition(opening, variant, USER_ID, "2", 1, ENTRY_PRICE,
                75, CoreMarginMode.CROSS);
        assertThat(opening.user(USER_ID).balances().get(variant.settleAsset()).availableUnits()).isEqualTo(50);
        assertThat(opening.user(USER_ID).balances().get(variant.settleAsset()).lockedUnits()).isEqualTo(125);
        // 给附加全仓标的二设置价格后的状态，用于抵押隔离估值
        TradingCoreState markedEth = reducer.applyMarkPrice(opening,
                new ApplyMarkPriceCommand("2", ENTRY_PRICE, 1, 1_700_000_000_000L));
        // 应用本场景标记价和风险扫描后的冷状态，保留价格序列和强平计划
        TradingCoreState marked = reducer.applyMarkPrice(markedEth,
                new ApplyMarkPriceCommand(SYMBOL, 90, 2, 1_700_000_000_000L));
        // 本场景业务步骤完成后的冷状态，交给局部断言与资金行使用
        TradingCoreState ending = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, liquidationSequence(marked), 90, 0));

        // 当前需要检查抵押隔离或统计资金的主用户状态
        CoreUserState user = ending.user(USER_ID);
        // 本场景已执行或确认后的强平状态，用于检查亏空、费用和后续阶段
        CoreLiquidationState result = ending.riskState().liquidations().get(1L);
        assertThat(user.balances().get(variant.settleAsset()).availableUnits()).isEqualTo(50);
        assertThat(user.balances().get(variant.settleAsset()).lockedUnits()).isEqualTo(75);
        assertThat(user.positions().get(SYMBOL).signedQuantitySteps()).isZero();
        assertThat(user.positions().get(SYMBOL).marginMode()).isEqualTo(CoreMarginMode.ISOLATED);
        assertThat(user.positions().get("2").signedQuantitySteps()).isEqualTo(1);
        assertThat(user.positions().get("2").marginMode()).isEqualTo(CoreMarginMode.CROSS);
        assertThat(user.positions().get("2").positionMarginUnits()).isEqualTo(75);
        assertThat(result.deficitUnits()).isEqualTo(linearOrInverse(variant, 50, 61));
        assertThat(result.status()).isEqualTo(CoreLiquidationState.Status.INSURANCE_REQUIRED);
        assertThat(ending.treasuryState().insuranceBalances())
                .containsEntry(variant.settleAsset(), 50L);
        return row(variant, "ISOLATED_FREE_COLLATERAL_LEAKAGE", opening, ending, List.of(), false, false,
                funds(175, 0, 0, 0, -50, 0, 0, 50, 125, 0, 0, 50));
    }

    /**
     * 手工建立低抵押多头及入场价二百、一百五十的两个空头，供强平后的盈利候选与补亏测试。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     */
    private TradingCoreState adlSetup(Variant variant) {
        // 本辅助按配置、注资、挂单或手工持仓顺序推进的冷状态
        TradingCoreState state = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, 100, POSITION_MARGIN);
        state = withPosition(state, variant, MAKER_ID, -QUANTITY, 200, DEFAULT_WALLET, POSITION_MARGIN);
        return withPosition(state, variant, SECOND_MAKER_ID, -QUANTITY, 150, DEFAULT_WALLET, POSITION_MARGIN);
    }

    /**
     * 为用户和第一做市用户建立同价对向十份持仓，分别使用指定钱包余额及默认保证金。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param userWallet 给主用户注入的结算资产数量
     * @param makerWallet 给第一做市用户注入的结算资产数量
     */
    private TradingCoreState oppositePositions(Variant variant, long userWallet, long makerWallet) {
        // 本辅助按配置、注资、挂单或手工持仓顺序推进的冷状态
        TradingCoreState state = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, userWallet,
                POSITION_MARGIN);
        return withPosition(state, variant, MAKER_ID, -QUANTITY, ENTRY_PRICE, makerWallet, POSITION_MARGIN);
    }

    /**
     * 按指定标的档位配置给双方注资，默认标的挂吃单费率均为零。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param tiered 是否采用两档风险配置，第二档要求更高保证金率
     * @param userWallet 给主用户注入的结算资产数量
     * @param makerWallet 给第一做市用户注入的结算资产数量
     */
    private TradingCoreState pairFunded(Variant variant, boolean tiered, long userWallet, long makerWallet) {
        return pairFunded(variant, tiered, userWallet, makerWallet, 0, 0);
    }

    /**
     * 建立指定费率和单档或双档标的后，分别给用户和做市用户注入结算资产；此时没有持仓。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param tiered 是否采用两档风险配置，第二档要求更高保证金率
     * @param userWallet 给主用户注入的结算资产数量
     * @param makerWallet 给第一做市用户注入的结算资产数量
     * @param makerFeeRatePpm 写入标的配置的挂单费率，单位为百万分之一；订单意图辅助最终不读取此值
     * @param takerFeeRatePpm 写入标的配置的吃单费率，单位为百万分之一；订单意图辅助最终不读取此值
     */
    private TradingCoreState pairFunded(Variant variant, boolean tiered, long userWallet, long makerWallet,
                                        long makerFeeRatePpm, long takerFeeRatePpm) {
        // 本辅助按配置、注资、挂单或手工持仓顺序推进的冷状态
        TradingCoreState state = stateWithInstrument(variant, tiered, makerFeeRatePpm, takerFeeRatePpm);
        state = reducer.adjustBalance(state, USER_ID,
                new BalanceAdjustmentCommand(variant.settleAsset(), userWallet));
        return reducer.adjustBalance(state, MAKER_ID,
                new BalanceAdjustmentCommand(variant.settleAsset(), makerWallet));
    }

    /**
     * 双方按指定方向和数量在默认入场价挂限价单，再显式应用一组成交事件，不调用真实 Matcher。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param state 本次转换的原冷状态或需要读取资金的冷状态
     * @param makerOrderId 第一做市用户挂单的订单 ID
     * @param takerOrderId 主用户吃单的订单 ID
     * @param takerSide 主用户吃单方向
     * @param makerSide 第一做市用户挂单方向
     * @param quantity 本次委托或手工持仓数量；持仓辅助中可带正负方向
     */
    private TradingCoreState match(Variant variant, TradingCoreState state, long makerOrderId,
                                   long takerOrderId, CoreOrderSide takerSide, CoreOrderSide makerSide,
                                   long quantity) {
        // 本次按用例顺序挂入订单后的冷状态
        TradingCoreState placed = reducer.placeOrder(state, MAKER_ID,
                order(makerOrderId, variant, makerSide, quantity, false, 0, 0));
        placed = reducer.placeOrder(placed, USER_ID,
                order(takerOrderId, variant, takerSide, quantity, false, 0, 0));
        return reducer.applyMatches(placed, takerOrderId, variant.baseAsset(), variant.quoteAsset(),
                List.of(trade(makerOrderId, MAKER_ID, ENTRY_PRICE, quantity, true, true)));
    }

    /**
     * 把默认入场价和只减仓标志交给定价订单辅助；费率入参继续传递但最终辅助没有读取它们。
     * @param orderId 本次测试委托的订单 ID
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param side 本次委托买卖方向
     * @param quantity 本次委托或手工持仓数量；持仓辅助中可带正负方向
     * @param reduceOnly 本委托是否只减仓
     * @param makerFeeRatePpm 保留的挂单费率入参，最终 pricedOrder 重载当前未读取，不写入下单意图
     * @param takerFeeRatePpm 保留的吃单费率入参，最终 pricedOrder 重载当前未读取，不写入下单意图
     */
    private PlaceOrderCommand order(long orderId, Variant variant, CoreOrderSide side, long quantity,
                                     boolean reduceOnly, long makerFeeRatePpm, long takerFeeRatePpm) {
        return pricedOrder(orderId, variant, side, quantity, ENTRY_PRICE, reduceOnly,
                makerFeeRatePpm, takerFeeRatePpm);
    }

    /**
     * 按指定价格构造普通非只减仓意图，费用入参默认零；实际费用后续按业务配置解析。
     * @param orderId 本次测试委托的订单 ID
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param side 本次委托买卖方向
     * @param quantity 本次委托或手工持仓数量；持仓辅助中可带正负方向
     * @param priceTicks 本委托限价，单位为价格 tick
     */
    private PlaceOrderCommand pricedOrder(long orderId, Variant variant, CoreOrderSide side, long quantity,
                                           long priceTicks) {
        return pricedOrder(orderId, variant, side, quantity, priceTicks, false, 0, 0);
    }

    /**
     * 构造指定标的数量、价格、方向和保证金模式的限价 GTC 净仓意图；makerFeeRatePpm 与 takerFeeRatePpm 当前未读取，不生成费用快照。
     * @param orderId 本次测试委托的订单 ID
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param side 本次委托买卖方向
     * @param quantity 本次委托或手工持仓数量；持仓辅助中可带正负方向
     * @param priceTicks 本委托限价，单位为价格 tick
     * @param reduceOnly 本委托是否只减仓
     * @param makerFeeRatePpm 保留的挂单费率入参，最终 pricedOrder 重载当前未读取，不写入下单意图
     * @param takerFeeRatePpm 保留的吃单费率入参，最终 pricedOrder 重载当前未读取，不写入下单意图
     */
    private PlaceOrderCommand pricedOrder(long orderId, Variant variant, CoreOrderSide side, long quantity,
                                          long priceTicks, boolean reduceOnly,
                                          long makerFeeRatePpm, long takerFeeRatePpm) {
        return new PlaceOrderCommand(orderId, SYMBOL, side, priceTicks, quantity, reduceOnly, variant.marginMode(), CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "");
    }

    /**
     * 为标的一选取至少大于旧序列的价格序列并应用标记价；variant 参数当前未读取，标的固定使用 SYMBOL。
     * @param state 本次转换的原冷状态或需要读取资金的冷状态
     * @param variant 保留的组合参数，当前方法未读取，始终更新默认标的 SYMBOL
     * @param price 本次标记价格，单位为价格 tick
     * @param sequence 期望的最低价格序列，已有价格时会确保严格递增
     */
    private TradingCoreState mark(TradingCoreState state, Variant variant, long price, long sequence) {
        // 默认标的已有价格，供下一序列严格递增判断
        CoreMarkPriceState current = state.riskState().markPrices().get(SYMBOL);
        // 不小于请求且严格超过原价格的下一序列，避免辅助意外提交旧价
        long nextSequence = current == null
                ? sequence : Math.max(sequence, Math.incrementExact(current.priceSequence()));
        return reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand(SYMBOL, price, nextSequence, 1_700_000_000_000L));
    }

    /**
     * 读取强平计划一保存的触发价格序列，供后续执行命令匹配。
     * @param state 本次转换的原冷状态或需要读取资金的冷状态
     */
    private static long liquidationSequence(TradingCoreState state) {
        return state.riskState().liquidations().get(1L).triggerPriceSequence();
    }

    /**
     * 创建带默认标的且已注资的状态，再手工添加指定方向、入场价和保证金的持仓。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param userId 持仓、注资或统计所属用户 ID
     * @param quantity 本次委托或手工持仓数量；持仓辅助中可带正负方向
     * @param entryPrice 手工建立持仓的入场价格 tick
     * @param wallet 本次给用户增加的结算资产数量
     * @param margin 手工建立持仓时从可用余额冻结的保证金数量
     */
    private TradingCoreState withPosition(Variant variant, long userId, long quantity, long entryPrice,
                                          long wallet, long margin) {
        // 本辅助按配置、注资、挂单或手工持仓顺序推进的冷状态
        TradingCoreState state = fundedState(variant, userId, wallet);
        return addPosition(state, variant, userId, SYMBOL, quantity, entryPrice, margin, variant.marginMode());
    }

    /**
     * 在已有状态给指定用户追加钱包余额，再手工添加该用户默认标的持仓，保留已有其他用户。
     * @param state 本次转换的原冷状态或需要读取资金的冷状态
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param userId 持仓、注资或统计所属用户 ID
     * @param quantity 本次委托或手工持仓数量；持仓辅助中可带正负方向
     * @param entryPrice 手工建立持仓的入场价格 tick
     * @param wallet 本次给用户增加的结算资产数量
     * @param margin 手工建立持仓时从可用余额冻结的保证金数量
     */
    private TradingCoreState withPosition(TradingCoreState state, Variant variant, long userId, long quantity,
                                          long entryPrice, long wallet, long margin) {
        state = fundedState(state, userId, wallet);
        return addPosition(state, variant, userId, SYMBOL, quantity, entryPrice, margin, variant.marginMode());
    }

    /**
     * 复制用户余额和持仓表，冻结指定保证金并写入净仓持仓，分别增加用户及全局修订；这是测试数据拼装，不执行生产持仓准入。
     * @param state 本次转换的原冷状态或需要读取资金的冷状态
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param userId 持仓、注资或统计所属用户 ID
     * @param instrumentId 需要建立的规范数字标的 ID 文本
     * @param quantity 本次委托或手工持仓数量；持仓辅助中可带正负方向
     * @param entryPrice 手工建立持仓的入场价格 tick
     * @param margin 手工建立持仓时从可用余额冻结的保证金数量
     * @param marginMode 本持仓或矩阵键采用的全仓、逐仓模式
     */
    private TradingCoreState addPosition(TradingCoreState state, Variant variant, long userId, String instrumentId,
                                         long quantity, long entryPrice, long margin, CoreMarginMode marginMode) {
        // 需要新增手工持仓的原用户状态
        CoreUserState current = state.user(userId);
        // 用户该结算资产的原余额，不存在时临时以零余额作为起点
        AssetBalance currentBalance = current.balances().getOrDefault(variant.settleAsset(),
                new AssetBalance(variant.settleAsset(), 0, 0));
        // 从原可用金额冻结指定保证金后的新余额
        AssetBalance nextBalance = currentBalance.reserve(margin);
        // 复制的余额表，只替换需要冻结保证金的资产
        Map<String, AssetBalance> balances = new TreeMap<>(current.balances());
        balances.put(variant.settleAsset(), nextBalance);
        // 复制的用户持仓表，插入本次手工构造或替换的持仓
        Map<String, CorePositionState> positions = new TreeMap<>(current.positions());
        positions.put(instrumentId, new CorePositionState(instrumentId, variant.settleAsset(), marginMode,
                CorePositionSide.NET, quantity, entryPrice,
                Math.multiplyExact(Math.absExact(quantity), entryPrice), 0, margin));
        // 替换对应余额或持仓并增加修订后的用户值
        CoreUserState nextUser = new CoreUserState(state.productLine(), userId,
                Math.incrementExact(current.revision()), balances, current.reservations(), positions,
                current.positionMode());
        // 复制的完整用户表，只替换本次指定用户
        Map<Long, CoreUserState> users = new TreeMap<>(state.users());
        users.put(userId, nextUser);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users,
                state.orders(), state.instruments(), state.riskState(), state.treasuryState());
    }

    /**
     * 创建单档默认标的后给指定用户注入钱包余额。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param userId 持仓、注资或统计所属用户 ID
     * @param wallet 本次给用户增加的结算资产数量
     */
    private TradingCoreState fundedState(Variant variant, long userId, long wallet) {
        return reducer.adjustBalance(stateWithInstrument(variant, false), userId,
                new BalanceAdjustmentCommand(variant.settleAsset(), wallet));
    }

    /**
     * 从已有标的一读取结算资产，给指定用户追加钱包余额。
     * @param state 本次转换的原冷状态或需要读取资金的冷状态
     * @param userId 持仓、注资或统计所属用户 ID
     * @param wallet 本次给用户增加的结算资产数量
     */
    private TradingCoreState fundedState(TradingCoreState state, long userId, long wallet) {
        return reducer.adjustBalance(state, userId,
                new BalanceAdjustmentCommand(state.instruments().get(SYMBOL).settleAsset(), wallet));
    }

    /**
     * 创建零费率的单档或双档标的一，并初始化默认标记价。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param tiered 是否采用两档风险配置，第二档要求更高保证金率
     */
    private TradingCoreState stateWithInstrument(Variant variant, boolean tiered) {
        return stateWithInstrument(variant, tiered, 0, 0);
    }

    /**
     * 在该产品线空状态中注册指定费率和档位的标的一，再以默认入场价初始化价格。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param tiered 是否采用两档风险配置，第二档要求更高保证金率
     * @param makerFeeRatePpm 写入标的配置的挂单费率，单位为百万分之一；订单意图辅助最终不读取此值
     * @param takerFeeRatePpm 写入标的配置的吃单费率，单位为百万分之一；订单意图辅助最终不读取此值
     */
    private TradingCoreState stateWithInstrument(Variant variant, boolean tiered,
                                                 long makerFeeRatePpm, long takerFeeRatePpm) {
        // 本辅助按配置、注资、挂单或手工持仓顺序推进的冷状态
        TradingCoreState state = reducer.registerInstrument(TradingCoreState.empty(variant.productLine()),
                instrument(variant, SYMBOL, "BTC", tiered, makerFeeRatePpm, takerFeeRatePpm));
        return reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand(SYMBOL, ENTRY_PRICE, 1, 1_700_000_000_000L));
    }

    /**
     * 构造指定标的及基础资产的单档或双档永续配置，费率默认零。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param instrumentId 需要建立的规范数字标的 ID 文本
     * @param baseAsset 该测试标的基础资产代码
     * @param tiered 是否采用两档风险配置，第二档要求更高保证金率
     */
    private RegisterInstrumentCommand instrument(Variant variant, String instrumentId, String baseAsset,
                                               boolean tiered) {
        return instrument(variant, instrumentId, baseAsset, tiered, 0, 0);
    }

    /**
     * 根据 tiered 选择百分之十单档或包含百分之二十的双档风险规则，写入产品线单位、费率及相应杠杆上限。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param instrumentId 需要建立的规范数字标的 ID 文本
     * @param baseAsset 该测试标的基础资产代码
     * @param tiered 是否采用两档风险配置，第二档要求更高保证金率
     * @param makerFeeRatePpm 写入标的配置的挂单费率，单位为百万分之一；订单意图辅助最终不读取此值
     * @param takerFeeRatePpm 写入标的配置的吃单费率，单位为百万分之一；订单意图辅助最终不读取此值
     */
    private RegisterInstrumentCommand instrument(Variant variant, String instrumentId, String baseAsset,
                                               boolean tiered, long makerFeeRatePpm, long takerFeeRatePpm) {
        // 单档或双档连续风险限额列表，按本辅助的固定档位参数创建
        List<CoreRiskLimitBracket> brackets = tiered
                ? List.of(new CoreRiskLimitBracket(1_000, 5_000_000, 100_000, 100_000),
                new CoreRiskLimitBracket(2_500, 5_000_000, 200_000, 200_000))
                : List.of(new CoreRiskLimitBracket(1_000_000, 10_000_000, 100_000, 100_000));
        // 单档允许十倍、双档允许五倍的标的杠杆上限
        long maxLeveragePpm = tiered ? 5_000_000 : 10_000_000;
        return new RegisterInstrumentCommand(
                instrumentId, baseAsset, variant.quoteAsset(), variant.settleAsset(), variant.notionalMultiplierUnits(),
                1, variant.settleScaleUnits(),
                (variant.type() == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                        100_000, maxLeveragePpm, tiered ? 2_500 : 1_000_000, 0, 1_000_000, brackets)),
                makerFeeRatePpm, takerFeeRatePpm, 0, -1, 0);
    }

    /**
     * 复制强平映射替换指定计划，保留其他风险分量并增加全局修订，供人为调整计划量或游标的测试。
     * @param state 本次转换的原冷状态或需要读取资金的冷状态
     * @param liquidation 要替换进冷状态的强平计划或进度
     */
    private TradingCoreState replaceLiquidation(TradingCoreState state, CoreLiquidationState liquidation) {
        // 复制的强平状态表，只替换本次指定的计划或进度
        Map<Long, CoreLiquidationState> liquidations = new TreeMap<>(state.riskState().liquidations());
        liquidations.put(liquidation.liquidationId(), liquidation);
        // 替换强平表后重新组合的风险状态，其余原分量保留
        CoreRiskState risk = new CoreRiskState(state.riskState().markPrices(), state.riskState().snapshots(),
                liquidations, state.riskState().scans(), state.riskState().nextLiquidationId());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), state.users(),
                state.orders(), state.instruments(), risk, state.treasuryState());
    }

    /**
     * 复制指定用户持仓表替换一个持仓，保留余额等其他用户事实，再增加用户与全局修订。
     * @param state 本次转换的原冷状态或需要读取资金的冷状态
     * @param userId 持仓、注资或统计所属用户 ID
     * @param position 要替换到该用户持仓表中的新持仓值
     */
    private TradingCoreState replacePosition(TradingCoreState state, long userId, CorePositionState position) {
        // 需要替换单个持仓的原用户状态
        CoreUserState current = state.user(userId);
        // 复制的用户持仓表，插入本次手工构造或替换的持仓
        Map<String, CorePositionState> positions = new TreeMap<>(current.positions());
        positions.put(position.key(), position);
        // 替换对应余额或持仓并增加修订后的用户值
        CoreUserState nextUser = new CoreUserState(current.productLine(), current.userId(),
                Math.incrementExact(current.revision()), current.balances(), current.reservations(), positions,
                current.positionMode());
        // 复制的完整用户表，只替换本次指定用户
        Map<Long, CoreUserState> users = new TreeMap<>(state.users());
        users.put(userId, nextUser);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users,
                state.orders(), state.instruments(), state.riskState(), state.treasuryState());
    }

    /**
     * 把本组合和场景生成的唯一键、前后状态、做市用户清单及资金统计口径装成矩阵行。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param scenario 本行需要登记的场景名，用于固定清单与诊断
     * @param opening 本资金行选定的期初冷状态
     * @param ending 本资金行选定的期末冷状态
     * @param makerIds 本资金行统计包含的做市用户 ID 清单
     * @param economic 是否在用户现金余额上加未实现盈亏作为统计值
     * @param includeLiquidationDeficit 保险统计值是否扣除默认标的强平任务剩余亏空
     * @param funds 手工列出的期初、净变化和期末预期值
     */
    private Row row(Variant variant, String scenario, TradingCoreState opening, TradingCoreState ending,
                    List<Long> makerIds, boolean economic, boolean includeLiquidationDeficit, Funds funds) {
        return new Row(rowKey(variant.type(), variant.marginMode(), scenario), variant, opening, ending,
                makerIds, economic, includeLiquidationDeficit, funds);
    }

    /**
     * 逐行拒绝重复键，按现金或含未实现盈亏口径抽取期初期末值，核对四类净变化及 Funds 差额，并比较观察到的合计变化；此处不额外把每行预期外部流量之和强制为零。
     * @param rows 已经执行并生成的矩阵行集合
     */
    private void assertRows(List<Row> rows) {
        // 本次已断言的场景键集合，重复加入立即失败
        Set<String> seen = new LinkedHashSet<>();
        for (Row /* 当前需要检查场景身份或资金前后值的一行 */ row : rows) {
            assertThat(seen.add(row.key())).as("duplicate matrix row").isTrue();
            // 当前矩阵行手工列出的期初、净变化与期末资金值
            Funds expected = row.funds();
            // 按本行口径从期初状态读出的用户资金值
            long openingUser = userValue(row, row.opening(), USER_ID);
            // 按本行口径从期初状态读出的做市用户资金值
            long openingMaker = makerValue(row, row.opening());
            // 从期初状态读出的手续费账资金值
            long openingFee = feeValue(row.opening(), row.variant().settleAsset());
            // 按本行口径从期初状态读出的保险账资金值
            long openingInsurance = insuranceValue(row, row.opening());
            // 按本行口径从期末状态读出的用户资金值
            long endingUser = userValue(row, row.ending(), USER_ID);
            // 按本行口径从期末状态读出的做市用户资金值
            long endingMaker = makerValue(row, row.ending());
            // 从期末状态读出的手续费账资金值
            long endingFee = feeValue(row.ending(), row.variant().settleAsset());
            // 按本行口径从期末状态读出的保险账资金值
            long endingInsurance = insuranceValue(row, row.ending());
            assertThat(openingUser).as(row.key() + " user opening").isEqualTo(expected.userOpening());
            assertThat(openingMaker).as(row.key() + " maker opening").isEqualTo(expected.makerOpening());
            assertThat(openingFee).as(row.key() + " fee opening").isEqualTo(expected.feeOpening());
            assertThat(openingInsurance).as(row.key() + " insurance opening")
                    .isEqualTo(expected.insuranceOpening());
            assertThat(endingUser).as(row.key() + " user ending").isEqualTo(expected.userEnding());
            assertThat(endingMaker).as(row.key() + " maker ending").isEqualTo(expected.makerEnding());
            assertThat(endingFee).as(row.key() + " fee ending").isEqualTo(expected.feeEnding());
            assertThat(endingInsurance).as(row.key() + " insurance ending")
                    .isEqualTo(expected.insuranceEnding());
            assertThat(endingUser - openingUser).as(row.key() + " user flow")
                    .isEqualTo(expected.userFlow());
            assertThat(endingMaker - openingMaker).as(row.key() + " maker flow")
                    .isEqualTo(expected.makerFlow());
            assertThat(endingFee - openingFee).as(row.key() + " fee flow")
                    .isEqualTo(expected.feeFlow());
            assertThat(endingInsurance - openingInsurance).as(row.key() + " insurance flow")
                    .isEqualTo(expected.insuranceFlow());
            assertThat(expected.difference()).as(row.key() + " FUNDS_DIFFERENCE").isZero();
            assertThat((endingUser + endingMaker + endingFee + endingInsurance)
                    - (openingUser + openingMaker + openingFee + openingInsurance))
                    .as(row.key() + " observed FUNDS_DIFFERENCE")
                    .isEqualTo(expected.userFlow() + expected.makerFlow()
                            + expected.feeFlow() + expected.insuranceFlow());
        }
    }

    /**
     * 只选择指定场景的行键，与固定必需清单逐项比较并核对数量，避免缺行或重复行被集合掩盖。
     * @param rows 已经执行并生成的矩阵行集合
     * @param scenarios 本次完整性检查需要覆盖的场景名清单
     */
    private void assertManifest(List<Row> rows, List<String> scenarios) {
        // 当前所选场景必须覆盖的固定矩阵键集合
        Set<String> expected = requiredRows(scenarios);
        // 从实际行按场景筛选得到的矩阵键集合
        Set<String> actual = new LinkedHashSet<>();
        rows.stream().filter(/* 当前需要检查场景身份或资金前后值的一行 */ row -> scenarios.contains(row.key().split(":")[2])).forEach(/* 当前需要检查场景身份或资金前后值的一行 */ row -> actual.add(row.key()));
        assertThat(actual).as("matrix completeness manifest").containsExactlyInAnyOrderElementsOf(expected);
        assertThat(rows.stream().filter(/* 当前需要检查场景身份或资金前后值的一行 */ row -> scenarios.contains(row.key().split(":")[2])).count())
                .isEqualTo(expected.size());
    }

    /**
     * 从七十二个固定要求键中筛出指定场景，保留固定顺序用于完整性断言。
     * @param scenarios 本次完整性检查需要覆盖的场景名清单
     */
    private static Set<String> requiredRows(List<String> scenarios) {
        // 从显式要求清单选择出的当前必需矩阵键
        Set<String> required = new LinkedHashSet<>();
        for (String /* 当前统计或筛选的矩阵场景键 */ key : REQUIRED_ROWS) {
            // 从固定要求键最后一段提取的场景名，用来选择本次要求行
            String scenario = key.substring(key.lastIndexOf(':') + 1);
            if (scenarios.contains(scenario)) {
                required.add(key);
            }
        }
        return required;
    }

    /**
     * 用户不存在返回零；普通行统计结算资产总额，economic 行另加该资产全部非零持仓按已存在标记价计算的未实现盈亏，缺价格或标的则跳过该持仓。
     * @param row 决定产品资产、用户统计和保险亏空口径的矩阵行
     * @param state 本次转换的原冷状态或需要读取资金的冷状态
     * @param userId 持仓、注资或统计所属用户 ID
     */
    private long userValue(Row row, TradingCoreState state, long userId) {
        // 当前需要检查抵押隔离或统计资金的主用户状态
        CoreUserState user = state.user(userId);
        if (user == null) return 0;
        // 当前用户结算资产总现金，economic 行在后面继续加未实现盈亏
        long value = user.totalUnits(row.variant().settleAsset());
        if (!row.economic()) return value;
        for (CorePositionState /* 当前需要计入未实现盈亏的非零结算资产持仓 */ position : user.positions().values()) {
            if (position.signedQuantitySteps() == 0 || !position.marginAsset().equals(row.variant().settleAsset())) {
                continue;
            }
            // 该非零持仓的已存在标记价格，缺失时不纳入经济价值补项
            CoreMarkPriceState mark = state.riskState().markPrices().get(position.instrumentId());
            // 该持仓所属标的配置，缺失时不纳入经济价值补项
            CoreInstrument instrument = state.instruments().get(position.instrumentId());
            if (mark != null && instrument != null) {
                value = Math.addExact(value, CoreContractMath.pnlUnits(instrument,
                        position.signedQuantitySteps(), position.entryPriceTicks(), mark.markPriceTicks()));
            }
        }
        return value;
    }

    /**
     * 按本行相同资金口径累加所列做市用户，使用精确加法检查溢出。
     * @param row 决定产品资产、用户统计和保险亏空口径的矩阵行
     * @param state 本次转换的原冷状态或需要读取资金的冷状态
     */
    private long makerValue(Row row, TradingCoreState state) {
        // 所列做市用户按本行资金口径的累计值，从零开始
        long value = 0;
        for (Long /* 本行做市用户清单中当前需要合计的一位用户 */ makerId : row.makerIds()) {
            value = Math.addExact(value, userValue(row, state, makerId));
        }
        return value;
    }

    /**
     * 读取指定资产普通手续费余额，没有则为零。
     * @param state 本次转换的原冷状态或需要读取资金的冷状态
     * @param asset 需要读取普通手续费余额的资产代码
     */
    private static long feeValue(TradingCoreState state, String asset) {
        return state.treasuryState().feeBalances().getOrDefault(asset, 0L);
    }

    /**
     * 读取本行结算资产保险余额；需要计入亏空时再减去默认标的一各强平任务的剩余亏空，并非直接使用财库全部资产亏空总额。
     * @param row 决定产品资产、用户统计和保险亏空口径的矩阵行
     * @param state 本次转换的原冷状态或需要读取资金的冷状态
     */
    private long insuranceValue(Row row, TradingCoreState state) {
        // 本行结算资产的保险余额，是否再扣默认标的强平亏空由标志决定
        long value = state.treasuryState().insuranceBalances()
                .getOrDefault(row.variant().settleAsset(), 0L);
        if (!row.includeLiquidationDeficit()) return value;
        // 默认标的一所有强平任务剩余亏空之和，用于保险经济值扣项
        long deficit = state.riskState().liquidations().values().stream()
                .filter(/* 当前默认标的的强平任务，用于累计剩余亏空 */ liquidation -> liquidation.instrumentId().equals(SYMBOL))
                .mapToLong(CoreLiquidationState::deficitUnits).sum();
        return Math.subtractExact(value, deficit);
    }

    /**
     * 把手工列出的四类期初、净变化和期末值装为预期资金记录，不在构造时自动推导或校验。
     * @param userOpening 期初用户资金统计值，统计口径由对应矩阵行决定
     * @param makerOpening 期初纳入该行的做市用户资金统计值
     * @param feeOpening 期初手续费账统计值
     * @param insuranceOpening 期初保险账统计值，是否扣除待补亏空由该行口径决定
     * @param userFlow 本行预期的用户资金净变化
     * @param makerFlow 本行预期的做市用户资金净变化
     * @param feeFlow 本行预期的手续费账净变化
     * @param insuranceFlow 本行预期的保险账统计值净变化
     * @param userEnding 期末用户资金统计值
     * @param makerEnding 期末纳入该行的做市用户资金统计值
     * @param feeEnding 期末手续费账统计值
     * @param insuranceEnding 期末保险账统计值
     */
    private static Funds funds(long userOpening, long makerOpening, long feeOpening, long insuranceOpening,
                               long userFlow, long makerFlow, long feeFlow, long insuranceFlow,
                               long userEnding, long makerEnding, long feeEnding, long insuranceEnding) {
        return new Funds(userOpening, makerOpening, feeOpening, insuranceOpening, userFlow, makerFlow,
                feeFlow, insuranceFlow, userEnding, makerEnding, feeEnding, insuranceEnding);
    }

    /**
     * 按本测试支持的两种永续类型选择已列出的正向或反向期望值，不计算业务金额。
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param linear 正向永续需要返回的手工期望值
     * @param inverse 反向永续需要返回的手工期望值
     */
    private static long linearOrInverse(Variant variant, long linear, long inverse) {
        return variant.type() == ContractType.LINEAR_PERPETUAL ? linear : inverse;
    }

    /**
     * 按合约类型、保证金模式和场景拼出矩阵唯一键。
     * @param contractType 生成矩阵键所用的具体永续合约类型
     * @param marginMode 本持仓或矩阵键采用的全仓、逐仓模式
     * @param scenario 本行需要登记的场景名，用于固定清单与诊断
     */
    private static String rowKey(ContractType contractType, CoreMarginMode marginMode, String scenario) {
        return contractType.name() + ":" + marginMode.name() + ":" + scenario;
    }

    /**
     * 一种永续产品及保证金模式的固定测试参数，金额单位随正反向组合选择。
     * @param type 本组合具体的正向或反向永续合约类型
     * @param marginMode 本持仓或矩阵键采用的全仓、逐仓模式
     * @param baseAsset 该测试标的基础资产代码
     * @param quoteAsset 本组合报价资产代码
     * @param settleAsset 本组合结算资产代码
     * @param notionalMultiplierUnits 本组合合约名义金额换算乘数
     * @param settleScaleUnits 本组合结算资产整数金额换算比例
     */
    private record Variant(ContractType type, CoreMarginMode marginMode, String baseAsset, String quoteAsset,
                           String settleAsset, long notionalMultiplierUnits, long settleScaleUnits) {
        /**
         * 返回该组合的合约类型所属独立产品线。
         */
        private ProductLine productLine() {
            return type.productLine();
        }
    }

    /**
     * 一行手工列出的四类资金期初、净变化及期末预期，用 difference 检查未解释差额；不是自动记账器。
     * @param userOpening 期初用户资金统计值，统计口径由对应矩阵行决定
     * @param makerOpening 期初纳入该行的做市用户资金统计值
     * @param feeOpening 期初手续费账统计值
     * @param insuranceOpening 期初保险账统计值，是否扣除待补亏空由该行口径决定
     * @param userFlow 本行预期的用户资金净变化
     * @param makerFlow 本行预期的做市用户资金净变化
     * @param feeFlow 本行预期的手续费账净变化
     * @param insuranceFlow 本行预期的保险账统计值净变化
     * @param userEnding 期末用户资金统计值
     * @param makerEnding 期末纳入该行的做市用户资金统计值
     * @param feeEnding 期末手续费账统计值
     * @param insuranceEnding 期末保险账统计值
     */
    private record Funds(long userOpening, long makerOpening, long feeOpening, long insuranceOpening,
                         long userFlow, long makerFlow, long feeFlow, long insuranceFlow,
                         long userEnding, long makerEnding, long feeEnding, long insuranceEnding) {
        /**
         * 用四类期末减期初再减已列净变化，返回该预期资金行的未解释差额，调用方要求为零。
         */
        private long difference() {
            return userEnding + makerEnding + feeEnding + insuranceEnding
                    - userOpening - makerOpening - feeOpening - insuranceOpening
                    - userFlow - makerFlow - feeFlow - insuranceFlow;
        }
    }

    /**
     * 一行已执行场景的前后冷状态与统计参数；economic 和 includeLiquidationDeficit 决定是否把未实现盈亏及待补亏空算入资金值。
     * @param key 本行的合约类型、保证金模式和场景唯一键
     * @param variant 本次正反向永续、全仓逐仓及资产单位的测试组合
     * @param opening 本资金行选定的期初冷状态
     * @param ending 本资金行选定的期末冷状态
     * @param makerIds 本资金行统计包含的做市用户 ID 清单
     * @param economic 是否在用户现金余额上加未实现盈亏作为统计值
     * @param includeLiquidationDeficit 保险统计值是否扣除默认标的强平任务剩余亏空
     * @param funds 手工列出的期初、净变化和期末预期值
     */
    private record Row(String key, Variant variant, TradingCoreState opening, TradingCoreState ending,
                       List<Long> makerIds, boolean economic, boolean includeLiquidationDeficit, Funds funds) {
    }
}
