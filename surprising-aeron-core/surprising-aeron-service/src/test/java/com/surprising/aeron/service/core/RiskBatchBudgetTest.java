package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.replay.CommittedTradeReplay;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 风险与强平批次的预算、继续令牌及恢复测试。覆盖多币对共享工作量、跨账户撤单预算、溢出回滚，以及旧动作重放后账户与资金事实保持一致。
 */
class RiskBatchBudgetTest {
    /** 本测试实例已经分配的来源序号，每次构造消息先递增一次。 */
    private long sequence;
    /** 所有测试消息使用的固定 Unix 毫秒基准时间。 */
    private static final long TIME = 1_700_000_000_000L;

    /**
     * 五条衍生品线都构造一个用户很多和一个用户很少的币对，首批小预算也必须调度到轻币对；同步参照、异步续步与快照恢复最终业务哈希相同，风险扫描不改资金哈希。
     * @param line 当前参数化测试的产品线，所有配置、账户、命令和恢复状态必须保持一致
     */
    @ParameterizedTest
    @EnumSource(value=ProductLine.class, names={"LINEAR_PERPETUAL","INVERSE_PERPETUAL",
            "LINEAR_DELIVERY","INVERSE_DELIVERY","OPTION"})
    void heavySymbolCannotConsumeEverySliceOfSharedBudget(ProductLine line) throws Exception {
        try (/* 本用例创建的交易运行时，离开作用域时关闭线程等资源。 */ var state = new TradingCoreRuntime(line)) {
            // 产品线对应的合约类型，用来设置反向金额精度、到期时间及期权行权信息。
            var type = ContractType.valueOf(line.contractTypeCode());
            // 本产品的测试结算资产，反向合约使用 BTC，其余使用 USDT。
            String asset=type.isInverse()?"BTC":"USDT";
            for (/* 注册币对、发送初始行情或推进后续有限批次的循环下标。 */ int i=0;i<2;i++) {
                // 由当前下标生成的测试币对编号，让共享预算能够在多个币对之间轮转。
                String instrumentId=Integer.toString(100+i);
                applied(state,command(line,CoreMessageType.REGISTER_INSTRUMENT,
                        TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(instrumentId,
                                type.ordinal(),"BTC","USDT",asset,1,1,type.isInverse()?1000:1,
                                100_000,50_000,0,0,type.isDelivery()||type.isOption()?2_000_000_000_000L:0,
                                type.isOption()?0:-1,type.isOption()?100:0))));
            }
            for (/* 注册币对、发送初始行情或推进后续有限批次的循环下标。 */ int i=0;i<2;i++) applied(state,mark(line,Integer.toString(100+i),1));
            for(/* 当前依次充值和建仓的五十个用户，前四十八个放繁忙币对，后两个放轻币对。 */ long user=1;user<=50;user++) {
                applied(state,command(line,CoreMessageType.ADJUST_BALANCE,user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset,1_000_000))));
                applied(state,command(line,CoreMessageType.PLACE_ORDER,user,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100+user,
                                user<=48?"100":"101",
                                user%2==1?CoreOrderSide.SELL:CoreOrderSide.BUY,100,1,false,CoreMarginMode.CROSS,
                                CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"risk-"+user))));
            }
            // 执行前的资金检查量，风险场景用资金哈希，强平场景用含保险及亏空的资金总额。
            long funds=com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(state.tradingState());
            // 风险工作预算为十六的第一批请求，用来验证轻币对不会被繁忙币对挤掉。
            var firstBatch = batch(line, work(state, line), 16);
            try (/* 由执行前快照恢复的同步参照运行时，用来核对第一批响应及业务哈希。 */ var reference = TradingCoreRuntime.fromSnapshot(line, state.snapshot(400))) {
                // 同步参照执行的最终响应，待撮合时在本地补完后再与异步结果比对。
                var expected = reference.apply(firstBatch);
                if (expected.resultCode() == CoreResultCode.MATCHING_PENDING)
                    expected = CoreTestCompletion.completeMatchingSynchronously(reference,
                            reference.matchingSequence(firstBatch.header().commandId()), TIME,
                            firstBatch.header().sourceSequence());
                // 本用例先取得批执行准备阶段需要的 Owner 账户访问权。
                // 后续异步风险续步应由 Lane 完成，不能再进行第二次所有权交接。
                // 准备阶段等待 Owner 账户访问权的单调时钟截止值，最多等待五秒。
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!state.runtimeState.tryAcquireOwnerLaneAccess()) {
                    if (System.nanoTime() > deadline) throw new AssertionError("preparation handoff timeout");
                    Thread.onSpinWait();
                }
                // 运行时 Lane 所有权交接代次的反射字段，用来确认异步续步没有再次借用账户表。
                var epoch = state.runtimeState.getClass().getDeclaredField("laneHandoffEpoch");
                epoch.setAccessible(true);
                // 准备阶段已取得访问权之后的交接代次，整批异步续步不应再次增加它。
                long handoffBefore = epoch.getLong(state.runtimeState);
                // 原运行时本批执行的最终响应，需要与同步或恢复参照逐字节一致。
                var actual = apply(state, firstBatch);
                assertThat(epoch.getLong(state.runtimeState)).isEqualTo(handoffBefore);
                assertThat(actual.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(actual.data()).isEqualTo(expected.data());
                assertThat(state.tradingState().businessStateHash())
                        .isEqualTo(reference.tradingState().businessStateHash());
            }
            assertThat(state.tradingState().riskState().scans().get("100").riskComplete()).isFalse();
            assertThat(state.tradingState().riskState().scans().get("101").lastScheduledRevision()).isPositive();
            try(/* 从当前检查点快照恢复的第二个运行时，应用完全相同的后续消息比对结果。 */ var restored=TradingCoreRuntime.fromSnapshot(line,state.snapshot(500))) {
                for(/* 本次完成剩余扫描的批次数量，最多三十批以防继续游标不推进。 */ int i=0;i<30 && work(state,line).riskScanPending();i++) {
                    // 用最新工作令牌构造的下一批请求，两个运行时应用同一条消息。
                    var next=batch(line,work(state,line),64);
                    applied(state,next);applied(restored,next);
                }
                assertThat(work(state,line).riskScanPending()).isFalse();
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
                assertThat(com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(state.tradingState())).isEqualTo(funds);
            }
        }
    }

    /**
     * 共享预算跨过五个币对并严格限制工作量，重复使用旧继续令牌应拒绝且业务哈希不变；刷新已扫币对不能抢过未扫币对，快照恢复后使用新令牌仍可一致完成。
     * @param line 当前参数化测试的产品线，所有配置、账户、命令和恢复状态必须保持一致
     */
    @ParameterizedTest
    @EnumSource(value=ProductLine.class, names={"LINEAR_PERPETUAL","INVERSE_PERPETUAL",
            "LINEAR_DELIVERY","INVERSE_DELIVERY","OPTION"})
    void budgetSpansSymbolsIsBoundedAndRejectsStaleTokensAcrossRecovery(ProductLine line) {
        try (/* 本用例创建的交易运行时，离开作用域时关闭线程等资源。 */ var state = new TradingCoreRuntime(line)) {
            // 产品线对应的合约类型，用来设置反向金额精度、到期时间及期权行权信息。
            var type = ContractType.valueOf(line.contractTypeCode());
            for (/* 注册币对、发送初始行情或推进后续有限批次的循环下标。 */ int i=0;i<5;i++) {
                // 由当前下标生成的测试币对编号，让共享预算能够在多个币对之间轮转。
                String instrumentId=Integer.toString(100+i);
                applied(state, command(line, CoreMessageType.REGISTER_INSTRUMENT,
                        TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(instrumentId,
                                type.ordinal(),"BTC","USDT",type.isInverse()?"BTC":"USDT",1,1,
                                type.isInverse()?1000:1,100_000,50_000,0,0,
                                type.isDelivery()||type.isOption()?2_000_000_000_000L:0,
                                type.isOption()?0:-1,type.isOption()?100:0))));
            }
            for (/* 注册币对、发送初始行情或推进后续有限批次的循环下标。 */ int i=0;i<5;i++) applied(state, mark(line,Integer.toString(100+i),1));
            for(long /* 当前充值、建仓或汇总余额的测试账户，各场景按指定集合分布到不同 Lane。 */ user:new long[]{7,8}) applied(state,command(line,CoreMessageType.ADJUST_BALANCE,user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(type.isInverse()?"BTC":"USDT",1_000_000))));
            for(long /* 当前充值、建仓或汇总余额的测试账户，各场景按指定集合分布到不同 Lane。 */ user:new long[]{7,8}) applied(state,command(line,CoreMessageType.PLACE_ORDER,user,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100+user,"104",
                            user==7?CoreOrderSide.SELL:CoreOrderSide.BUY,100,1,false,CoreMarginMode.CROSS,
                            CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"risk-"+user))));
            // 执行前的资金检查量，风险场景用资金哈希，强平场景用含保险及亏空的资金总额。
            long funds=com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(state.tradingState());
            // 首批前查询到的继续令牌，应用一次后再次使用应判为过期。
            var first=work(state,line);
            applied(state, batch(line,first,2));
            assertThat(state.tradingState().riskState().scans().values().stream()
                    .filter(/* 正在统计或检查的币对扫描记录，以其风险完成标记验证共享预算推进数量。 */ s->s.riskComplete()).count()).isEqualTo(2);
            // 首次小预算应用成功后的业务哈希，旧令牌拒绝后必须保持相同。
            long hash=state.tradingState().businessStateHash();
            assertThat(apply(state,batch(line,first,2)).commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(state.tradingState().businessStateHash()).isEqualTo(hash);
            applied(state,mark(line,first.riskScanContinuation().instrumentId(),2));
            // 刷新旧币对后查询到的新工作令牌，应优先指向尚未扫描的其他币对。
            var next=work(state,line);
            assertThat(next.riskScanContinuation().instrumentId()).isNotEqualTo(first.riskScanContinuation().instrumentId());
            try(/* 从当前检查点快照恢复的第二个运行时，应用完全相同的后续消息比对结果。 */ var restored=TradingCoreRuntime.fromSnapshot(line,state.snapshot(500))) {
                assertThat(work(restored,line).riskScanContinuation()).isEqualTo(next.riskScanContinuation());
                // 用恢复后仍一致的新令牌构造的大预算完成请求。
                var finish=batch(line,next,64);
                applied(state,finish);applied(restored,finish);
                assertThat(state.tradingState().riskState().scans().values()).allMatch(/* 正在统计或检查的币对扫描记录，以其风险完成标记验证共享预算推进数量。 */ s->s.riskComplete());
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
                assertThat(com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(state.tradingState())).isEqualTo(funds);
            }
        }
    }

    /**
     * 将强平编号放到 long 上界，真实异步续扫应以整数溢出拒绝并回滚所有 Lane 变化；随后正常价格和续扫仍可执行，恢复运行时也得到相同结果。
     */
    @org.junit.jupiter.api.Test
    void asynchronousRiskIdOverflowRollsBackAllLaneChangesAndFollowingCommandWorks() {
        // 本用例需要真实强平和亏空的线性永续产品线。
        ProductLine line = ProductLine.LINEAR_PERPETUAL;
        try (/* 本用例创建的交易运行时，离开作用域时关闭线程等资源。 */ var state = new TradingCoreRuntime(line)) {
            applied(state, command(line, CoreMessageType.REGISTER_INSTRUMENT,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("1",
                            ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                            100_000, 50_000, 0, 0, 0, -1, 0))));
            applied(state, mark(line, "1", 1));
            for (long /* 当前充值、建仓或汇总余额的测试账户，各场景按指定集合分布到不同 Lane。 */ user : new long[]{7, 8}) {
                applied(state, command(line, CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100))));
                applied(state, command(line, CoreMessageType.PLACE_ORDER, user,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100 + user, "1",
                                user == 7 ? CoreOrderSide.SELL : CoreOrderSide.BUY, 100, 1, false,
                                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                                CoreTimeInForce.GTC, false, "overflow-" + user))));
            }
            applied(state, command(line, CoreMessageType.ADJUST_BALANCE, 8,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", -90))));
            // 仅功能测试将编号放到边界；通过随后正常的行情命令提交为恢复基线。
            state.runtimeState.setNextLiquidationId(Long.MAX_VALUE);
            applied(state, command(line, CoreMessageType.APPLY_MARK_PRICE,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 80, 2, TIME))));
            // 编号边界已经通过正常行情提交后的业务检查点，溢出拒绝应完整回滚到它。
            var before = state.tradingState();
            // 尝试在编号上界产生强平计划的风险续扫命令。
            var scan = command(line, CoreMessageType.CONTINUE_RISK_SCAN,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64)));
            // 本次命令的最终响应，溢出用例要求拒绝，普通辅助入口由调用方断言状态。
            var response = apply(state, scan);
            assertThat(response.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(response.resultCode()).isEqualTo(CoreResultCode.ARITHMETIC_OVERFLOW);
            assertThat(state.tradingState()).isEqualTo(before);
            assertThat(state.tradingState().businessStateHash()).isEqualTo(before.businessStateHash());
            try (/* 从当前检查点快照恢复的第二个运行时，应用完全相同的后续消息比对结果。 */ var restored = TradingCoreRuntime.fromSnapshot(line, state.snapshot(600))) {
                // 溢出回滚后能解除危险状态的新价格消息，原运行时与恢复运行时共同应用。
                var normalPrice = mark(line, "1", 3);
                applied(state, normalPrice); applied(restored, normalPrice);
                // 新价格后的正常风险续扫，验证失败命令没有污染下一命令的可复用状态。
                var normalScan = command(line, CoreMessageType.CONTINUE_RISK_SCAN,
                        TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64)));
                applied(state, normalScan); applied(restored, normalScan);
                assertThat(state.tradingState().riskState().liquidations()).isEmpty();
                assertThat(state.tradingState().businessStateHash()).isEqualTo(restored.tradingState().businessStateHash());
                assertThat(state.tradingState().users()).isEqualTo(before.users());
            }
        }
    }

    /**
     * 同步借用账户访问权和异步两条路径分别执行过期强平批次，应按失效动作统计而非应用撤单或平仓；恢复运行时的结果、用户和资金事实保持一致。
     * @param asynchronous true 使用真实异步完成路径，false 先借用 Owner 账户访问权再同步完成撮合
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void staleLiquidationActionDoesNotAbortBatchOrChangeFundsAfterRecovery(boolean asynchronous) {
        // 本用例需要真实强平和亏空的线性永续产品线。
        ProductLine line = ProductLine.LINEAR_PERPETUAL;
        try (/* 本用例创建的交易运行时，离开作用域时关闭线程等资源。 */ var state = new TradingCoreRuntime(line)) {
            applied(state, command(line, CoreMessageType.REGISTER_INSTRUMENT,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("1",
                            ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                            100_000, 50_000, 0, 0, 0, -1, 0))));
            applied(state, mark(line, "1", 1));
            for (long /* 当前充值、建仓或汇总余额的测试账户，各场景按指定集合分布到不同 Lane。 */ user : new long[]{1, 2, 7}) applied(state, command(line, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", user == 2 ? 10000 : 110))));
            applied(state, command(line, CoreMessageType.PLACE_ORDER, 2,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100, "1",
                            CoreOrderSide.SELL, 100, 20, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "maker"))));
            for (long /* 当前充值、建仓或汇总余额的测试账户，各场景按指定集合分布到不同 Lane。 */ user : new long[]{1, 7}) {
                applied(state, command(line, CoreMessageType.PLACE_ORDER, user,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100 + user, "1",
                                CoreOrderSide.BUY, 100, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "taker-" + user))));
                for (/* 每个强平目标账户的两笔只减仓挂单下标，用来生成确定的待撤订单编号。 */ int n = 0; n < 2; n++) applied(state, command(line, CoreMessageType.PLACE_ORDER, user,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(200 + user * 10 + n, "1",
                                CoreOrderSide.SELL, 110, 1, true, CoreMarginMode.CROSS, CorePositionSide.NET,
                                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "cancel-" + user + "-" + n))));
            }
            applied(state, command(line, CoreMessageType.APPLY_MARK_PRICE, TradingCommandCodec.encodeApplyMarkPrice(
                    new ApplyMarkPriceCommand("1", 1, 2, TIME))));
            while (work(state, line).riskScanPending()) applied(state, command(line, CoreMessageType.CONTINUE_RISK_SCAN,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64))));
            assertThat(work(state, line).actions()).hasSize(2);
            // 行情刷新之前取得的强平批动作，随后执行应全部计为失效。
            var stale=ExecuteLiquidationBatchCommand.fromWork(work(state,line),0,0);
            applied(state,command(line,CoreMessageType.APPLY_MARK_PRICE,TradingCommandCodec.encodeApplyMarkPrice(
                    new ApplyMarkPriceCommand("1",2,3,TIME))));
            // 执行前的资金检查量，风险场景用资金哈希，强平场景用含保险及亏空的资金总额。
            long funds=liquidationFunds(state);
            // 执行过期动作前的用户业务状态，结束后余额及持仓事实必须保持相同。
            var users=state.tradingState().users();
            // 包含旧动作的批执行消息，同步、异步及恢复路径使用同一份输入。
            var execute=command(line,CoreMessageType.EXECUTE_LIQUIDATION_BATCH,TradingCommandCodec.encodeExecuteLiquidationBatch(stale));
            try(/* 从当前检查点快照恢复的第二个运行时，应用完全相同的后续消息比对结果。 */ var restored=TradingCoreRuntime.fromSnapshot(line,state.snapshot(601))) {
                // 旧动作批次的最终响应，虽计失效动作，整条批命令仍应正常应用。
                var result=executeLiquidation(state,execute,asynchronous);
                assertThat(result.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                // 解码后的批执行统计，核对失效数、实际应用数和已处理撤单数。
                var summary=CoreLiquidationBatchResultCodec.decode(result.data());
                assertThat(summary.obsoleteActions()).isEqualTo(2);
                assertThat(summary.processedOrders()).isZero();
                assertThat(summary.appliedActions()).isZero();
                assertThat(apply(restored,execute).data()).isEqualTo(result.data());
                assertThat(liquidationFunds(state)).isEqualTo(funds);
                assertThat(state.tradingState().users()).isEqualTo(users);
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
            }
        }
    }

    /**
     * 两账户各两笔挂单，每批只处理一笔撤单，跨快照四批完成并逐批比对响应；再次发送旧动作应只计失效，已提交重放与恢复运行时的业务哈希和资金守恒结果一致。
     * @param asynchronous true 使用真实异步完成路径，false 先借用 Owner 账户访问权再同步完成撮合
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void liquidationBatchResumesAcrossAccountsAndSnapshotWithinCancellationBudget(boolean asynchronous) {
        // 本用例需要真实强平和亏空的线性永续产品线。
        ProductLine line = ProductLine.LINEAR_PERPETUAL;
        try (/* 本用例创建的交易运行时，离开作用域时关闭线程等资源。 */ var state = new TradingCoreRuntime(line)) {
            applied(state, command(line, CoreMessageType.REGISTER_INSTRUMENT,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("1",
                            ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                            100_000, 50_000, 0, 0, 0, -1, 0))));
            applied(state, mark(line, "1", 1));
            for (long /* 当前充值、建仓或汇总余额的测试账户，各场景按指定集合分布到不同 Lane。 */ user : new long[]{1, 2, 7}) applied(state, command(line, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", user == 2 ? 10000 : 110))));
            applied(state, command(line, CoreMessageType.PLACE_ORDER, 2,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100, "1",
                            CoreOrderSide.SELL, 100, 20, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "maker"))));
            for (long /* 当前充值、建仓或汇总余额的测试账户，各场景按指定集合分布到不同 Lane。 */ user : new long[]{1, 7}) {
                applied(state, command(line, CoreMessageType.PLACE_ORDER, user,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100 + user, "1",
                                CoreOrderSide.BUY, 100, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "taker-" + user))));
                for (/* 每个强平目标账户的两笔只减仓挂单下标，用来生成确定的待撤订单编号。 */ int n = 0; n < 2; n++) applied(state, command(line, CoreMessageType.PLACE_ORDER, user,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(200 + user * 10 + n, "1",
                                CoreOrderSide.SELL, 110, 1, true, CoreMarginMode.CROSS, CorePositionSide.NET,
                                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "cancel-" + user + "-" + n))));
            }
            applied(state, command(line, CoreMessageType.APPLY_MARK_PRICE, TradingCommandCodec.encodeApplyMarkPrice(
                    new ApplyMarkPriceCommand("1", 1, 2, TIME))));
            while (work(state, line).riskScanPending()) applied(state, command(line, CoreMessageType.CONTINUE_RISK_SCAN,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64))));
            assertThat(work(state, line).actions()).hasSize(2);
            // 执行前的资金检查量，风险场景用资金哈希，强平场景用含保险及亏空的资金总额。
            long funds = liquidationFunds(state);
            // 真正强平前保存的第一份完整动作列表，完成后还会重放它来验证失效处理。
            var firstWork = ExecuteLiquidationBatchCommand.fromWork(work(state, line), 0, 0);
            // 只给一笔撤单预算的首批强平消息，必须保存两笔动作的未完进度。
            var first = command(line, CoreMessageType.EXECUTE_LIQUIDATION_BATCH,
                    TradingCommandCodec.encodeExecuteLiquidationBatch(new ExecuteLiquidationBatchCommand(
                            firstWork.actions(), 1, 0, null, 0)));
            // 首批执行统计，应只撤一笔订单且两账户动作都仍待继续。
            var firstResult = CoreLiquidationBatchResultCodec.decode(executeLiquidation(state, first, asynchronous).data());
            assertThat(firstResult.processedOrders()).isEqualTo(1);
            assertThat(firstResult.pendingActions()).isEqualTo(2);
            try (/* 从当前检查点快照恢复的第二个运行时，应用完全相同的后续消息比对结果。 */ var restored = TradingCoreRuntime.fromSnapshot(line, state.snapshot(600))) {
                // 已经执行的撤单批次数，四笔挂单每批一笔，应恰好四批完成。
                int pages = 1;
                while (!work(state, line).actions().isEmpty()) {
                    assertThat(++pages).isLessThanOrEqualTo(4);
                    // 重新查询得到的剩余动作与游标，不能继续沿用上一批过期令牌。
                    var nextWork = ExecuteLiquidationBatchCommand.fromWork(work(state, line), 0, 0);
                    // 当前剩余动作构成的一笔撤单预算请求，原状态和恢复状态共同执行。
                    var next = command(line, CoreMessageType.EXECUTE_LIQUIDATION_BATCH,
                            TradingCommandCodec.encodeExecuteLiquidationBatch(new ExecuteLiquidationBatchCommand(
                                    nextWork.actions(), 1, 0, null, 0)));
                    // 原运行时本批执行的最终响应，需要与同步或恢复参照逐字节一致。
                    var actual = executeLiquidation(state, next, asynchronous);
                    // 恢复运行时处理同一批次得到的响应，与原运行时逐字节比较。
                    var recovered = apply(restored, next);
                    assertThat(actual.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                    assertThat(actual.data()).isEqualTo(recovered.data());
                    assertThat(CoreLiquidationBatchResultCodec.decode(actual.data()).processedOrders()).isEqualTo(1);
                }
                assertThat(pages).isEqualTo(4);
                // 所有强平结束后再次发送最初动作的消息，用来核对旧动作重放不产生新资金变化。
                var obsolete = command(line, CoreMessageType.EXECUTE_LIQUIDATION_BATCH,
                        TradingCommandCodec.encodeExecuteLiquidationBatch(firstWork));
                // 再次提交旧动作之前的快照字节，给已提交重放器作为相同起点。
                byte[] beforeObsolete = state.snapshot(602);
                // 重放最初旧动作的统计结果，应计两笔失效动作。
                var obsoleteResult = executeLiquidation(state, obsolete, asynchronous);
                assertThat(CoreLiquidationBatchResultCodec.decode(obsoleteResult.data()).obsoleteActions()).isEqualTo(2);
                assertThat(apply(restored, obsolete).data()).isEqualTo(obsoleteResult.data());
                try (/* 从相同快照开始的已提交交易重放器，用它核对旧动作执行后的业务哈希。 */ var replay = new CommittedTradeReplay(line, beforeObsolete)) {
                    replay.apply(CoreMessageCodec.encode(obsolete), TIME, obsolete.header().sourceSequence());
                    assertThat(replay.businessHash()).isEqualTo(state.snapshotBusinessStateHash());
                }
                assertThat(state.tradingState().businessStateHash()).isEqualTo(restored.tradingState().businessStateHash());
                assertThat(liquidationFunds(state)).isEqualTo(funds);
            }
        }
    }

    /**
     * 异步模式交统一完成辅助器；同步模式先激活并在五秒内取得 Owner 账户访问权，提交后补完待定撮合，再检查执行路径已经归还访问权。
     * @param state 当前测试交易运行时，接受风险、强平命令并提供可恢复快照
     * @param command 本次要执行的强平消息，包含消息头和已编码批次载荷
     * @param asynchronous true 使用真实异步完成路径，false 先借用 Owner 账户访问权再同步完成撮合
     */
    private static CoreResponse executeLiquidation(TradingCoreRuntime state, CoreMessage command, boolean asynchronous) {
        if (asynchronous) return apply(state, command);
        state.activate();
        // 准备阶段等待 Owner 账户访问权的单调时钟截止值，最多等待五秒。
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!state.runtimeState.tryAcquireOwnerLaneAccess()) {
            if (System.nanoTime() > deadline) throw new AssertionError("borrowed ownership timeout");
            Thread.onSpinWait();
        }
        // 本次命令的最终响应，溢出用例要求拒绝，普通辅助入口由调用方断言状态。
        CoreResponse response = state.apply(command);
        if (response.resultCode() == CoreResultCode.MATCHING_PENDING)
            response = CoreTestCompletion.completeMatchingSynchronously(state,
                    state.matchingSequence(command.header().commandId()), command.header().submittedAtEpochMillis(),
                    command.header().sourceSequence());
        try {
            // 运行时 ownerLaneAccess 的反射字段，确认同步执行结束已归还借用的账户访问权。
            var field = state.runtimeState.getClass().getDeclaredField("ownerLaneAccess");
            field.setAccessible(true);
            assertThat(field.getBoolean(state.runtimeState)).isFalse();
        } catch (/* 测试反射读取访问权标记失败的原因，包装成断言失败直接暴露。 */ ReflectiveOperationException failure) { throw new AssertionError(failure); }
        return response;
    }

    /**
     * 汇总所有用户 USDT 可用及冻结余额，加保险余额和清算对手余额，再减保险亏空，作为强平前后的资金守恒检查量。
     * @param state 当前测试交易运行时，接受风险、强平命令并提供可恢复快照
     */
    private static long liquidationFunds(TradingCoreRuntime state) {
        // 用于资金守恒汇总的当前业务状态快照。
        var core = state.tradingState();
        // 逐步汇总的用户余额及保险、清算对手项，最后扣减亏空。
        long total = 0;
        for (var /* 当前充值、建仓或汇总余额的测试账户，各场景按指定集合分布到不同 Lane。 */ user : core.users().values()) {
            // 当前用户 USDT 余额，把可用与冻结金额一起计入资金总额。
            var balance = user.balances().get("USDT");
            total = Math.addExact(total, Math.addExact(balance.availableUnits(), balance.lockedUnits()));

        }
        total = Math.addExact(total, core.treasuryState().insuranceBalances().getOrDefault("USDT", 0L));
        total = Math.addExact(total, core.treasuryState().clearingPnlBalances().getOrDefault("USDT", 0L));
        return Math.subtractExact(total, core.treasuryState().insuranceDeficits().getOrDefault("USDT", 0L));
    }

    /**
     * 发送并解码指定产品线的待执行强平工作查询，要求查询响应成功。
     * @param state 当前测试交易运行时，接受风险、强平命令并提供可恢复快照
     * @param line 当前参数化测试的产品线，所有配置、账户、命令和恢复状态必须保持一致
     */
    private CoreLiquidationWorkView work(TradingCoreRuntime state,ProductLine line) {
        // 本次命令的最终响应，溢出用例要求拒绝，普通辅助入口由调用方断言状态。
        var response=CoreTestCompletion.querySynchronously(state,command(line,CoreMessageType.LIQUIDATION_WORK_QUERY,
                CoreLiquidationWorkCodec.encodeQuery(line,CoreLiquidationWorkView.Purpose.EXECUTION,0,1000,1_048_576)));
        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        return CoreLiquidationWorkCodec.decodeWork(response.data());
    }
    /**
     * 根据当前查询工作的精确继续令牌及指定风险预算构造批执行消息，强平费率设为 0，撤单上限沿用协议默认值。
     * @param line 当前参数化测试的产品线，所有配置、账户、命令和恢复状态必须保持一致
     * @param work 查询得到的最新强平工作，包含动作及扫描继续令牌
     * @param budget 本批允许推进的风险扫描工作量
     */
    private CoreMessage batch(ProductLine line,CoreLiquidationWorkView work,int budget) {
        return command(line,CoreMessageType.EXECUTE_LIQUIDATION_BATCH,
                TradingCommandCodec.encodeExecuteLiquidationBatch(ExecuteLiquidationBatchCommand.fromWork(work,0,budget)));
    }
    /**
     * 创建价格为一百的测试行情；期权同时带指数价和同到期远期价，其他产品只需标记价。
     * @param line 当前参数化测试的产品线，所有配置、账户、命令和恢复状态必须保持一致
     * @param instrumentId 需要产生测试行情的币对编号
     * @param priceSequence 本币对的新行情序号，后续命令必须严格递增
     */
    private CoreMessage mark(ProductLine line,String instrumentId,long priceSequence) {
        return command(line,CoreMessageType.APPLY_MARK_PRICE,TradingCommandCodec.encodeApplyMarkPrice(
                line==ProductLine.OPTION ? new ApplyMarkPriceCommand(instrumentId,100,100,100,priceSequence,TIME)
                        :new ApplyMarkPriceCommand(instrumentId,100,priceSequence,TIME)));
    }
    /**
     * 以系统级用户范围 0 构造查询或命令消息，复用带明确用户参数的入口。
     * @param line 当前参数化测试的产品线，所有配置、账户、命令和恢复状态必须保持一致
     * @param type 本次查询或写命令的协议消息类型
     * @param payload 与消息类型对应的已编码业务载荷
     */
    private CoreMessage command(ProductLine line,CoreMessageType type,byte[] payload) {
        return command(line,type,0,payload);
    }
    /**
     * 分配递增来源序号和随机唯一命令编号，按强平工作查询或写命令选择消息头，并带上产品线、用户和固定基准时间。
     * @param line 当前参数化测试的产品线，所有配置、账户、命令和恢复状态必须保持一致
     * @param type 本次查询或写命令的协议消息类型
     * @param user 写命令的源用户编号，系统级控制或查询使用 0
     * @param payload 与消息类型对应的已编码业务载荷
     */
    private CoreMessage command(ProductLine line,CoreMessageType type,long user,byte[] payload) {
        // 为当前消息新分配的递增来源序号，同时用于消息头和测试时间偏移。
        long seq=++sequence;
        // 当前消息的随机唯一命令编号，供执行及恢复关联结果。
        var id=UUID.randomUUID();
        return new CoreMessage(type==CoreMessageType.LIQUIDATION_WORK_QUERY
                ? CoreMessageHeader.query(type,id,line,CommandSource.OPERATIONS,982,seq,user,TIME+seq,seq)
                : CoreMessageHeader.command(type,id,line,CommandSource.OPERATIONS,982,seq,user,TIME+seq,seq),payload);
    }
    /**
     * 通过真实异步完成辅助器取得写命令的最终业务响应，供测试自行检查成功或拒绝。
     * @param state 当前测试交易运行时，接受风险、强平命令并提供可恢复快照
     * @param message 需要交异步完成辅助器处理的完整命令消息
     */
    private static CoreResponse apply(TradingCoreRuntime state,CoreMessage message) {
        return CoreTestCompletion.applyAsynchronously(state, message);
    }
    /**
     * 发送写命令并要求最终状态为 APPLIED，断言失败时附消息类型和结果码。
     * @param state 当前测试交易运行时，接受风险、强平命令并提供可恢复快照
     * @param message 需要交异步完成辅助器处理的完整命令消息
     */
    private static void applied(TradingCoreRuntime state,CoreMessage message) {
        // 本次命令的最终响应，溢出用例要求拒绝，普通辅助入口由调用方断言状态。
        var response=apply(state,message);
        assertThat(response.commandStatus()).as("%s %s",message.header().messageType(),response.resultCode())
                .isEqualTo(ResponseStatus.APPLIED);
    }
}
