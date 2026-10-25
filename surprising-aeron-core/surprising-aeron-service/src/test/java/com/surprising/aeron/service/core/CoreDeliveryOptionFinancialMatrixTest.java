package com.surprising.aeron.service.core;

import com.surprising.aeron.service.account.AccountControlTask;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;
import com.surprising.aeron.service.settlement.RuntimeDerivativeMatchFixture;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.model.CoreRiskSnapshot;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.core.index.AdlPositionIndex;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.order.state.RuntimeTestStateTransitions;
import com.surprising.aeron.service.query.state.*;
import com.surprising.aeron.service.query.state.RuntimeRiskQueryService;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import com.surprising.aeron.service.settlement.lifecycle.RuntimeLifecycleSettlementContinuation;
import com.surprising.aeron.service.snapshot.state.*;
import com.surprising.aeron.service.snapshot.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.snapshot.state.TradingStateSnapshotCodec;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.aeron.service.treasury.RuntimeInsuranceFundStateTransitions;
import com.surprising.aeron.support.math.*;
import com.surprising.aeron.support.math.OptionContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static com.surprising.aeron.service.matcher.MatcherEventFixtures.trade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交割与期权资金测试：四种交割全仓逐仓和六种看涨看跌实平虚值资金行，另覆盖分段恢复、保险不足重试、抵押隔离、期权开平仓及强平/ADL。原 System Ledger 用例会启动真实账户和账本任务；大多数场景仍用冷转换或显式成交事件，不等于集群网络端到端。
 */
class CoreDeliveryOptionFinancialMatrixTest {

    /** 主用户一百零一，通常作为期权买方或交割多头。 */
    private static final long USER_ID = 101;
    /** 第一做市用户二百零二，通常作为期权卖方或交割空头。 */
    private static final long MAKER_ID = 202;
    /** 第二做市用户二百零三，用于跨账户路由的对向持仓场景。 */
    private static final long SECOND_MAKER_ID = 203;
    /** 默认期权成交或交割持仓两份。 */
    private static final long QUANTITY = 2;
    /** 默认交割持仓入场价一百。 */
    private static final long ENTRY_PRICE = 100;
    /** 期权行权价一百，供实平虚值和现金推导。 */
    private static final long STRIKE_PRICE = 100;
    /** 期权默认权利金价格十。 */
    private static final long PREMIUM_PRICE = 10;
    /** 常规场景每位用户注入的结算资产两千。 */
    private static final long WALLET = 2_000;
    /** 手工建立默认交割持仓冻结的一百保证金。 */
    private static final long POSITION_MARGIN = 100;
    /** 保留的期权费率常量十万；当前本类场景没有读取它，实际注册标的费率为零。 */
    private static final long OPTION_FEE_RATE_PPM = 100_000;
    /** 四个交割全仓逐仓键和六个看涨看跌实平虚值键，共十项固定覆盖要求。 */
    private static final Set<String> REQUIRED_ROWS = Set.of(
            "LINEAR_DELIVERY:CROSS", "LINEAR_DELIVERY:ISOLATED",
            "INVERSE_DELIVERY:CROSS", "INVERSE_DELIVERY:ISOLATED",
            "OPTION:CALL:ITM", "OPTION:CALL:ATM", "OPTION:CALL:OTM",
            "OPTION:PUT:ITM", "OPTION:PUT:ATM", "OPTION:PUT:OTM");

    /** 四个交割组合和六个全仓期权组合，固定各自资产单位及结算价。 */
    private static final List<Variant> VARIANTS = List.of(
            new Variant(ContractType.LINEAR_DELIVERY, CoreMarginMode.CROSS, null, "", 120, 1, 1,
                    "BTC", "USDT", "USDT"),
            new Variant(ContractType.LINEAR_DELIVERY, CoreMarginMode.ISOLATED, null, "", 120, 1, 1,
                    "BTC", "USDT", "USDT"),
            new Variant(ContractType.INVERSE_DELIVERY, CoreMarginMode.CROSS, null, "", 120, 100, 100,
                    "BTC", "USD", "BTC"),
            new Variant(ContractType.INVERSE_DELIVERY, CoreMarginMode.ISOLATED, null, "", 120, 100, 100,
                    "BTC", "USD", "BTC"),
            new Variant(ContractType.VANILLA_OPTION, CoreMarginMode.CROSS, OptionType.CALL, "ITM", 120, 1, 1,
                    "BTC", "USDT", "USDT"),
            new Variant(ContractType.VANILLA_OPTION, CoreMarginMode.CROSS, OptionType.CALL, "ATM", 100, 1, 1,
                    "BTC", "USDT", "USDT"),
            new Variant(ContractType.VANILLA_OPTION, CoreMarginMode.CROSS, OptionType.CALL, "OTM", 80, 1, 1,
                    "BTC", "USDT", "USDT"),
            new Variant(ContractType.VANILLA_OPTION, CoreMarginMode.CROSS, OptionType.PUT, "ITM", 80, 1, 1,
                    "BTC", "USDT", "USDT"),
            new Variant(ContractType.VANILLA_OPTION, CoreMarginMode.CROSS, OptionType.PUT, "ATM", 100, 1, 1,
                    "BTC", "USDT", "USDT"),
            new Variant(ContractType.VANILLA_OPTION, CoreMarginMode.CROSS, OptionType.PUT, "OTM", 120, 1, 1,
                    "BTC", "USDT", "USDT"));

    /**
     * 默认正向交割为两位用户建立对向持仓，直接结算后确认进度完成并和冷转换完整状态比较；没有启动账户或账本工作线程。
     */
    @Test
    void crossLaneExpiryReturnsClearingContributionsBeforeSequencerTreasuryApply() {
        // 本次执行的交割或期权测试组合
        Variant variant = VARIANTS.getFirst();
        // 本场景准备好的原冷状态，后续结算或风险操作由此开始
        TradingCoreState opening = fundedState(variant, USER_ID, WALLET);
        opening = fundedState(opening, SECOND_MAKER_ID, WALLET);
        opening = addPosition(opening, USER_ID, QUANTITY, variant);
        opening = addPosition(opening, SECOND_MAKER_ID, -QUANTITY, variant);
        // 同标的结算七百零一命令，附带不可信单份期权现金九千九百九十九，实际金额由核心推导
        SettleInstrumentCommand command = new SettleInstrumentCommand(701, variant.instrumentId(), variant.settlementPriceTicks());
        // 冷转换直接结算相同对向持仓后的完整预期状态
        TradingCoreState expected = reducer.settleInstrument(opening, command);
        // 本次投影、业务辅助和物化共用的独立整数身份表
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (/* 本次直接业务或异步控制使用的运行时，按既有资源作用域或 finally 关闭 */ TradingAccountRuntime runtime = RuntimeStateProjector.project(opening, identities)) {
            // 直接结算方法返回的用户和订单游标及完成状态
            CoreSettlementProgressView actual = RuntimeLifecycleSettlementFixture.applyRuntime(command,
                    List.of(USER_ID, SECOND_MAKER_ID), new ActiveOrderIndex(opening), runtime, identities);

            assertThat(actual.complete()).isTrue();
            RuntimeStateParityChecker.assertMatches(expected, identities, runtime);
        }
    }

    /**
     * 十种交割或期权组合启动账户 Lane 和原 System Ledger，异步完成结算并提交，比较冷结果；随后确认 Owner 不能直接读取财库保险余额。
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("ledgerExpiryVariants")
    void expiryMoneyBudgetAndProgressUseOriginalSystemLedger(Variant variant) {
        // 本场景准备好的原冷状态，后续结算或风险操作由此开始
        TradingCoreState opening = fundedState(variant, USER_ID, WALLET);
        opening = fundedState(opening, SECOND_MAKER_ID, WALLET);
        opening = addPosition(opening, USER_ID, QUANTITY, variant);
        opening = addPosition(opening, SECOND_MAKER_ID, -QUANTITY, variant);
        // 交给原账本续步的结算七百零一命令，采用本组合结算价格
        var command = new SettleInstrumentCommand(701, variant.instrumentId(), variant.settlementPriceTicks());
        // 冷转换完成同一结算后的完整预期状态，供异步账本提交结果比较
        TradingCoreState expected = reducer.settleInstrument(opening, command);
        // 本次投影、业务辅助和物化共用的独立整数身份表
        var identities = new RuntimeIdentityRegistry();
        try (/* 本次直接业务或异步控制使用的运行时，按既有资源作用域或 finally 关闭 */ var runtime = RuntimeStateProjector.project(opening, identities)) {
            runtime.startAccountLanes(); runtime.startSystemLedgerLane(); runtime.enterAsynchronousCommandScope();
            try {
                // 本段到期结算可轮询工作；任务完成不代表整体结算进度已经 complete
                var work = RuntimeLifecycleSettlementContinuation.prepare(null, command, List.of(USER_ID, SECOND_MAKER_ID),
                        new ActiveOrderIndex(opening), runtime, identities);
                LedgerControlTestSupport.await(runtime, work::poll);
                assertThat(work.result().complete()).isTrue();
                assertThat(work.poll()).isTrue();
                assertThat(LedgerControlTestSupport.commit(runtime, identities, false)).isEqualTo(expected);
                assertThatThrownBy(() -> runtime.treasury().insurance(identities.assetId(variant.settleAsset())))
                        .isInstanceOf(IllegalStateException.class);
            } finally { runtime.exitAsynchronousCommandScope(); }
        }
    }

    /**
     * 逐仓交割损失超过保证金时，首段任务结束但结算仍缺保险二十、游标及处理人数保持零；与冷路径比较后给保险注资二十，复用任务重试完成，继续比较完整状态并确认保险耗尽。
     */
    @Test
    void insuranceShortfallKeepsUnsettledUserCursorAndRetriesOnLedgerAfterFunding() {
        // 本次执行的交割或期权测试组合
        Variant variant = VARIANTS.get(1);
        // 本场景准备好的原冷状态，后续结算或风险操作由此开始
        TradingCoreState opening = fundedState(variant, USER_ID, 200);
        opening = addPosition(opening, USER_ID, variant.instrumentId(), 1, 160, 20, variant);
        // 逐仓交割结算七百零二命令，固定价格一百二十，保险不足后重试同一业务身份
        var command = new SettleInstrumentCommand(702, variant.instrumentId(), 120);
        // 本次投影、业务辅助和物化共用的独立整数身份表
        var identities = new RuntimeIdentityRegistry();
        // 只给冷对照运行时使用的独立身份表
        var coldIdentities = new RuntimeIdentityRegistry();
        try (/* 本次直接业务或异步控制使用的运行时，按既有资源作用域或 finally 关闭 */ var runtime = RuntimeStateProjector.project(opening, identities);
             /* 同一起点投影的冷运行时，和异步账本路径逐步比较 */ var cold = RuntimeStateProjector.project(opening, coldIdentities)) {
            // 从原状态建立的活跃订单索引，结算首段和补款重试复用
            var index = new ActiveOrderIndex(opening);
            runtime.startAccountLanes(); runtime.startSystemLedgerLane(); runtime.enterAsynchronousCommandScope();
            try {
                // 本段到期结算可轮询工作；任务完成不代表整体结算进度已经 complete
                var work = RuntimeLifecycleSettlementContinuation.prepare(null, command, List.of(USER_ID), index, runtime, identities);
                LedgerControlTestSupport.await(runtime, work::poll);
                assertThat(work.result().requiredInsuranceUnits()).isEqualTo(20);
                assertThat(work.result().nextCursorUserId()).isZero();
                assertThat(work.result().processedUsers()).isZero();
                RuntimeLifecycleSettlementFixture.applyRuntime(command, List.of(USER_ID), index, cold, coldIdentities);
                assertThat(LedgerControlTestSupport.commit(runtime, identities, false)).isEqualTo(RuntimeStateMaterializer.materialize(cold, coldIdentities));
                cold.clearChangedKeys();
                // 给缺口对应结算资产保险基金增加二十的补款命令
                var topUp = new com.surprising.aeron.protocol.AdjustInsuranceFundCommand(variant.settleAsset(), 20);
                LedgerControlTestSupport.await(runtime, RuntimeInsuranceFundStateTransitions.prepare(runtime, identities, topUp));
                LedgerControlTestSupport.await(cold, RuntimeInsuranceFundStateTransitions.prepare(cold, coldIdentities, topUp));
                LedgerControlTestSupport.commit(runtime, identities, true); cold.clearChangedKeys();
                work = RuntimeLifecycleSettlementContinuation.prepare(work, command, List.of(USER_ID), index, runtime, identities);
                LedgerControlTestSupport.await(runtime, work::poll);
                assertThat(work.result().complete()).isTrue();
                RuntimeLifecycleSettlementFixture.applyRuntime(command, List.of(USER_ID), index, cold, coldIdentities);
                assertThat(LedgerControlTestSupport.commit(runtime, identities, false)).isEqualTo(RuntimeStateMaterializer.materialize(cold, coldIdentities));
                assertThat(runtime.treasuryForQuery(identities).insuranceBalances()).isEmpty();
            } finally { runtime.exitAsynchronousCommandScope(); }
        }
    }

    /**
     * 返回四种交割全仓/逐仓组合和六种看涨看跌实平虚值组合，供原账本到期测试使用。
     */
    static java.util.stream.Stream<Variant> ledgerExpiryVariants() { return VARIANTS.stream(); }

    /** 手工列出的四种交割多空收益与期末余额表，供生产结算结果对比。 */
    private static final Map<String, DeliveryExpectation> DELIVERY_EXPECTATIONS = Map.of(
            "LINEAR_DELIVERY:CROSS", new DeliveryExpectation(40, -40, 2_040, 1_960),
            "LINEAR_DELIVERY:ISOLATED", new DeliveryExpectation(40, -40, 2_040, 1_960),
            "INVERSE_DELIVERY:CROSS", new DeliveryExpectation(33, -33, 2_033, 1_967),
            "INVERSE_DELIVERY:ISOLATED", new DeliveryExpectation(33, -33, 2_033, 1_967));

    /**
     * 执行并收集全部十行资金场景，核对固定清单无遗漏、额外项或重复；名称中的 failingFirst 不表示本用例预期失败。
     */
    @Test
    void failingFirstCompletenessManifestReportsMissingDeliveryAndOptionRows() {
        // 本次生成的完整资金矩阵行，用于逐行金额与场景完整性断言
        List<Row> rows = allRows();
        // 按矩阵键统计出现次数的有序表，用于找遗漏、额外项和重复项
        Map<String, Integer> counts = new TreeMap<>();
        rows.forEach(/* 当前统计键出现次数或核对资金金额的一行 */ row -> counts.merge(row.key(), 1, Integer::sum));
        // 实际生成矩阵行出现的固定键集合
        Set<String> actual = counts.keySet();
        // 要求存在但实际没有生成的矩阵键集合
        Set<String> missing = new LinkedHashSet<>(REQUIRED_ROWS);
        missing.removeAll(actual);
        // 实际生成但不在要求清单中的矩阵键集合
        Set<String> unexpected = new LinkedHashSet<>(actual);
        unexpected.removeAll(REQUIRED_ROWS);
        // 出现超过一次的矩阵键集合
        Set<String> duplicate = new LinkedHashSet<>();
        counts.forEach((/* 当前统计或筛选的矩阵场景键 */ key, /* 当前场景键实际出现次数，超过一表示重复 */ count) -> {
            if (count > 1) duplicate.add(key);
        });

        assertThat(missing).as("missing delivery/option financial matrix rows").isEmpty();
        assertThat(unexpected).as("unexpected delivery/option financial matrix rows").isEmpty();
        assertThat(duplicate).as("duplicate delivery/option financial matrix rows").isEmpty();
        assertThat(rows).hasSize(REQUIRED_ROWS.size());
    }

    /**
     * 逐行核对资金值与差额，再确认四交割加六期权实平虚值组合全部出现。
     */
    @Test
    void coversDeliveryAndOptionMoneyness() {
        // 本次生成的完整资金矩阵行，用于逐行金额与场景完整性断言
        List<Row> rows = allRows();
        assertRows(rows);
        assertThat(rows).extracting(Row::key).containsExactlyInAnyOrderElementsOf(REQUIRED_ROWS);
    }

    /**
     * 使用本测试独立 BigDecimal 公式，确认反向交割两份多空在一百入场、一百二十交割时分别支付正负三十三；本例只直接断言该公式的两组输入。
     */
    @Test
    void inverseDeliveryRoundingOracleUsesIndependentSignedHalfUpFormula() {
        assertThat(independentInverseDeliveryPayout(QUANTITY, ENTRY_PRICE, 120)).isEqualTo(33);
        assertThat(independentInverseDeliveryPayout(-QUANTITY, ENTRY_PRICE, 120)).isEqualTo(-33);
    }

    /**
     * 期权按核心推导收益而非调用方现金值结算；同结算 ID 完成后即使换价仍返回原状态。分段处理中重交旧用户游标须拒绝，交割运行时注册期权也须拒绝，两种拒绝都保持原哈希。
     */
    @Test
    void rejectsUntrustedCashDuplicateMutationAndWrongLine() {
        // 本例看涨实值、全仓期权组合
        Variant option = VARIANTS.get(4);
        // 期权开仓显式成交后的冷状态，保留买卖方持仓与权利金转移
        TradingCoreState matched = matchedOption(option);
        // 本次到期结算完成后的冷状态，供持仓释放和资金守恒断言
        TradingCoreState settled = reducer.settleInstrument(matched,
                new SettleInstrumentCommand(401, option.instrumentId(), option.settlementPriceTicks()));

        assertThat(settled.user(USER_ID).totalUnits(option.settleAsset())).isEqualTo(WALLET - 20 + 40);
        assertThat(settled.user(MAKER_ID).totalUnits(option.settleAsset())).isEqualTo(WALLET + 20 - 40);
        assertThat(settled.treasuryState().feeBalances()).doesNotContainKey(option.settleAsset());
        assertThat(settled.treasuryState().lifecycleSettlements()).containsEntry(option.instrumentId(), 401L);
        assertThat(reducer.settleInstrument(settled,
                new SettleInstrumentCommand(401, option.instrumentId(), 1))).isEqualTo(settled);

        // 本场景选定的正向或反向交割组合
        Variant delivery = VARIANTS.get(2);
        // 双方对向持仓、尚未开始分段交割的冷状态
        TradingCoreState cursorState = oppositePositions(delivery, WALLET, WALLET);
        // 从用户游标零开始、最多处理一个用户的首段结算命令
        SettleInstrumentCommand firstCommand = new SettleInstrumentCommand(402, delivery.instrumentId(), delivery.settlementPriceTicks(), 0, 1);
        // 首段返回的状态与结算进度，正常分段场景尚未整体完成
        RuntimeTestStateTransitions.SettlementApplication first = reducer.settleInstrumentWithProgress(cursorState,
                firstCommand, List.of(USER_ID, MAKER_ID));
        // 预期拒绝前的业务哈希，之后必须保持原值
        long hash = first.state().businessStateHash();
        assertThatThrownBy(() -> reducer.settleInstrumentWithProgress(first.state(), firstCommand,
                List.of(USER_ID, MAKER_ID)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定业务代码核对原因 */ exception -> assertThat(exception.code()).isEqualTo("INVALID_COMMAND"));
        assertThat(first.state().businessStateHash()).isEqualTo(hash);

        // 故意用来注册期权标的的空正向交割运行时状态
        TradingCoreState wrongLine = TradingCoreState.empty(ProductLine.LINEAR_DELIVERY);
        // 跨线注册前的业务哈希，拒绝后不能变化
        long wrongLineHash = wrongLine.businessStateHash();
        assertThatThrownBy(() -> com.surprising.aeron.service.product.ProductTradingRulesRegistry
                .forProductLine(wrongLine.productLine())
                .requireInstrument(CoreInstrument.from(ProductLine.OPTION, instrument(VARIANTS.get(4)))))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定业务代码核对原因 */ exception -> assertThat(exception.code()).isEqualTo("PRODUCT_LINE_UNSUPPORTED"));
        assertThat(wrongLine.businessStateHash()).isEqualTo(wrongLineHash);
    }

    /**
     * 反向全仓交割和看涨实值期权分别每页结一个用户，首段快照往返保留用户游标，第二段继续完成并清进度，持仓与锁定归零。
     */
    @Test
    void snapshotCursorResumeCompletesInverseAndOptionWithDerivedCash() {
        for (Variant /* 本次执行的交割或期权测试组合 */ variant : List.of(VARIANTS.get(2), VARIANTS.get(4))) {
            // 本场景依配置、注资、持仓、挂单或结算顺序推进的冷状态
            TradingCoreState state = variant.type().isOption()
                    ? matchedOption(variant) : oppositePositions(variant, WALLET, WALLET);
            // 当前恢复续步场景的固定结算业务 ID，两段保持同一身份
            long settlementId = variant.type().isOption() ? 403 : 404;
            // 从用户游标零开始、最多处理一个用户的首段结算命令
            SettleInstrumentCommand firstCommand = new SettleInstrumentCommand(settlementId, variant.instrumentId(),
                    variant.settlementPriceTicks(), 0, 1);

            // 首段返回的状态与结算进度，正常分段场景尚未整体完成
            RuntimeTestStateTransitions.SettlementApplication first = reducer.settleInstrumentWithProgress(state,
                    firstCommand, List.of(USER_ID, MAKER_ID));

            assertThat(first.progress().complete()).isFalse();
            assertThat(first.progress().nextCursorUserId()).isEqualTo(USER_ID);
            // 业务中间状态或候选查询状态快照往返后的冷状态
            TradingCoreState restored = TradingStateSnapshotCodec.decode(
                    TradingStateSnapshotCodec.encode(first.state()), variant.productLine());
            assertThat(restored).isEqualTo(first.state());

            // 从保存用户游标继续处理下一用户的结算结果，应整体完成
            RuntimeTestStateTransitions.SettlementApplication second = reducer.settleInstrumentWithProgress(restored,
                    new SettleInstrumentCommand(settlementId, variant.instrumentId(), variant.settlementPriceTicks(), USER_ID, 1),
                    List.of(USER_ID, MAKER_ID));

            assertThat(second.progress().complete()).isTrue();
            assertThat(second.state().treasuryState().lifecycleProgress(variant.instrumentId())).isNull();
            assertFlatAndReleased(second.state(), variant);
        }
    }

    /**
     * 正反向逐仓交割各处理一个用户后快照往返，从保存游标接续第二用户，确认完成、清进度和释放持仓保证金。
     */
    @Test
    void snapshotCursorResumeCompletesLinearAndInverseIsolatedDelivery() {
        for (Variant /* 本次执行的交割或期权测试组合 */ variant : List.of(VARIANTS.get(1), VARIANTS.get(3))) {
            // 本场景依配置、注资、持仓、挂单或结算顺序推进的冷状态
            TradingCoreState state = oppositePositions(variant, WALLET, WALLET);
            // 当前恢复续步场景的固定结算业务 ID，两段保持同一身份
            long settlementId = variant.type() == ContractType.LINEAR_DELIVERY ? 405 : 406;
            // 从用户游标零开始、最多处理一个用户的首段结算命令
            SettleInstrumentCommand firstCommand = new SettleInstrumentCommand(settlementId, variant.instrumentId(),
                    variant.settlementPriceTicks(), 0, 1);

            // 首段返回的状态与结算进度，正常分段场景尚未整体完成
            RuntimeTestStateTransitions.SettlementApplication first = reducer.settleInstrumentWithProgress(state,
                    firstCommand, List.of(USER_ID, MAKER_ID));

            assertThat(first.progress().complete()).isFalse();
            assertThat(first.progress().nextCursorUserId()).isEqualTo(USER_ID);
            // 业务中间状态或候选查询状态快照往返后的冷状态
            TradingCoreState restored = TradingStateSnapshotCodec.decode(
                    TradingStateSnapshotCodec.encode(first.state()), variant.productLine());
            assertThat(restored).isEqualTo(first.state());

            // 从保存用户游标继续处理下一用户的结算结果，应整体完成
            RuntimeTestStateTransitions.SettlementApplication second = reducer.settleInstrumentWithProgress(restored,
                    new SettleInstrumentCommand(settlementId, variant.instrumentId(), variant.settlementPriceTicks(), USER_ID, 1),
                    List.of(USER_ID, MAKER_ID));

            assertThat(second.progress().complete()).isTrue();
            assertThat(second.state().treasuryState().lifecycleProgress(variant.instrumentId())).isNull();
            assertFlatAndReleased(second.state(), variant);
        }
    }

    /**
     * 同账户同时保留全仓持仓、挂单预留及另一逐仓亏损；保险不足先暂停且余额持仓不动，补二十后只结逐仓，保留全仓抵押、挂单及自由余额，并核对包含外部注资的总额。
     */
    @Test
    void settlingIsolatedLossKeepsUnrelatedCrossStateAndReservationIntact() {
        // 用于保留其他业务事实的正向交割全仓组合
        Variant cross = VARIANTS.get(0);
        // 本场景需要单独隔离保证金的逐仓交割或期权组合
        Variant isolated = VARIANTS.get(1);
        // 需要保留全仓持仓和挂单的独立标的八
        String crossSymbol = "8";
        // 需要执行逐仓亏损结算的独立标的九
        String isolatedSymbol = "9";
        // 本场景依配置、注资、持仓、挂单或结算顺序推进的冷状态
        TradingCoreState state = TradingCoreState.empty(ProductLine.LINEAR_DELIVERY);
        state = reducer.registerInstrument(state, instrument(cross, crossSymbol));
        state = reducer.registerInstrument(state, instrument(isolated, isolatedSymbol));
        state = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand(crossSymbol, ENTRY_PRICE, 1, 1_700_000_000_000L));
        state = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand(isolatedSymbol, ENTRY_PRICE, 1, 1_700_000_000_000L));
        state = reducer.adjustBalance(state, USER_ID, new BalanceAdjustmentCommand("USDT", 200));
        state = addPosition(state, USER_ID, crossSymbol, 1, 100, 40, cross);
        state = addPosition(state, USER_ID, isolatedSymbol, 1, 140, 20, isolated);
        state = reducer.placeOrder(state, USER_ID, deliveryOrder(701, cross, crossSymbol));

        // 逐仓结算前的用户值，供余额、全仓持仓及预留不变断言
        CoreUserState beforeUser = state.user(USER_ID);
        // 逐仓结算前的 USDT 余额，包含自由资金和各类锁定
        AssetBalance beforeBalance = beforeUser.balances().get("USDT");
        // 逐仓结算前保留的另一个全仓持仓，不得被改写
        CorePositionState beforeCrossPosition = beforeUser.positions().get(crossSymbol);
        // 结算前保存的未成交订单预留，供隔离或释放断言
        OrderReservation beforeReservation = beforeUser.reservations().get(701L);
        // 用户总额扣除目标逐仓保证金后的全仓可用抵押口径，应保持一百八十
        long beforeCrossCollateral = beforeBalance.totalUnits()
                - beforeUser.positions().get(isolatedSymbol).positionMarginUnits();
        assertThat(beforeBalance.availableUnits()).isEqualTo(130);
        assertThat(beforeBalance.lockedUnits()).isEqualTo(70);
        assertThat(beforeReservation.reservedUnits()).isEqualTo(10);
        assertThat(beforeReservation.remainingUnits()).isEqualTo(10);
        assertThat(beforeCrossCollateral).isEqualTo(180);
        // 外部补保险或结算前的用户与财库总额基线
        long beforeTotal = total(state, "USDT");

        // 只结逐仓标的九、价格一百的结算四百零七命令
        var command = new SettleInstrumentCommand(407, isolatedSymbol, 100);
        // 保险不足时结束本段但保持目标用户未结算的状态
        TradingCoreState paused = reducer.settleInstrument(state, command);
        assertThat(paused.user(USER_ID).balances()).isEqualTo(beforeUser.balances());
        assertThat(paused.user(USER_ID).positions()).isEqualTo(beforeUser.positions());
        assertThat(paused.treasuryState().lifecycleProgress(isolatedSymbol).requiredInsuranceUnits()).isEqualTo(20);
        state = reducer.adjustInsuranceFund(paused, new com.surprising.aeron.protocol.AdjustInsuranceFundCommand("USDT", 20));
        // 本次到期结算完成后的冷状态，供持仓释放和资金守恒断言
        TradingCoreState settled = reducer.settleInstrument(state, command);

        // 保险补足并结算后用户值，核对其他持仓和挂单保留
        CoreUserState afterUser = settled.user(USER_ID);
        // 逐仓亏损结算后 USDT 余额，自由资金应保持原值
        AssetBalance afterBalance = afterUser.balances().get("USDT");
        assertThat(afterUser.positions().get(crossSymbol)).isEqualTo(beforeCrossPosition);
        assertThat(afterUser.reservations().get(701L)).isEqualTo(beforeReservation);
        assertThat(settled.order(701).status()).isEqualTo(CoreOrderStatus.OPEN);
        assertThat(afterBalance.availableUnits()).isEqualTo(beforeBalance.availableUnits());
        assertThat(afterBalance.totalUnits() - afterUser.positions().get(isolatedSymbol).positionMarginUnits())
                .isEqualTo(beforeCrossCollateral);
        assertThat(afterBalance.lockedUnits()).isEqualTo(50);
        assertThat(afterUser.positions().get(isolatedSymbol).signedQuantitySteps()).isZero();
        assertThat(afterUser.positions().get(isolatedSymbol).realizedPnlUnits()).isEqualTo(-40);
        assertThat(settled.treasuryState().insuranceBalances()).doesNotContainKey("USDT");
        assertThat(settled.treasuryState().clearingPnlBalances()).containsEntry("USDT", 40L);
        assertThat(total(settled, "USDT")).isEqualTo(beforeTotal + 20);
    }

    /**
     * 手工建立同标的双向全仓多空，分别用低于或高于结算价的入场价，使一边盈利一边亏损；应先合计净损益再校验偿付，最终总资金二十且全部解锁。
     */
    @Test
    void netsCrossHedgePnlBeforeTestingSolvency() {
        for (long /* 选择入场一百或三百，使多空损益方向互换但净值抵消 */ direction : new long[]{1, -1}) {
            // 本次执行的交割或期权测试组合
            Variant variant = VARIANTS.getFirst();
            // 本场景依配置、注资、持仓、挂单或结算顺序推进的冷状态
            TradingCoreState state = fundedState(variant, USER_ID, 20);
            // 本例手工建立双向持仓的默认交割标的
            String instrumentId = variant.instrumentId();
            // 手工建立的一份全仓 LONG 持仓，锁定保证金十
            var longPosition = new CorePositionState(instrumentId, "USDT", CoreMarginMode.CROSS,
                    CorePositionSide.LONG, 1, direction > 0 ? 100 : 300, direction > 0 ? 100 : 300, 0, 10);
            // 手工建立的一份全仓 SHORT 空头，和多头损益相抵，保证金十
            var shortPosition = new CorePositionState(instrumentId, "USDT", CoreMarginMode.CROSS,
                    CorePositionSide.SHORT, -1, direction > 0 ? 100 : 300, direction > 0 ? 100 : 300, 0, 10);
            // 当前需要构造或检查持仓、锁定或合计资金的用户值
            var user = new CoreUserState(state.productLine(), USER_ID, 2,
                    Map.of("USDT", new AssetBalance("USDT", 0, 20)), Map.of(),
                    Map.of(longPosition.key(), longPosition, shortPosition.key(), shortPosition),
                    com.surprising.aeron.protocol.CorePositionMode.HEDGE);
            state = new TradingCoreState(state.productLine(), 3, Map.of(USER_ID, user), Map.of(),
                    state.instruments(), state.riskState(), state.treasuryState());
            // 本次到期结算完成后的冷状态，供持仓释放和资金守恒断言
            var settled = reducer.settleInstrument(state, new SettleInstrumentCommand(900, instrumentId, 200));
            assertThat(settled.user(USER_ID).totalUnits("USDT")).isEqualTo(20);
            assertThat(settled.user(USER_ID).balances().get("USDT").lockedUnits()).isZero();
            assertThat(settled.treasuryState().lifecycleSettlement(instrumentId)).isEqualTo(900);
            assertThat(settled.treasuryState().clearingPnlBalances()).isEmpty();
        }
    }

    /**
     * 交割到期时取消仍挂着的买单，保留原预留总额但把剩余全部记为释放，用户锁定清零、总资金不变。
     */
    @Test
    void cancelsOpenDeliveryOrderAndReleasesOwnerReservation() {
        // 本场景选定的正向或反向交割组合
        Variant delivery = VARIANTS.get(0);
        // 本场景依配置、注资、持仓、挂单或结算顺序推进的冷状态
        TradingCoreState state = fundedState(delivery, USER_ID, WALLET);
        state = reducer.placeOrder(state, USER_ID, deliveryOrder(702, delivery, delivery.instrumentId()));
        // 结算前保存的未成交订单预留，供隔离或释放断言
        OrderReservation beforeReservation = state.user(USER_ID).reservations().get(702L);
        // 外部补保险或结算前的用户与财库总额基线
        long beforeTotal = total(state, delivery.settleAsset());

        // 本次到期结算完成后的冷状态，供持仓释放和资金守恒断言
        TradingCoreState settled = reducer.settleInstrument(state,
                new SettleInstrumentCommand(411, delivery.instrumentId(), delivery.settlementPriceTicks()));

        // 到期撤单后的预留，原总量不变、剩余零、全部记为已释放
        OrderReservation afterReservation = settled.user(USER_ID).reservations().get(702L);
        assertThat(settled.order(702).status()).isEqualTo(CoreOrderStatus.CANCELED);
        assertThat(afterReservation.reservedUnits()).isEqualTo(beforeReservation.reservedUnits());
        assertThat(afterReservation.remainingUnits()).isZero();
        assertThat(afterReservation.releasedUnits()).isEqualTo(beforeReservation.reservedUnits());
        assertThat(settled.user(USER_ID).balances().get(delivery.settleAsset()).lockedUnits()).isZero();
        assertThat(total(settled, delivery.settleAsset())).isEqualTo(beforeTotal);
    }

    /**
     * 分别用正向交割、反向交割和期权调用资金费，确认不支持并保持原对象、哈希及结算记录不变。
     */
    @Test
    void rejectsFundingForDeliveryAndOptionWithoutStateMutation() {
        assertFundingRejected(VARIANTS.get(0), 408);
        assertFundingRejected(VARIANTS.get(2), 409);
        assertFundingRejected(VARIANTS.get(4), 410);
    }

    /**
     * 未成交期权卖单在结算前取消并释放卖方预留，不创建持仓，双方总资金保持不变。
     */
    @Test
    void cancelsOpenOptionOrderBeforeSettlementAndReleasesReservation() {
        // 本例看涨实值、全仓期权组合
        Variant option = VARIANTS.get(4);
        // 本场景依配置、注资、持仓、挂单或结算顺序推进的冷状态
        TradingCoreState state = fundedState(option, USER_ID, WALLET);
        state = fundedState(state, MAKER_ID, WALLET);
        state = reducer.placeOrder(state, MAKER_ID, optionOrder(501, option, CoreOrderSide.SELL));
        // 未成交期权卖单结算前的系统现金总额基线
        long before = total(state, option.settleAsset());

        // 本次到期结算完成后的冷状态，供持仓释放和资金守恒断言
        TradingCoreState settled = reducer.settleInstrument(state,
                new SettleInstrumentCommand(501, option.instrumentId(), option.settlementPriceTicks()));

        assertThat(settled.order(501).status()).isEqualTo(CoreOrderStatus.CANCELED);
        assertThat(settled.user(MAKER_ID).balances().get(option.settleAsset()).lockedUnits()).isZero();
        assertThat(settled.user(MAKER_ID).positions()).isEmpty();
        assertThat(total(settled, option.settleAsset())).isEqualTo(before);
    }

    /** 复用现有业务转换的冷状态辅助，不代表独立金融公式实现。 */
    private final RuntimeTestStateTransitions reducer = new RuntimeTestStateTransitions();

    /**
     * 四种交割组合在价格上涨后都只把盈利多头列为 ADL 候选，核对正反向收益；快照恢复后的冷查询和运行时索引查询保留同一候选结果及总资金。
     */
    @Test
    void deliveryAdlCandidatesIncludeProfitablePositionsAcrossMarginModesAndRestore() {
        for (Variant /* 本次执行的交割或期权测试组合 */ variant : VARIANTS.subList(0, 4)) {
            // 本场景依配置、注资、持仓、挂单或结算顺序推进的冷状态
            TradingCoreState state = oppositePositions(variant, WALLET, WALLET);
            state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand(
                    variant.instrumentId(), 120, 2, 1_700_000_000_001L));
            // 本场景查询出的盈利 ADL 候选，预期只有主用户多头
            var candidates = reducer.adlCandidates(state, variant.settleAsset(), 10);
            assertThat(candidates).as(variant.key()).hasSize(1);
            assertThat(candidates.getFirst().userId()).isEqualTo(USER_ID);
            assertThat(candidates.getFirst().unrealizedProfitUnits())
                    .isEqualTo(variant.type().isInverse() ? 33 : 40);
            // 业务中间状态或候选查询状态快照往返后的冷状态
            TradingCoreState restored = TradingStateSnapshotCodec.decode(
                    TradingStateSnapshotCodec.encode(state), variant.productLine());
            assertThat(reducer.adlCandidates(restored, variant.settleAsset(), 10))
                    .containsExactlyElementsOf(candidates);
            assertThat(total(restored, variant.settleAsset())).isEqualTo(2 * WALLET);
        }
    }

    /**
     * 同用户另一标的期权空头使账户不安全时，非组合保证金多头仍受保护；对多头手工放入旧强平计划也不能绕过检查，执行后计划取消、多头保留。
     */
    @Test
    void nonPortfolioOptionLongIsNotLiquidatedWhenAnotherShortMakesAccountUnsafe() {
        // 本例看涨实值、全仓期权组合
        Variant option = VARIANTS.get(4);
        // 另一个期权标的九千零一，用不安全空头验证多头保护
        String shortSymbol = "9001";
        // 本场景准备好的原冷状态，后续结算或风险操作由此开始
        TradingCoreState opening = fundedState(option, USER_ID, 100);
        opening = reducer.registerInstrument(opening, instrument(option, shortSymbol));
        opening = addPosition(opening, USER_ID, option.instrumentId(), 1, 10, 0, option);
        opening = addPosition(opening, USER_ID, shortSymbol, -10, 10, 0, option);
        // 本场景用于重新估值期权市值负债及风险的标记价命令
        ApplyMarkPriceCommand mark = new ApplyMarkPriceCommand(shortSymbol, 100, 100, 100, 1,
                1_700_000_000_001L);
        // 标记价及风险扫描完成后的冷状态，供快照与运行时对照
        TradingCoreState marked = reducer.applyMarkPrice(opening, mark);
        assertThat(marked.riskState().liquidations().values())
                .extracting(CoreLiquidationState::instrumentId).containsExactly(shortSymbol);
        assertThat(marked.user(USER_ID).positions().get(option.instrumentId()).signedQuantitySteps()).isEqualTo(1);
        assertThat(total(marked, option.settleAsset())).isEqualTo(100);
        // 本次投影、业务辅助和物化共用的独立整数身份表
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 本次直接业务或异步控制使用的运行时，按既有资源作用域或 finally 关闭
        TradingAccountRuntime runtime = RuntimeDerivativeRiskFixture.simulateMarkPrice(
                opening, mark, identities);
        try {
            RuntimeStateParityChecker.assertMatches(marked, identities, runtime);
            assertThat(TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(marked),
                    ProductLine.OPTION)).isEqualTo(marked);
            // 即使已经存在排队强平计划，也不能绕过非组合保证金期权多头保护。
            runtime.putLiquidation(new LiquidationRuntime(99, USER_ID,
                    identities.symbolId(option.instrumentId()), CoreMarginMode.CROSS, CorePositionSide.NET,
                    runtime.instrument(option.instrumentId()), 1, 1, 1, 0, 0, 0, 0,
                    CoreLiquidationState.Status.PLANNED, 0));
            // 对手工排入的受保护期权多头计划发起的强平执行命令
            var execution = new com.surprising.aeron.protocol.ExecuteLiquidationCommand(
                    99, 1, PREMIUM_PRICE, 0);
            RuntimeDerivativeLiquidationFixture.applyExecutionRuntime(execution, List.of(), runtime, identities);
            assertThat(runtime.liquidation(99).status()).isEqualTo(CoreLiquidationState.Status.CANCELED);
            assertThat(runtime.position(identities.positionKey(USER_ID, option.instrumentId()))
                    .signedQuantitySteps()).isEqualTo(1);
        } finally {
            runtime.close();
        }
    }

    /**
     * 逐仓期权空头在标记价三十时，保证金四十减完整市值负债六十后权益为负二十，浮亏为负四十并进入强平；冷路径与运行时风险结果一致。
     */
    @Test
    void isolatedOptionShortEquityUsesFullMarkLiabilityInBothStateModels() {
        // 原看涨实值全仓组合，复制参数后改成逐仓来单独检查卖方权益
        Variant call = VARIANTS.get(4);
        // 本场景需要单独隔离保证金的逐仓交割或期权组合
        Variant isolated = new Variant(call.type(), CoreMarginMode.ISOLATED, call.optionType(), call.moneyness(),
                call.settlementPriceTicks(), call.notionalMultiplierUnits(), call.settleScaleUnits(),
                call.baseAsset(), call.quoteAsset(), call.settleAsset());
        // 本场景准备好的原冷状态，后续结算或风险操作由此开始
        TradingCoreState opening = fundedState(isolated, MAKER_ID, WALLET);
        opening = addPosition(opening, MAKER_ID, isolated.instrumentId(), -QUANTITY, PREMIUM_PRICE, 40, isolated);
        // 本场景用于重新估值期权市值负债及风险的标记价命令
        ApplyMarkPriceCommand mark = new ApplyMarkPriceCommand(isolated.instrumentId(), 30,
                100, 100, 2, 1_700_000_000_001L);

        // 标记价及风险扫描完成后的冷状态，供快照与运行时对照
        TradingCoreState marked = reducer.applyMarkPrice(opening, mark);
        // 逐仓期权空头的风险快照，区分完整市值负债和单纯浮亏
        CoreRiskSnapshot risk = marked.riskState().snapshots().get(MAKER_ID + ":" + isolated.instrumentId());
        assertThat(risk.equityUnits()).isEqualTo(-20);
        assertThat(risk.unrealizedPnlUnits()).isEqualTo(-40);
        assertThat(risk.status()).isEqualTo(CoreRiskStatus.LIQUIDATION);
        // 固定给运行时操作闭包捕获的标记前冷状态
        TradingCoreState beforeMark = opening;
        assertRuntimeParity(beforeMark, marked,
                /* 本次投影、业务辅助和物化共用的独立整数身份表 */ identities -> RuntimeDerivativeRiskFixture.simulateMarkPrice(
                        beforeMark, mark, identities));
    }

    /**
     * 期权卖方在标记价三千时被强平并形成亏空，保险部分覆盖后对盈利买方减一份补剩余亏空；各步骤对照运行时辅助，最终总现金四千、强平完成，完整结果可快照往返。
     */
    @Test
    void optionShortLiquidationAndAdlCloseAtMarkAndConserveCashAcrossRuntimeRestore() {
        // 本例看涨实值、全仓期权组合
        Variant option = VARIANTS.get(4);
        // 期权开仓显式成交后的冷状态，保留买卖方持仓与权利金转移
        TradingCoreState matched = matchedOption(option);
        // 把期权标记价提高到三千的冲击价格命令，指数与远期仍为一百
        ApplyMarkPriceCommand shock = new ApplyMarkPriceCommand(option.instrumentId(), 3_000,
                100, 100, 2, 1_700_000_000_001L);
        // 标记价及风险扫描完成后的冷状态，供快照与运行时对照
        TradingCoreState marked = reducer.applyMarkPrice(matched, shock);
        // 价格冲击后给期权卖方生成的强平计划
        CoreLiquidationState plan = marked.riskState().liquidations().values().iterator().next();
        assertThat(plan.userId()).isEqualTo(MAKER_ID);

        // 按计划身份和价格序列二在三千执行的强平命令
        ExecuteLiquidationCommand liquidation = new ExecuteLiquidationCommand(
                plan.liquidationId(), 2, 3_000, 0);
        // 卖方持仓已归零且记录三千九百八十亏空的冷状态
        TradingCoreState liquidated = reducer.executeLiquidation(marked, liquidation);
        assertThat(liquidated.user(MAKER_ID).positions().get(option.instrumentId()).signedQuantitySteps()).isZero();
        assertThat(liquidated.riskState().liquidations().get(plan.liquidationId()).deficitUnits())
                .isEqualTo(3_980);
        assertThat(total(liquidated, option.settleAsset())).isEqualTo(2 * WALLET);
        assertRuntimeParity(marked, liquidated,
                /* 本次投影、业务辅助和物化共用的独立整数身份表 */ identities -> RuntimeDerivativeLiquidationFixture.simulateExecution(marked, liquidation, identities));

        // 确认保险覆盖二千零二十、剩余继续 ADL 的结果命令
        ResolveLiquidationCommand insurance = new ResolveLiquidationCommand(plan.liquidationId(),
                ResolveLiquidationCommand.Resolution.INSURANCE, 2_020);
        // 保险确认后仍有一千九百六十待补亏空的冷状态
        TradingCoreState insured = reducer.resolveLiquidation(liquidated, insurance);
        assertThat(insured.riskState().liquidations().get(plan.liquidationId()).status())
                .isEqualTo(CoreLiquidationState.Status.ADL_REQUIRED);
        assertRuntimeParity(liquidated, insured,
                /* 本次投影、业务辅助和物化共用的独立整数身份表 */ identities -> RuntimeDerivativeLiquidationFixture.simulateResolution(
                        liquidated, insurance, identities));

        // 校验买方原持仓两份和入场价十，在标记序列二减一份并补亏一千九百六十的命令
        ExecuteAdlCommand adl = new ExecuteAdlCommand(plan.liquidationId(), USER_ID, option.instrumentId(),
                CoreMarginMode.CROSS, CorePositionSide.NET, QUANTITY, PREMIUM_PRICE, 2, 1, 1_960);
        assertThat(reducer.adlCandidates(insured, option.settleAsset(), 10))
                .extracting(com.surprising.aeron.protocol.CoreAdlCandidateView::userId)
                .containsExactly(USER_ID);
        // ADL 补足亏空后的状态，买方仍剩一份、总现金保持四千
        TradingCoreState completed = reducer.executeAdl(insured, adl);
        assertThat(completed.user(USER_ID).positions().get(option.instrumentId()).signedQuantitySteps()).isEqualTo(1);
        assertThat(completed.user(USER_ID).totalUnits(option.settleAsset())).isEqualTo(3_020);
        assertThat(completed.riskState().liquidations().get(plan.liquidationId()).status())
                .isEqualTo(CoreLiquidationState.Status.COMPLETED);
        assertThat(total(completed, option.settleAsset())).isEqualTo(2 * WALLET);
        assertRuntimeParity(insured, completed,
                /* 本次投影、业务辅助和物化共用的独立整数身份表 */ identities -> RuntimeDerivativeLiquidationFixture.simulateAdl(insured, adl, identities));
        assertThat(TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(completed), ProductLine.OPTION))
                .isEqualTo(completed);
    }

    /**
     * 期权开仓双方先各冻结二十，成交后卖方持仓保证金四十；双方只减仓平仓单各只预留一，成交后仓位归零并恢复原现金。开平两次显式成交都和运行时结算结果比较。
     */
    @Test
    void optionSellOpenAndBuyCloseReserveNetCashAndRuntimeMatchKeepsParity() {
        // 本例看涨实值、全仓期权组合
        Variant option = VARIANTS.get(4);
        // 本场景依配置、注资、持仓、挂单或结算顺序推进的冷状态
        TradingCoreState state = optionFundingState(option);
        state = reducer.placeOrder(state, MAKER_ID, optionOrder(301, option, CoreOrderSide.SELL));
        assertThat(state.user(MAKER_ID).reservations().get(301L).remainingUnits()).isEqualTo(20);
        state = reducer.placeOrder(state, USER_ID, optionOrder(302, option, CoreOrderSide.BUY));
        assertThat(state.user(USER_ID).reservations().get(302L).remainingUnits()).isEqualTo(20);

        // 买卖双方开仓意图已接受、显式成交尚未应用的冷状态
        TradingCoreState beforeMatch = state;
        // 本次测试显式提供的开仓成交事件列表，不是 Matcher 线程结果
        List<MatcherEvent> matches =
                List.of(trade(301, MAKER_ID, PREMIUM_PRICE, QUANTITY, true, true));
        // 期权开仓显式成交后的冷状态，保留买卖方持仓与权利金转移
        TradingCoreState matched = reducer.applyMatches(
                beforeMatch, 302, option.baseAsset(), option.quoteAsset(), matches);
        // 开仓成交后的卖方期权空头，用来检查保证金四十
        CorePositionState shortPosition = matched.user(MAKER_ID).positions().get(option.instrumentId());
        assertThat(shortPosition.positionMarginUnits()).isEqualTo(40);
        assertThat(matched.user(MAKER_ID).balances().get(option.settleAsset()).availableUnits()).isEqualTo(1_980);
        assertThat(matched.user(MAKER_ID).balances().get(option.settleAsset()).lockedUnits()).isEqualTo(40);

        // 本次投影、业务辅助和物化共用的独立整数身份表
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (/* 本次直接业务或异步控制使用的运行时，按既有资源作用域或 finally 关闭 */ TradingAccountRuntime runtime = RuntimeDerivativeMatchFixture.simulate(
                beforeMatch, 302, matches, identities)) {
            RuntimeStateParityChecker.assertMatches(matched, identities, runtime);
        }

        // 卖方平空的只减仓买单，数量二、价格十
        PlaceOrderCommand buyClose = new PlaceOrderCommand(303, option.instrumentId(),
                CoreOrderSide.BUY, PREMIUM_PRICE, QUANTITY, true, option.marginMode(),
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "");
        // 双方只减仓平仓单陆续接受、尚未成交的状态
        TradingCoreState closing = reducer.placeOrder(matched, MAKER_ID, buyClose);
        assertThat(closing.user(MAKER_ID).reservations().get(303L).remainingUnits()).isOne();
        // 买方平多的只减仓卖单，数量二、价格十
        PlaceOrderCommand sellClose = new PlaceOrderCommand(304, option.instrumentId(),
                CoreOrderSide.SELL, PREMIUM_PRICE, QUANTITY, true, option.marginMode(),
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "");
        closing = reducer.placeOrder(closing, USER_ID, sellClose);
        assertThat(closing.user(USER_ID).reservations().get(304L).remainingUnits()).isOne();
        // 本次测试显式提供的两份平仓成交事件
        List<MatcherEvent> closeMatches =
                List.of(trade(304, USER_ID, PREMIUM_PRICE, QUANTITY, true, true));
        // 平仓单均接受但尚未应用成交的冷状态
        TradingCoreState beforeClose = closing;
        // 平仓显式成交完成后的冷状态，双方数量零且现金回到默认钱包
        TradingCoreState closed = reducer.applyMatches(
                beforeClose, 303, option.baseAsset(), option.quoteAsset(), closeMatches);
        assertThat(closed.user(MAKER_ID).positions().get(option.instrumentId()).signedQuantitySteps()).isZero();
        assertThat(closed.user(USER_ID).positions().get(option.instrumentId()).signedQuantitySteps()).isZero();
        assertThat(closed.user(MAKER_ID).totalUnits(option.settleAsset())).isEqualTo(WALLET);
        assertThat(closed.user(USER_ID).totalUnits(option.settleAsset())).isEqualTo(WALLET);
        assertThat(total(closed, option.settleAsset())).isEqualTo(2 * WALLET);

        // 平仓成交对照单独投影使用的身份表
        RuntimeIdentityRegistry closeIdentities = new RuntimeIdentityRegistry();
        try (/* 本次直接业务或异步控制使用的运行时，按既有资源作用域或 finally 关闭 */ TradingAccountRuntime runtime = RuntimeDerivativeMatchFixture.simulate(
                beforeClose, 303, closeMatches, closeIdentities)) {
            RuntimeStateParityChecker.assertMatches(closed, closeIdentities, runtime);
        }
    }

    /**
     * 非组合保证金期权配置杠杆应在冷路径及直接运行时都以同一不支持代码拒绝。
     */
    @Test
    void nonPortfolioOptionRejectsLeverageConfigurationInBothStateModels() {
        // 本例看涨实值、全仓期权组合
        Variant option = VARIANTS.get(4);
        // 本场景依配置、注资、持仓、挂单或结算顺序推进的冷状态
        TradingCoreState state = fundedState(option, USER_ID, WALLET);
        // 对非组合保证金期权设置全仓一倍杠杆的故意非法命令
        UpdateLeverageCommand command = new UpdateLeverageCommand(
                option.instrumentId(), CoreMarginMode.CROSS, 1_000_000);
        assertThatThrownBy(() -> reducer.updateLeverage(state, USER_ID, command))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定业务代码核对原因 */ exception -> assertThat(exception.code()).isEqualTo("OPTION_LEVERAGE_UNSUPPORTED"));

        // 本次投影、业务辅助和物化共用的独立整数身份表
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (/* 本次直接业务或异步控制使用的运行时，按既有资源作用域或 finally 关闭 */ TradingAccountRuntime runtime = RuntimeStateProjector.project(state, identities)) {
            assertThatThrownBy(() -> LedgerControlTestSupport.await(runtime, new AccountControlTask().prepareLeverageChange(runtime, identities, USER_ID, command)))
                    .isInstanceOfSatisfying(CoreStateRejectedException.class,
                            /* 本次预期的业务拒绝异常，用稳定业务代码核对原因 */ exception -> assertThat(exception.code())
                                    .isEqualTo("OPTION_LEVERAGE_UNSUPPORTED"));
        }
    }

    /**
     * 建立独立身份表并执行调用方提供的运行时操作，再和预期冷状态完整比较，作用域结束关闭运行时；before 参数当前没有读取，原起点由闭包自行捕获。
     * @param before 保留的原冷状态参数，本辅助当前不读取；运行时操作闭包自行捕获起点
     * @param expected 本次运行时操作完成后应匹配的完整冷状态
     * @param operation 接收身份表并创建、执行业务后返回运行时的测试闭包，返回值在本辅助内关闭
     */
    private static void assertRuntimeParity(TradingCoreState before, TradingCoreState expected,
                                            java.util.function.Function<RuntimeIdentityRegistry,
                                                    TradingAccountRuntime> operation) {
        // 本次投影、业务辅助和物化共用的独立整数身份表
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (/* 本次直接业务或异步控制使用的运行时，按既有资源作用域或 finally 关闭 */ TradingAccountRuntime runtime = operation.apply(identities)) {
            RuntimeStateParityChecker.assertMatches(expected, identities, runtime);
        }
    }

    /**
     * 按十个固定组合实际执行交割或期权场景，返回已经含局部断言的矩阵行。
     */
    private List<Row> allRows() {
        return VARIANTS.stream().map(/* 本次执行的交割或期权测试组合 */ variant -> variant.type().isOption()
                ? optionRow(variant) : deliveryRow(variant)).toList();
    }

    /**
     * 建立对向持仓并交割，逐个比较手工列出的多空收益及期末余额，释放持仓和保证金后装成资金行；期望表不是调用生产收益函数生成。
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     */
    private Row deliveryRow(Variant variant) {
        // 本场景准备好的原冷状态，后续结算或风险操作由此开始
        TradingCoreState opening = oppositePositions(variant, WALLET, WALLET);
        // 按当前交割组合从手工期望表取得的多空收益和双方期末金额
        DeliveryExpectation expected = DELIVERY_EXPECTATIONS.get(variant.key());
        assertThat(expected).as(variant.key() + " independent delivery expectation").isNotNull();
        // 本行结算完成且持仓保证金已释放的冷状态
        TradingCoreState ending = reducer.settleInstrument(opening,
                new SettleInstrumentCommand(100 + variant.marginMode().ordinal(), variant.instrumentId(), variant.settlementPriceTicks()));
        assertFlatAndReleased(ending, variant);
        assertThat(userValue(ending, variant) - userValue(opening, variant))
                .as(variant.key() + " signed long payout")
                .isEqualTo(expected.longPayout());
        assertThat(userValue(ending, variant, MAKER_ID) - userValue(opening, variant, MAKER_ID))
                .as(variant.key() + " signed short payout")
                .isEqualTo(expected.shortPayout());
        assertThat(userValue(ending, variant)).as(variant.key() + " user final balance")
                .isEqualTo(expected.userEnding());
        assertThat(userValue(ending, variant, MAKER_ID)).as(variant.key() + " maker final balance")
                .isEqualTo(expected.makerEnding());
        return new Row(rowKey(variant), variant, opening, ending,
                funds(WALLET, WALLET, 0, 0, expected.longPayout(), expected.shortPayout(), 0, 0,
                        expected.userEnding(), expected.makerEnding(), 0, 0));
    }

    /**
     * 先按权利金十成交两份期权，再独立计算看涨或看跌内在价值及现金总额，核对生产单份公式，结算并释放持仓后生成包含权利金和行权收益的资金行。
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     */
    private Row optionRow(Variant variant) {
        // 本场景准备好的原冷状态，后续结算或风险操作由此开始
        TradingCoreState opening = optionFundingState(variant);
        // 期权开仓显式成交后的冷状态，保留买卖方持仓与权利金转移
        TradingCoreState matched = matchOption(opening, variant);
        // 按看涨或看跌方向独立计算的非负内在价格 tick
        long intrinsicTicks = variant.optionType() == OptionType.CALL
                ? Math.max(variant.settlementPriceTicks() - STRIKE_PRICE, 0)
                : Math.max(STRIKE_PRICE - variant.settlementPriceTicks(), 0);
        // 内在价格乘本组合名义乘数的单份期权现金期望，组合精度固定为一
        long expectedCashPerContract = Math.multiplyExact(intrinsicTicks, variant.notionalMultiplierUnits());
        assertThat(OptionContractMath.optionSettlementCashUnits(
                matched.instruments().get(variant.instrumentId()), variant.settlementPriceTicks()))
                .as(variant.key() + " intrinsic cash per contract")
                .isEqualTo(expectedCashPerContract);
        // 单份内在现金乘默认两份数量后的总行权支付期望
        long payout = Math.multiplyExact(expectedCashPerContract, QUANTITY);
        // 本行结算完成且持仓保证金已释放的冷状态
        TradingCoreState ending = reducer.settleInstrument(matched,
                new SettleInstrumentCommand(200 + variant.optionType().ordinal() * 10
                        + (variant.moneyness().equals("ITM") ? 1 : variant.moneyness().equals("ATM") ? 2 : 3), variant.instrumentId(), variant.settlementPriceTicks()));
        assertFlatAndReleased(ending, variant);
        return new Row(rowKey(variant), variant, opening, ending,
                funds(WALLET, WALLET, 0, 0,
                        Math.subtractExact(payout, 20), Math.subtractExact(20, payout), 0, 0,
                        WALLET - 20 + payout, WALLET + 20 - payout, 0, 0));
    }

    /**
     * 给双方注入默认钱包余额，再在权利金价十显式成交两份期权。
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     */
    private TradingCoreState matchedOption(Variant variant) {
        return matchOption(optionFundingState(variant), variant);
    }

    /**
     * 创建期权标的及价格后给主用户和第一做市用户分别注资默认钱包金额。
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     */
    private TradingCoreState optionFundingState(Variant variant) {
        // 本场景依配置、注资、持仓、挂单或结算顺序推进的冷状态
        TradingCoreState state = fundedState(variant, USER_ID, WALLET);
        return fundedState(state, MAKER_ID, WALLET);
    }

    /**
     * 卖方挂单、买方吃单均为两份，再显式提供一组成交事件；不调用真实 Matcher。
     * @param state 需要变更、读取或检查的当前冷状态
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     */
    private TradingCoreState matchOption(TradingCoreState state, Variant variant) {
        state = reducer.placeOrder(state, MAKER_ID, optionOrder(301, variant, CoreOrderSide.SELL));
        state = reducer.placeOrder(state, USER_ID, optionOrder(302, variant, CoreOrderSide.BUY));
        return reducer.applyMatches(state, 302, variant.baseAsset(), variant.quoteAsset(),
                List.of(trade(301, MAKER_ID, PREMIUM_PRICE, QUANTITY, true, true)));
    }

    /**
     * 构造本组合保证金模式下、权利金价十、数量二的限价 GTC 净仓期权委托。
     * @param orderId 本次测试委托的订单 ID
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     * @param side 期权委托的买卖方向
     */
    private PlaceOrderCommand optionOrder(long orderId, Variant variant, CoreOrderSide side) {
        return new PlaceOrderCommand(orderId, variant.instrumentId(), side, PREMIUM_PRICE, QUANTITY, false, variant.marginMode(), CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "");
    }

    /**
     * 给双方注资并手工建立默认入场价、相反方向的两份交割持仓，保证金都用默认一百。
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     * @param userWallet 主用户需要注入的结算资产数量
     * @param makerWallet 第一做市用户需要注入的结算资产数量
     */
    private TradingCoreState oppositePositions(Variant variant, long userWallet, long makerWallet) {
        // 本场景依配置、注资、持仓、挂单或结算顺序推进的冷状态
        TradingCoreState state = fundedState(variant, USER_ID, userWallet);
        state = fundedState(state, MAKER_ID, makerWallet);
        state = addPosition(state, USER_ID, QUANTITY, variant);
        return addPosition(state, MAKER_ID, -QUANTITY, variant);
    }

    /**
     * 使用本组合默认标的、入场价及保证金添加测试净仓持仓。
     * @param state 需要变更、读取或检查的当前冷状态
     * @param userId 需要注资、建立持仓或统计金额的用户
     * @param quantity 手工建立的带方向持仓数量，正为多头、负为空头
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     */
    private TradingCoreState addPosition(TradingCoreState state, long userId, long quantity, Variant variant) {
        return addPosition(state, userId, variant.instrumentId(), quantity, ENTRY_PRICE, POSITION_MARGIN, variant);
    }

    /**
     * 复制指定用户余额和持仓，保证金为正时先冻结，再直接写入指定带方向持仓并增加用户和全局修订；属于测试拼装，不执行生产准入。
     * @param state 需要变更、读取或检查的当前冷状态
     * @param userId 需要注资、建立持仓或统计金额的用户
     * @param instrumentId 本次手工持仓或委托所属的规范数字标的 ID
     * @param quantity 手工建立的带方向持仓数量，正为多头、负为空头
     * @param entryPrice 手工建立持仓的入场价格 tick
     * @param positionMargin 手工建立持仓时需要冻结的保证金，正数才执行冻结
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     */
    private TradingCoreState addPosition(TradingCoreState state, long userId, String instrumentId, long quantity,
                                         long entryPrice, long positionMargin, Variant variant) {
        // 手工加入持仓前的原用户值
        CoreUserState current = state.user(userId);
        // 该用户结算资产的余额，保证金为正时先从可用移到锁定
        AssetBalance balance = current.balances().get(variant.settleAsset());
        if (positionMargin > 0) balance = balance.reserve(positionMargin);
        // 复制的余额表，只替换新增持仓所用结算资产
        Map<String, AssetBalance> balances = new TreeMap<>(current.balances());
        balances.put(variant.settleAsset(), balance);
        // 复制的持仓表，插入本次指定标的净仓持仓
        Map<String, CorePositionState> positions = new TreeMap<>(current.positions());
        positions.put(instrumentId, new CorePositionState(instrumentId, variant.settleAsset(), variant.marginMode(),
                CorePositionSide.NET, quantity, entryPrice,
                Math.multiplyExact(Math.absExact(quantity), entryPrice), 0, positionMargin));
        // 手工建立持仓并增加用户修订后的新用户值
        CoreUserState next = new CoreUserState(current.productLine(), userId,
                Math.incrementExact(current.revision()), balances, current.reservations(), positions,
                current.positionMode());
        // 复制的用户表，只替换本次手工变更的用户
        Map<Long, CoreUserState> users = new TreeMap<>(state.users());
        users.put(userId, next);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users,
                state.orders(), state.instruments(), state.riskState(), state.treasuryState());
    }

    /**
     * 在对应产品线创建指定交割或期权标的与初始价格，给指定用户注资；期权价格另包含固定指数与远期一百。
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     * @param userId 需要注资、建立持仓或统计金额的用户
     * @param wallet 本次给用户追加的结算资产数量
     */
    private TradingCoreState fundedState(Variant variant, long userId, long wallet) {
        // 本场景依配置、注资、持仓、挂单或结算顺序推进的冷状态
        TradingCoreState state = reducer.registerInstrument(TradingCoreState.empty(variant.productLine()),
                instrument(variant));
        state = reducer.applyMarkPrice(state, variant.type().isOption()
                ? new ApplyMarkPriceCommand(variant.instrumentId(), PREMIUM_PRICE, 100, 100, 1,
                1_700_000_000_000L)
                : new ApplyMarkPriceCommand(variant.instrumentId(), ENTRY_PRICE, 1, 1_700_000_000_000L));
        return reducer.adjustBalance(state, userId,
                new BalanceAdjustmentCommand(variant.settleAsset(), wallet));
    }

    /**
     * 从已有状态第一个标的取结算资产，再给指定用户追加钱包金额，依赖本测试资产一致的布置。
     * @param state 需要变更、读取或检查的当前冷状态
     * @param userId 需要注资、建立持仓或统计金额的用户
     * @param wallet 本次给用户追加的结算资产数量
     */
    private TradingCoreState fundedState(TradingCoreState state, long userId, long wallet) {
        // 已有状态第一个标的的结算资产，用来给后续用户注资
        String settleAsset = state.instruments().values().iterator().next().settleAsset();
        return reducer.adjustBalance(state, userId,
                new BalanceAdjustmentCommand(settleAsset, wallet));
    }

    /**
     * 按本组合默认数字标的 ID 构造交割或期权注册参数。
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     */
    private RegisterInstrumentCommand instrument(Variant variant) {
        return instrument(variant, variant.instrumentId());
    }

    /**
     * 使用组合合约类型、资产及整数单位，固定正到期时间和零费率；期权写入看涨看跌类型及行权价一百，交割不带期权参数。
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     * @param instrumentId 本次手工持仓或委托所属的规范数字标的 ID
     */
    private RegisterInstrumentCommand instrument(Variant variant, String instrumentId) {
        return new RegisterInstrumentCommand(
                instrumentId, variant.baseAsset(), variant.quoteAsset(), variant.settleAsset(),
                variant.notionalMultiplierUnits(), 1, variant.settleScaleUnits(),
                (variant.type() == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 100_000)))),
                0, 0, 2_000_000_000_000L, variant.optionType() == null ? -1 : variant.optionType().ordinal(),
                variant.optionType() == null ? 0 : STRIKE_PRICE);
    }

    /**
     * 构造指定标的、价格一百、数量一的全仓净仓限价买单；variant 参数当前未读取，保证金模式固定全仓。
     * @param orderId 本次测试委托的订单 ID
     * @param variant 保留的测试组合参数，当前方法不读取，订单保证金模式固定全仓
     * @param instrumentId 本次手工持仓或委托所属的规范数字标的 ID
     */
    private PlaceOrderCommand deliveryOrder(long orderId, Variant variant, String instrumentId) {
        return new PlaceOrderCommand(orderId, instrumentId, CoreOrderSide.BUY, ENTRY_PRICE, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "");
    }

    /**
     * 给该交割或期权标的注资后尝试资金费，检查不支持的业务代码、原对象及哈希不变，且未登记资金费结算。
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     * @param settlementId 本次故意请求不支持资金费的结算业务 ID
     */
    private void assertFundingRejected(Variant variant, long settlementId) {
        // 本场景依配置、注资、持仓、挂单或结算顺序推进的冷状态
        TradingCoreState state = fundedState(variant, USER_ID, WALLET);
        // 请求不支持资金费前的同一冷状态引用，拒绝后仍为原对象
        TradingCoreState before = state;
        // 预期拒绝前的业务哈希，之后必须保持原值
        long hash = state.businessStateHash();
        assertThatThrownBy(() -> reducer.applyFunding(state,
                new ApplyFundingCommand(settlementId, variant.instrumentId(), 100_000)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定业务代码核对原因 */ exception -> assertThat(exception.code()).isEqualTo("PRODUCT_LINE_UNSUPPORTED"));
        assertThat(state).isSameAs(before);
        assertThat(state.businessStateHash()).isEqualTo(hash);
        assertThat(state.treasuryState().fundingSettlements()).doesNotContainKey(variant.instrumentId());
    }

    /**
     * 独立用带方向数量乘一百乘一百再乘价差，除以入场价与交割价，按 HALF_UP 到整数精确转 long；常量对应本测试反向合约单位，不适用于任意配置。
     * @param signedQuantitySteps 独立反向交割公式中的带方向数量
     * @param entryPriceTicks 独立公式的入场价格，调用方需提供有效分母
     * @param settlementPriceTicks 本组合结算价格或独立公式交割价，按价格 tick 保存
     */
    private static long independentInverseDeliveryPayout(long signedQuantitySteps, long entryPriceTicks,
                                                          long settlementPriceTicks) {
        // 独立反向交割公式分子：带方向数量乘两个一百单位因子再乘价差
        BigDecimal numerator = BigDecimal.valueOf(signedQuantitySteps)
                .multiply(BigDecimal.valueOf(100))
                .multiply(BigDecimal.valueOf(100))
                .multiply(BigDecimal.valueOf(settlementPriceTicks - entryPriceTicks));
        // 独立反向交割公式分母：入场价乘交割价
        BigDecimal denominator = BigDecimal.valueOf(entryPriceTicks)
                .multiply(BigDecimal.valueOf(settlementPriceTicks));
        return numerator.divide(denominator, 0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * 检查本标的到期时间为正、各已有持仓数量和保证金为零、该结算资产所有用户锁定为零，并确认生命周期结算映射存在本标的项。
     * @param state 需要变更、读取或检查的当前冷状态
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     */
    private void assertFlatAndReleased(TradingCoreState state, Variant variant) {
        assertThat(state.instruments().get(variant.instrumentId()).expiryEpochMillis()).isPositive();
        state.users().values().forEach(/* 当前需要构造或检查持仓、锁定或合计资金的用户值 */ user -> {
            // 当前用户在目标标的的已有持仓，没有时跳过持仓归零断言
            CorePositionState position = user.positions().get(variant.instrumentId());
            if (position != null) {
                assertThat(position.signedQuantitySteps()).as(variant.key()).isZero();
                assertThat(position.positionMarginUnits()).as(variant.key()).isZero();
            }
            assertThat(user.balances().get(variant.settleAsset()).lockedUnits()).as(variant.key()).isZero();
        });
        assertThat(state.treasuryState().lifecycleSettlements()).containsEntry(variant.instrumentId(),
                state.treasuryState().lifecycleSettlement(variant.instrumentId()));
    }

    /**
     * 逐行拒绝重复键并比较用户、做市、手续费和保险统计的期初期末值，检查手工 Funds 差额和观察合计变化为零；此处没有再逐项断言四条 flow。
     * @param rows 本次已生成的完整资金矩阵行
     */
    private void assertRows(List<Row> rows) {
        // 本次已断言的场景键集合，重复加入立即失败
        Set<String> seen = new LinkedHashSet<>();
        for (Row /* 当前统计键出现次数或核对资金金额的一行 */ row : rows) {
            assertThat(seen.add(row.key())).as("duplicate matrix row").isTrue();
            // 当前矩阵行手工列出的四类期初、净变化和期末统计值
            Funds expected = row.funds();
            // 按本行口径从期初状态读出的用户资金值
            long openingUser = userValue(row.opening(), row.variant());
            // 按本行口径从期初状态读出的做市用户资金值
            long openingMaker = userValue(row.opening(), row.variant(), MAKER_ID);
            // 从期初状态读出的手续费账资金值
            long openingFee = feeValue(row.opening(), row.variant());
            // 按本行口径从期初状态读出的保险账资金值
            long openingInsurance = insuranceValue(row.opening(), row.variant());
            // 按本行口径从期末状态读出的用户资金值
            long endingUser = userValue(row.ending(), row.variant());
            // 按本行口径从期末状态读出的做市用户资金值
            long endingMaker = userValue(row.ending(), row.variant(), MAKER_ID);
            // 从期末状态读出的手续费账资金值
            long endingFee = feeValue(row.ending(), row.variant());
            // 按本行口径从期末状态读出的保险账资金值
            long endingInsurance = insuranceValue(row.ending(), row.variant());
            assertThat(openingUser).as(row.key() + " user opening").isEqualTo(expected.userOpening());
            assertThat(openingMaker).as(row.key() + " maker opening").isEqualTo(expected.makerOpening());
            assertThat(openingFee).as(row.key() + " fee opening").isEqualTo(expected.feeOpening());
            assertThat(openingInsurance).as(row.key() + " insurance opening").isEqualTo(expected.insuranceOpening());
            assertThat(endingUser).as(row.key() + " user ending").isEqualTo(expected.userEnding());
            assertThat(endingMaker).as(row.key() + " maker ending").isEqualTo(expected.makerEnding());
            assertThat(endingFee).as(row.key() + " fee ending").isEqualTo(expected.feeEnding());
            assertThat(endingInsurance).as(row.key() + " insurance ending").isEqualTo(expected.insuranceEnding());
            assertThat(expected.difference()).as(row.key() + " FUNDS_DIFFERENCE").isZero();
            assertThat((endingUser + endingMaker + endingFee + endingInsurance)
                    - (openingUser + openingMaker + openingFee + openingInsurance))
                    .as(row.key() + " observed FUNDS_DIFFERENCE").isZero();
        }
    }

    /**
     * 按当前组合结算资产读取主用户总现金余额。
     * @param state 需要变更、读取或检查的当前冷状态
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     */
    private long userValue(TradingCoreState state, Variant variant) {
        return userValue(state, variant, USER_ID);
    }

    /**
     * 指定用户不存在时返回零，否则读取该组合结算资产总现金余额，不加未实现盈亏。
     * @param state 需要变更、读取或检查的当前冷状态
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     * @param userId 需要注资、建立持仓或统计金额的用户
     */
    private long userValue(TradingCoreState state, Variant variant, long userId) {
        return state.user(userId) == null ? 0 : state.user(userId).totalUnits(variant.settleAsset());
    }

    /**
     * 读取本组合结算资产普通手续费余额，没有为零。
     * @param state 需要变更、读取或检查的当前冷状态
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     */
    private long feeValue(TradingCoreState state, Variant variant) {
        return state.treasuryState().feeBalances().getOrDefault(variant.settleAsset(), 0L);
    }

    /**
     * 按本组合结算资产取保险余额减去财库保险亏空，作为该矩阵保险统计值。
     * @param state 需要变更、读取或检查的当前冷状态
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     */
    private long insuranceValue(TradingCoreState state, Variant variant) {
        return state.treasuryState().insuranceBalances().getOrDefault(variant.settleAsset(), 0L)
                - state.treasuryState().insuranceDeficits().getOrDefault(variant.settleAsset(), 0L);
    }

    /**
     * 合计指定资产所有用户现金及各财库余额，最后扣保险亏空；用户合计先用 stream.sum，随后财库合并采用精确加减。
     * @param state 需要变更、读取或检查的当前冷状态
     * @param asset 合计用户及财库资金的资产代码
     */
    private long total(TradingCoreState state, String asset) {
        // 指定资产全部用户现金的 stream.sum 合计，尚未合入财库余额
        long users = state.users().values().stream().mapToLong(/* 当前需要构造或检查持仓、锁定或合计资金的用户值 */ user -> user.totalUnits(asset)).sum();
        // 本次总额统计读到的财库分量
        CoreTreasuryState treasury = state.treasuryState();
        // 逐项合入手续费、保险、强平费、资金费残差、舍入及清算余额的累计总额，最后减保险亏空
        long total = users;
        total = Math.addExact(total, treasury.feeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.insuranceBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.liquidationFeeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.fundingResidualBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.roundingResidualBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.clearingPnlBalances().getOrDefault(asset, 0L));
        return Math.subtractExact(total, treasury.insuranceDeficits().getOrDefault(asset, 0L));
    }

    /**
     * 交割使用合约类型与保证金模式，期权使用 OPTION、看涨看跌和实平虚值标签，生成固定矩阵键。
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     */
    private static String rowKey(Variant variant) {
        return variant.type().isOption()
                ? "OPTION:" + variant.optionType().name() + ':' + variant.moneyness()
                : variant.type().name() + ':' + variant.marginMode().name();
    }

    /**
     * 保存手工列出的期初、净变化及期末统计值，构造时不自动计算业务现金。
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
     * 一种交割或期权测试组合，固定产品类型、保证金模式、期权实平虚值、结算价及资产单位。
     * @param type 本组合具体的正反向交割或普通期权合约类型
     * @param marginMode 本组合采用的全仓或逐仓模式
     * @param optionType 期权看涨或看跌类型，交割组合为空
     * @param moneyness 期权实值、平值、虚值的 ITM/ATM/OTM 标签，交割为空文本
     * @param settlementPriceTicks 本组合结算价格或独立公式交割价，按价格 tick 保存
     * @param notionalMultiplierUnits 本组合每份合约的名义金额乘数
     * @param settleScaleUnits 本组合结算资产整数金额换算比例
     * @param baseAsset 本组合基础资产代码
     * @param quoteAsset 本组合报价资产代码
     * @param settleAsset 本组合结算资产代码
     */
    private record Variant(ContractType type, CoreMarginMode marginMode, OptionType optionType,
                           String moneyness, long settlementPriceTicks, long notionalMultiplierUnits,
                           long settleScaleUnits, String baseAsset, String quoteAsset, String settleAsset) {
        /**
         * 本测试期权统一使用数字标的六，交割统一使用数字标的八。
         */
        private String instrumentId() {
            return type.isOption() ? "6" : "8";
        }

        /**
         * 返回具体合约类型所属的独立产品线。
         */
        private ProductLine productLine() {
            return type.productLine();
        }

        /**
         * 按本组合类型和实平虚值等参数生成固定矩阵键。
         */
        private String key() {
            return rowKey(this);
        }
    }

    /**
     * 手工列出的四类资金期初、净变化和期末值，difference 用于检查未解释差额。
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
         * 四类期末合计减期初合计再减已列净变化，得到该预期资金行未解释的差额。
         */
        private long difference() {
            return userEnding + makerEnding + feeEnding + insuranceEnding
                    - userOpening - makerOpening - feeOpening - insuranceOpening
                    - userFlow - makerFlow - feeFlow - insuranceFlow;
        }
    }

    /**
     * 一行已执行交割或期权场景，保存前后冷状态及其独立列出的资金期望。
     * @param key 本行交割或期权组合的固定覆盖键
     * @param variant 本次交割或期权的类型、保证金模式、资产单位和结算价组合
     * @param opening 本资金行选定的期初冷状态
     * @param ending 本资金行选定的期末冷状态
     * @param funds 该行手工列出的四类期初、净变化和期末期望
     */
    private record Row(String key, Variant variant, TradingCoreState opening,
                       TradingCoreState ending, Funds funds) {
    }

    /**
     * 按具体交割组合手工固定的多空收益及双方期末余额，不由生产结算函数生成。
     * @param longPayout 独立列出的交割多头带符号收益
     * @param shortPayout 独立列出的交割空头带符号收益
     * @param userEnding 期末用户资金统计值
     * @param makerEnding 期末纳入该行的做市用户资金统计值
     */
    private record DeliveryExpectation(long longPayout, long shortPayout, long userEnding, long makerEnding) {
    }
}
