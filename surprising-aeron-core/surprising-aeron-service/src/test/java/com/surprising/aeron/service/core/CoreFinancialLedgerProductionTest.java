package com.surprising.aeron.service.core;

import com.surprising.aeron.service.order.state.RuntimeTestStateTransitions;

import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.snapshot.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.insurance.command.InsuranceFundCommands;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 真实命令入口自动启用账本的有限场景测试，以相同底层转换的冷状态作为对照；涵盖强平、保险、ADL 和交割/期权结算，不等同生产负载或独立资金公式验证。
 */
class CoreFinancialLedgerProductionTest {
    /** 本测试固定业务时间，交割命令另加偏移。 */
    private static final long TIME = 1_700_000_000_000L;
    /** 本测试各命令共用递增来源及UUID身份。 */
    private long sequence;

    /**
     * 构造风险仍待续扫且只有一条执行动作的场景，守卫内执行强平批次并要求本批继续风险扫描；核对用户仓位归零，并把用户和财库与冷执行对照，未逐项比较全部风险续步状态。
     */
    @Test
    void liquidationBatchReadsRiskInputsInsideItsUncommittedLedgerScope() throws Exception {
        try (/* 本轮真实交易入口，退出时关闭账户与账本资源 */ var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            register(core, core.productLine);
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME), core.productLine), TIME);
            balance(core, 1, "USDT", 110); balance(core, 2, "USDT", 10_000);
            trade(core, core.productLine, 10, 100);
            for (/* 额外挂入的48条买单下标，为风险续扫准备活动订单 */ int item = 0; item < 48; item++) applied(core, CoreMessageType.PLACE_ORDER, 2,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(1000 + item, "1", CoreOrderSide.BUY,
                            80, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.GTC, false, "nested-risk-" + item)), TIME);
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 1, 2, TIME), core.productLine), TIME);
            // 按真实查询取得的强平工作页，要求仍有风险续步
            CoreLiquidationWorkView work;
            for (/* 本次工作查询与续扫次数，限制少于32次 */ int attempt = 0; ; attempt++) {
                assertThat(attempt).isLessThan(32);
                // 实际强平工作查询请求
                var request = new CoreMessage(CoreMessageHeader.query(CoreMessageType.LIQUIDATION_WORK_QUERY,
                        new UUID(949, attempt + 1), core.productLine, CommandSource.OPERATIONS, 949, attempt + 1, 0, TIME, 0),
                        CoreLiquidationWorkCodec.encodeQuery(core.productLine, CoreLiquidationWorkView.Purpose.EXECUTION, 0, 1000, 1_048_576));
                // 本次工作查询或批次执行的协议结果
                var response = CoreTestCompletion.querySynchronously(core, request);
                assertThat(response.status()).isEqualTo(ResponseStatus.OK);
                work = CoreLiquidationWorkCodec.decodeWork(response.data());
                if (!work.actions().isEmpty()) break;
                applied(core, CoreMessageType.CONTINUE_RISK_SCAN, 0,
                        TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(8)), TIME);
            }
            assertThat(work.riskScanPending()).isTrue();
            assertThat(work.actions()).hasSize(1);
            // 工作页唯一强平动作，供冷执行生成对照
            var action = work.actions().getFirst();
            // 使用相同底层转换得到的冷对照状态，不是独立公式模型
            var expected = expected(core.tradingState(), (/* 冷对照回调实际操作的账户运行时 */ runtime, /* 与冷对照运行时配套的身份注册表 */ identities) ->
                    RuntimeDerivativeLiquidationFixture.applyExecutionRuntime(new ExecuteLiquidationCommand(
                            action.liquidationId(), action.triggerPriceSequence(), action.markPriceTicks(), 0),
                            List.of(), runtime, identities));
            // 本次工作查询或批次执行的协议结果
            var response = appliedWithGuard(core, CoreMessageType.EXECUTE_LIQUIDATION_BATCH, 0,
                    TradingCommandCodec.encodeExecuteLiquidationBatch(ExecuteLiquidationBatchCommand.fromWork(work, 0, 8)), TIME);
            // 解码出的强平批次执行数量和风险续扫结果
            var result = CoreLiquidationBatchResultCodec.decode(response.data());
            assertThat(result.appliedActions()).isEqualTo(1);
            assertThat(result.riskScanContinuedUsers()).isPositive();
            assertThat(CoreTestCompletion.userState(core, 1).positions()).allSatisfy(/* 当前断言的持仓视图，要求数量为零 */ position ->
                    assertThat(position.signedQuantitySteps()).isZero());
            // 真实批次执行后的物化状态，本场景只比较用户与财库
            var actual = core.tradingState();
            assertThat(actual.users()).isEqualTo(expected.users());
            assertThat(actual.treasuryState()).isEqualTo(expected.treasuryState());
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 通过真实入口依次强平、把保险余额调整到25、保险承担25及ADL处理剩余缺口，每步与冷转换结果比较；检查交接轮次、最终全部仓位归零及账本已启动。
     */
    @Test
    void liquidationInsuranceAndAdlCommitOriginalLedgerInProductionOrder() throws Exception {
        // 本场景U本位永续产品线
        var line = ProductLine.LINEAR_PERPETUAL;
        try (/* 本轮真实交易入口，退出时关闭账户与账本资源 */ var core = new TradingCoreRuntime(line)) {
            register(core, line);
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME), core.productLine), TIME);
            balance(core, 1, "USDT", 110);
            balance(core, 2, "USDT", 10_000);
            trade(core, line, 10, 100);
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 1, 2, TIME), core.productLine), TIME);
            for (/* 等待风险阶段完成的续扫轮次，要求少于20次 */ int polls = 0; core.runtimeState.firstRiskIncompleteScan() != null; polls++) {
                assertThat(polls).isLessThan(20);
                applied(core, CoreMessageType.CONTINUE_RISK_SCAN, 0,
                        TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64)), TIME);
            }
            // 用户1已计划的强平状态
            var planned = core.tradingState().riskState().liquidations().values().stream()
                    .filter(/* 筛选用户1强平计划的当前状态 */ value -> value.userId() == 1).findFirst().orElseThrow();
            // 单笔执行原强平计划的命令
            var execute = new ExecuteLiquidationCommand(planned.liquidationId(), 2, 1, 0);
            // 使用相同底层转换得到的冷对照状态，不是独立公式模型
            var expected = expected(core.tradingState(), (/* 冷对照回调实际操作的账户运行时 */ runtime, /* 与冷对照运行时配套的身份注册表 */ identities) ->
                    RuntimeDerivativeLiquidationFixture.applyExecutionRuntime(execute, List.of(), runtime, identities));
            // 执行前的账户所有权交接轮次，执行后要求不变
            long handoff = com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(core.runtimeState);
            appliedWithGuard(core, CoreMessageType.EXECUTE_LIQUIDATION, 0, TradingCommandCodec.encodeExecuteLiquidation(execute), TIME);
            assertThat(com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(core.runtimeState)).isEqualTo(handoff);
            assertThat(core.tradingState()).isEqualTo(expected);

            // 使保险余额达到25所需的差额调整，不能直接理解为固定充值25
            var topUp = new AdjustInsuranceFundCommand("USDT", 25 - expected.treasuryState().insuranceBalances().getOrDefault("USDT", 0L));
            expected = expected(expected, (/* 冷对照回调实际操作的账户运行时 */ runtime, /* 与冷对照运行时配套的身份注册表 */ identities) ->
                    LedgerControlTestSupport.await(runtime, InsuranceFundCommands.prepare(runtime, identities, topUp)));
            appliedWithGuard(core, CoreMessageType.ADJUST_INSURANCE_FUND, 0, TradingCommandCodec.encodeAdjustInsuranceFund(topUp), TIME);
            assertThat(core.tradingState()).isEqualTo(expected);
            // 由保险承担25的强平处置命令
            var insurance = new ResolveLiquidationCommand(planned.liquidationId(), ResolveLiquidationCommand.Resolution.INSURANCE, 25);
            expected = expected(expected, (/* 冷对照回调实际操作的账户运行时 */ runtime, /* 与冷对照运行时配套的身份注册表 */ identities) ->
                    RuntimeDerivativeLiquidationFixture.applyResolution(insurance, runtime, identities, List.of(planned.liquidationId())));
            appliedWithGuard(core, CoreMessageType.RESOLVE_LIQUIDATION, 0, TradingCommandCodec.encodeResolveLiquidation(insurance), TIME);
            assertThat(core.tradingState()).isEqualTo(expected);
            // 保险承担之后仍要求ADL的剩余强平状态
            var residual = expected.riskState().liquidations().get(planned.liquidationId());
            assertThat(residual.status()).isEqualTo(CoreLiquidationState.Status.ADL_REQUIRED);
            // 对剩余缺口执行ADL的原业务参数
            var adl = new ExecuteAdlCommand(planned.liquidationId(), 2, "1", CoreMarginMode.CROSS,
                    CorePositionSide.NET, -10, 100, 2, 10, residual.deficitUnits());
            expected = expected(expected, (/* 冷对照回调实际操作的账户运行时 */ runtime, /* 与冷对照运行时配套的身份注册表 */ identities) -> RuntimeDerivativeLiquidationFixture.applyAdl(adl, runtime, identities));
            appliedWithGuard(core, CoreMessageType.EXECUTE_ADL, 0, TradingCommandCodec.encodeExecuteAdl(adl), TIME);
            assertThat(core.tradingState()).isEqualTo(expected);
            assertThat(core.tradingState().users().values()).allSatisfy(/* 本轮准备资金、成交或检查的测试用户 */ user -> assertThat(user.positions().values()).allSatisfy(/* 当前断言的持仓视图，要求数量为零 */ position -> assertThat(position.signedQuantitySteps()).isZero()));
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 对U本位交割、币本位交割和期权执行标的结算命令，比较完整冷转换结果，核对仓位归零、账本启用且无需账户交接；本例不覆盖自动调度器。
     * @param line 本轮实际使用的产品线
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    void expiryCommandUsesAutomaticallyStartedLedgerAndMatchesColdStateTransitions(ProductLine line) throws Exception {
        try (/* 本轮真实交易入口，退出时关闭账户与账本资源 */ var core = new TradingCoreRuntime(line)) {
            register(core, line);
            // 币本位交割使用BTC，其余本例使用USDT
            String asset = line == ProductLine.INVERSE_DELIVERY ? "BTC" : "USDT";
            for (long /* 本轮准备资金、成交或检查的测试用户 */ user : new long[]{1, 2}) balance(core, user, asset, 10_000);
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(line.isOptionProduct() ? new ApplyMarkPriceCommand("1", 100, 100, 100, 1, TIME)
                            : new ApplyMarkPriceCommand("1", 100, 1, TIME), line), TIME);
            trade(core, line, 2, line == ProductLine.OPTION ? 10 : 100);
            // 对固定标的1按价格120执行的结算命令
            var settlement = new SettleInstrumentCommand(901, "1", 120);
            // 结算前实际状态，作为冷转换输入
            var opening = core.tradingState();
            // 使用相同底层转换得到的冷对照状态，不是独立公式模型
            var expected = expected(opening, (/* 冷对照回调实际操作的账户运行时 */ runtime, /* 与冷对照运行时配套的身份注册表 */ identities) ->
                    RuntimeTestStateTransitions.applyLifecycleSettlement(opening, settlement, null, null, runtime, identities));
            // 执行前的账户所有权交接轮次，执行后要求不变
            long handoff = com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(core.runtimeState);
            appliedWithGuard(core, CoreMessageType.SETTLE_INSTRUMENT, 0, TradingCommandCodec.encodeSettleInstrument(settlement), TIME + 2_000);
            assertThat(com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(core.runtimeState)).isEqualTo(handoff);
            assertThat(core.tradingState()).isEqualTo(expected);
            assertThat(core.tradingState().users().values()).allSatisfy(/* 本轮准备资金、成交或检查的测试用户 */ user -> assertThat(user.positions().values()).allSatisfy(/* 当前断言的持仓视图，要求数量为零 */ position -> assertThat(position.signedQuantitySteps()).isZero()));
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 把给定业务状态投影为冷运行时，应用指定底层转换后再物化对照结果，不经历序列化恢复。
     * @param before 冷对照投影前的业务状态
     * @param mutation 在冷运行时应用原底层业务转换的回调
     */
    private TradingCoreState expected(TradingCoreState before, BiConsumer<TradingAccountRuntime, RuntimeIdentityRegistry> mutation) {
        // 与冷对照运行时配套的身份注册表
        var identities = new RuntimeIdentityRegistry();
        try (/* 冷对照回调实际操作的账户运行时 */ var runtime = RuntimeStateProjector.project(before, identities)) {
            mutation.accept(runtime, identities);
            runtime.clearChangedKeys();
            return RuntimeStateMaterializer.materialize(runtime, identities);
        }
    }

    /**
     * 按本产品合约类型登记固定标的1，选择本位资产、交割到期值和期权CALL参数。
     * @param core 本次真实命令运行时
     * @param line 本轮实际使用的产品线
     */
    private void register(TradingCoreRuntime core, ProductLine line) {
        // 从本产品契约码得到的合约类型
        var type = ContractType.valueOf(line.contractTypeCode());
        // 该类型是否币本位，用于选择结算资产和尺度
        boolean inverse = type.isInverse();
        applied(core, CoreMessageType.REGISTER_INSTRUMENT, 0,
                TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                        "1", "BTC", "USDT", inverse ? "BTC" : "USDT", 1, 1, inverse ? 1_000 : 1,
                        (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                                100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                        0, 0, line.isDeliveryProduct() ? TIME + 1_000 : 0,
                        line.isOptionProduct() ? OptionType.CALL.ordinal() : -1, line.isOptionProduct() ? 100 : 0), line), TIME);
    }

    /**
     * 通过真实余额调整命令准备指定用户资产。
     * @param core 本次真实命令运行时
     * @param user 本次命令用户身份，系统命令可为零
     * @param asset 测试余额资产名称
     * @param amount 测试余额调整整数数量
     */
    private void balance(TradingCoreRuntime core, long user, String asset, long amount) {
        applied(core, CoreMessageType.ADJUST_BALANCE, user,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, amount)), TIME);
    }

    /**
     * 用户2先挂卖单、用户1再挂买单，以给定价格和数量形成对手成交；产品参数当前未直接读取。
     * @param core 本次真实命令运行时
     * @param line 本辅助方法当前未直接读取的产品参数，实际产品取自 core
     * @param quantity 本次双方成交数量
     * @param price 本次双方成交整数价格
     */
    private void trade(TradingCoreRuntime core, ProductLine line, long quantity, long price) {
        for (long /* 本轮准备资金、成交或检查的测试用户 */ user : new long[]{2, 1}) applied(core, CoreMessageType.PLACE_ORDER, user,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100 + user, "1",
                        user == 2 ? CoreOrderSide.SELL : CoreOrderSide.BUY, price, quantity, false,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                        false, "financial-" + user)), TIME);
    }

    /**
     * 在既有 Owner 账户读取守卫中执行命令并等待实际终态。
     * @param core 本次真实命令运行时
     * @param type 真实协议命令类型
     * @param user 本次命令用户身份，系统命令可为零
     * @param payload 原命令编码载荷
     * @param timestamp 指定的原复制业务时间
     */
    private CoreResponse appliedWithGuard(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload, long timestamp) throws Exception {
        try (/* 已有 Owner 账户读取守卫，只包住本次真实执行 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
            return applied(core, type, user, payload, timestamp);
        }
    }

    /**
     * 生成递增身份的真实命令，带指定业务时间及位置调用异步完成帮助器，并要求 commandStatus 为 APPLIED。
     * @param core 本次真实命令运行时
     * @param type 真实协议命令类型
     * @param user 本次命令用户身份，系统命令可为零
     * @param payload 原命令编码载荷
     * @param timestamp 指定的原复制业务时间
     */
    private CoreResponse applied(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload, long timestamp) {
        // 本测试新分配的原命令序号
        long seq = ++sequence;
        // 完整命令头与载荷，位置和时间显式传给完成帮助器
        var message = new CoreMessage(CoreMessageHeader.command(type, new UUID(948, seq), core.productLine,
                CommandSource.OPERATIONS, 948, seq, user, timestamp, seq), payload);
        // 本次工作查询或批次执行的协议结果
        var response = CoreTestCompletion.applyAsynchronously(core, message, timestamp, seq);
        assertThat(response.commandStatus()).as("%s: %s", type, response.resultCode()).isEqualTo(ResponseStatus.APPLIED);
        return response;
    }
}
