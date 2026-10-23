package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.order.ClusterCommandWindow;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 内部撤单后再下单的顺序及多标的批次资金测试。重点核对 Matcher 已成交、账户尚未消费旧成交时仍按真实订单簿处理前置撤单；覆盖六产品线，混合批次按当前模式执行，延迟成交用例显式使用 PIPELINED。
 */
class CoreSelfTradePrefixTest {
    /**
     * TIME：本测试原业务时间毫秒基点。
     * CAPITAL：每个用户每种初始化资产的一百万单位本金。
     */
    private static final long TIME = 1_700_000_000_000L, CAPITAL = 1_000_000;
    /** 本测试消息来源递增序号。 */
    private long sequence;

    /**
     * 组合全部产品线与原挂单此前成交零、一、二份三种情形。
     */
    static Stream<Arguments> productsAndPriorFill() {
        return Stream.of(ProductLine.values()).flatMap(/* 参数组合当前产品线 */ line -> Stream.of(0, 1, 2)
                .map(/* 参数组合当前先前成交数量 */ priorFill -> Arguments.of(line, priorFill)));
    }

    /**
     * 组合全部产品线、是否追加重复子单及是否用市价 taker。
     */
    static Stream<Arguments> productsAndBatchRejection() {
        return Stream.of(ProductLine.values()).flatMap(/* 参数组合当前产品线 */ line -> Stream.of(false, true)
                .flatMap(/* 参数组合是否加重复子单 */ rejectDuplicate -> Stream.of(false, true)
                        .map(/* 参数组合第二用户是否用市价 IOC */ marketTaker -> Arguments.of(line, rejectDuplicate, marketTaker))));
    }

    /**
     * 注册两标的、给两用户三种资产入账，三轮交替标的顺序批量成交；可追加重复项检查逐项拒绝，或用市价 taker。最后核对无残留预留、衍生品两持仓各六份及三资产双方总额守恒。
     * @param line 当前独立交易产品线
     * @param rejectDuplicate 是否在批次尾部附加与第一项相同的订单以触发拒绝
     * @param marketTaker 第二用户是否使用市价 IOC 而非限价 GTC
     */
    @ParameterizedTest @MethodSource("productsAndBatchRejection")
    void repeatedMixedSymbolBatchesKeepAssetMetadataAndReleaseBothUsersReservations(
            ProductLine line, boolean rejectDuplicate, boolean marketTaker) throws Exception {
        try (/* 本轮交易运行时资源，结束自动关闭 */ var core = new TradingCoreRuntime(line)) {
            // 按产品线选出的合约类型
            var type = ContractType.valueOf(line.contractTypeCode());
            for (String /* 当前注册或批次排列中的永久数字标的文本 */ symbol : new String[]{"1", "2"}) {
                // 标的一选 BTC、标的二选 ETH 的基础资产
                String base = symbol.equals("1") ? "BTC" : "ETH";
                applied(core, message(core, CoreMessageType.REGISTER_INSTRUMENT, 0,
                        TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                                symbol, base, "USDT", type.isInverse() ? base : "USDT", 1, 1,
                                type.isInverse() ? 1000 : 1,
                                (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                                0, 0, type.isDelivery() || type.isOption() ? TIME + 20_000 : 0,
                                type.isOption() ? 0 : -1, type.isOption() ? 100 : 0))));
                if (line != ProductLine.SPOT || marketTaker) applied(core, message(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                        TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                                ? new ApplyMarkPriceCommand(symbol, 100, 100, 100, 1, TIME)
                                : new ApplyMarkPriceCommand(symbol, 100, 1, TIME))));
            }
            for (long /* 当前入账、下单或核对资金的用户 */ user : new long[]{7, 8}) for (String /* 当前初始化的 BTC、ETH 或 USDT 资产 */ asset : new String[]{"BTC", "ETH", "USDT"})
                applied(core, message(core, CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, CAPITAL))));
            try (/* 用来发现 Owner 越界读取账户正文的既有测试保护器 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                for (/* 两标的交替排列的当前批次轮次，共三轮 */ int cycle = 0; cycle < 3; cycle++) {
                    for (long /* 当前入账、下单或核对资金的用户 */ user : new long[]{7, 8}) {
                        // 当前用户本轮短期批次意图列表
                        var orders = new java.util.ArrayList<PlaceOrderCommand>();
                        for (String /* 当前注册或批次排列中的永久数字标的文本 */ symbol : cycle % 2 == 0 ? new String[]{"1", "2"} : new String[]{"2", "1"}) {
                            // 当前子单业务 ID，或终态接收回调提供的 Core 序号
                            long id = 1000L + cycle * 100 + user * 10 + Integer.parseInt(symbol);
                            // 本条是否属于第二用户市价 taker，决定价格字段及 IOC
                            boolean market = user == 8 && marketTaker;
                            orders.add(new PlaceOrderCommand(id, symbol, user == 7 ? CoreOrderSide.SELL : CoreOrderSide.BUY,
                                    market && line != ProductLine.SPOT ? 0 : 100, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                                    market ? CoreOrderType.MARKET : CoreOrderType.LIMIT,
                                    market ? CoreTimeInForce.IOC : CoreTimeInForce.GTC, false, "mixed-" + id));
                        }
                        // 重复准入使预先尝试的批次回滚，再按顺序执行合法前缀并给重复项拒绝。
                        if (rejectDuplicate) orders.add(orders.getFirst());
                        // 当前批次或提交回调提供的原最终响应
                        var response = CoreTestCompletion.applyAsynchronously(core, message(core, CoreMessageType.PLACE_ORDER_BATCH,
                                user, TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders))));
                        assertThat(response.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                        // 解码的批次逐项响应，合法两项在前，可选重复拒绝在末尾
                        var items = TradingOrderBatchCodec.decodeResult(response.data()).items();
                        assertThat(items).hasSize(rejectDuplicate ? 3 : 2);
                        if (rejectDuplicate) assertThat(items.getLast().status()).isEqualTo(ResponseStatus.REJECTED);
                        assertThat(items.subList(0, 2)).allSatisfy(/* 当前合法批次子项响应，核对应用成功及成交条数 */ item -> {
                            assertThat(item.status()).isEqualTo(ResponseStatus.APPLIED);
                            assertThat(item.executions()).hasSize(user == 8 ? 1 : 0);
                        });
                    }
                }
            }
            // 按资产把双方可用加冻结精确汇总的对照表
            Map<String, Long> totals = new HashMap<>();
            for (long /* 当前入账、下单或核对资金的用户 */ user : new long[]{7, 8}) {
                // 当前用户已提交账户视图
                var account = CoreTestCompletion.userState(core, user);
                assertThat(account.reservations()).isEmpty();
                for (var /* 当前用户待计入双方总额的资产余额视图 */ balance : account.balances()) totals.merge(balance.asset(),
                        Math.addExact(balance.availableUnits(), balance.lockedUnits()), Math::addExact);
                if (line == ProductLine.SPOT) assertThat(account.positions()).isEmpty();
                else assertThat(account.positions()).hasSize(2).allSatisfy(/* 混合批次结束后当前用户每标的持仓，预期正/负六 */ position ->
                        assertThat(position.signedQuantitySteps()).isEqualTo(user == 7 ? -6 : 6));
            }
            assertThat(totals).containsEntry("BTC", CAPITAL * 2).containsEntry("ETH", CAPITAL * 2)
                    .containsEntry("USDT", CAPITAL * 2);
        }
    }

    /**
     * PIPELINED 下让 maker 账户延迟消费先前成交，再提交该用户反向新单触发前置撤单；按当前订单簿处理部分/全部已成交旧单，放行后收齐原终态，不发生 Owner 账户交接。最后核对旧单消失、新单仍 OPEN、预留和持仓及双方资产守恒，真实未知撤单仍拒绝。
     * @param line 当前独立交易产品线
     * @param priorFill 原 maker 两份挂单此前已成交数量，取零、一或二
     */
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    @ParameterizedTest @MethodSource("productsAndPriorFill")
    void cancelBeforePlaceHandlesRestingAndAlreadyMatchedOwnOrders(ProductLine line, int priorFill) throws Exception {
        // 放行 maker 账户以继续消费旧成交和后续准入的门闩，退出路径确保放行
        var releaseMaker = new CountDownLatch(1);
        try (/* 本轮交易运行时资源，结束自动关闭 */ var core = new TradingCoreRuntime(line)) {
            // 按产品线选出的合约类型
            var type = ContractType.valueOf(line.contractTypeCode());
            applied(core, message(core, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "1", "BTC", "USDT", type.isInverse() ? "BTC" : "USDT", 1, 1, type.isInverse() ? 1000 : 1,
                            (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                            0, 0, type.isDelivery() || type.isOption() ? TIME + 20_000 : 0, type.isOption() ? 0 : -1,
                            type.isOption() ? 100 : 0))));
            if (line != ProductLine.SPOT) applied(core, message(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                            ? new ApplyMarkPriceCommand("1", 100, 100, 100, 1, TIME)
                            : new ApplyMarkPriceCommand("1", 100, 1, TIME))));
            // 从八开始寻找与 maker 不同 Lane 的用户候选
            // 原挂卖单的用户七
            long maker = 7, candidate = 8;
            while (core.runtimeState.accountLaneId(candidate) == core.runtimeState.accountLaneId(maker)) candidate++;
            // 最终选定、与 maker 归属不同 Lane 的对手用户
            long taker = candidate;
            for (long /* 当前入账、下单或核对资金的用户 */ user : new long[]{maker, taker}) for (String /* 当前初始化的 BTC、ETH 或 USDT 资产 */ asset : new String[]{"BTC", "USDT"})
                applied(core, message(core, CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, CAPITAL))));
            applied(core, place(core, maker, 101, CoreOrderSide.SELL, 2));
            // 原下单后账户交接代号，测试轨迹不得新增 Owner 交接
            long handoff = core.runtimeState.laneHandoffEpoch();
            // 按 Core 序号收集各原在途命令终态状态的表
            Map<Long, ResponseStatus> terminals = new HashMap<>();
            try (/* 用来发现 Owner 越界读取账户正文的既有测试保护器 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                if (priorFill > 0) {
                    // 通知 maker 阻塞查询已经进入所属 Lane 的门闩
                    var entered = new CountDownLatch(1);
                    assertThat(core.runtimeState.dispatchAccountQuery(core.runtimeState.topology().accountLaneMask(maker), /* 正在执行 maker 阻塞查询的真实账户 Lane */ lane -> {
                        entered.countDown();
                        try {
                            if (!releaseMaker.await(5, TimeUnit.SECONDS)) throw new AssertionError("maker Lane gate timed out");
                        } catch (/* 阻塞查询等待被中断，转为测试失败 */ InterruptedException failure) { throw new AssertionError(failure); }
                    })).isTrue();
                    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                    // 先前让原 maker 挂单成交部分或全部的对手买入消息
                    var trade = place(core, taker, 102, CoreOrderSide.BUY, priorFill);
                    assertThat(submit(core, trade).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                    // 先前成交在原撮合环中的命令槽，观察已发布与已结算完成的区别
                    var tradeSlot = core.pendingMatching().get(core.matchingSequence(trade.header().commandId()));
                    // 当前等待 Matcher 发布或收齐终态的五秒单调时钟截止值
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!tradeSlot.settlementEvent().ready()) {
                        core.matchingProgress.drainMatchingCompletions();
                        if (System.nanoTime() >= deadline) throw new AssertionError("Matcher did not publish the preceding trade");
                        Thread.onSpinWait();
                    }
                    // Matcher 已经发布成交，但 maker 账户仍被门闩挡住，尚不能应用这份成交。
                    assertThat(tradeSlot.settlementEvent().complete()).isFalse();
                }
                // maker 本人反向新单，需先处理自己原订单的前置撤单
                var next = place(core, maker, 103, CoreOrderSide.BUY, 1);
                assertThat(submit(core, next).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                // 该反向新单原 Core 序号，用于核对收集的终态
                long nextSequence = core.matchingSequence(next.header().commandId());
                // 放行 maker 之前先排入新准入；Lane 的独立邮箱可能先看到它，不能假定旧成交已被账户应用。
                releaseMaker.countDown();
                // 当前等待 Matcher 发布或收齐终态的五秒单调时钟截止值
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (core.pendingMatching().size() != 0) {
                    core.commits.commitReadyMatching(16, TIME, sequence, false,
                            (/* 当前子单业务 ID，或终态接收回调提供的 Core 序号 */ id, /* 当前批次或提交回调提供的原最终响应 */ response) -> terminals.put(id, response.commandStatus()));
                    if (System.nanoTime() >= deadline) throw new AssertionError("self-trade prefix did not finish");
                    Thread.onSpinWait();
                }
                assertThat(terminals.get(nextSequence)).isEqualTo(ResponseStatus.APPLIED);
                assertThat(terminals.values()).allMatch(/* 每条已收集命令的最终业务状态，均应 APPLIED */ status -> status == ResponseStatus.APPLIED);
                assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            } finally { releaseMaker.countDown(); }
            // 按资产把双方可用加冻结精确汇总的对照表
            Map<String, Long> totals = new HashMap<>();
            for (long /* 当前入账、下单或核对资金的用户 */ user : new long[]{maker, taker}) {
                // 最后核对资金、预留和持仓的当前用户已提交视图
                var state = CoreTestCompletion.userState(core, user);
                for (var /* 当前用户待计入双方总额的资产余额视图 */ balance : state.balances()) totals.merge(balance.asset(),
                        Math.addExact(balance.availableUnits(), balance.lockedUnits()), Math::addExact);
                assertThat(state.reservations()).hasSize(user == maker ? 1 : 0);
                // 当前用户全部带符号持仓数量之和
                long quantity = state.positions().stream().mapToLong(CorePositionView::signedQuantitySteps).sum();
                assertThat(quantity).isEqualTo(line == ProductLine.SPOT ? 0 : user == maker ? -priorFill : priorFill);
            }
            assertThat(totals).containsEntry("BTC", CAPITAL * 2).containsEntry("USDT", CAPITAL * 2);
            assertThat(CoreTestCompletion.orderState(core, maker, 101)).isNull();
            // 对真实不存在订单号的显式撤单，必须仍被拒绝
            var missingCancel = message(core, CoreMessageType.CANCEL_ORDER, maker,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(999_999)));
            assertThat(CoreTestCompletion.applyAsynchronously(core, missingCancel).commandStatus())
                    .isEqualTo(ResponseStatus.REJECTED);
            assertThat(CoreTestCompletion.orderState(core, maker, 103).status()).isEqualTo("OPEN");
        } finally { releaseMaker.countDown(); }
    }

    /**
     * 用临时命令窗口准备解码及独立准入判断，进入异步命令范围提交原消息后退出并释放 Owner 访问权；返回仍可能 MATCHING_PENDING，外层再推进终态。
     * @param core 本产品线原运行时
     * @param message 当前待原样提交的原协议消息
     */
    private CoreResponse submit(TradingCoreRuntime core, CoreMessage message) {
        // 仅供这次原消息解码与独立准入准备的短期命令窗口
        var window = new ClusterCommandWindow();
        // 本条输入是否满足既有窗口内独立撮合准入条件
        boolean independent = core.prepareClusterPipelineScope(message, window);
        core.runtimeState.enterAsynchronousCommandScope();
        try {
            return core.applyDecodedCommand(message, TIME, message.header().sourceSequence(),
                    window.decodedIfPresent(message), independent);
        } finally {
            core.runtimeState.exitAsynchronousCommandScope();
            core.runtimeState.releaseOwnerLaneAccess();
        }
    }

    /**
     * 沿原异步完成辅助提交消息，要求业务最终状态 APPLIED。
     * @param core 本产品线原运行时
     * @param message 当前待原样提交的原协议消息
     */
    private void applied(TradingCoreRuntime core, CoreMessage message) {
        assertThat(CoreTestCompletion.applyAsynchronously(core, message).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
    }
    /**
     * 构造指定用户、方向和数量的标的一限价 GTC 消息，沿本测试原来源序号发送。
     * @param core 本产品线原运行时
     * @param user 原请求所属用户
     * @param id 本次原订单号
     * @param side 原订单买卖方向
     * @param quantity 原订单数量步数
     */
    private CoreMessage place(TradingCoreRuntime core, long user, long id, CoreOrderSide side, long quantity) {
        return message(core, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(id, "1", side, 100, quantity, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "prefix-" + id)));
    }
    /**
     * 为当前产品线构造固定业务时间、递增来源及可复现 UUID 的原消息。
     * @param core 本产品线原运行时
     * @param type 本次原消息类型
     * @param user 原请求所属用户
     * @param payload 本次原消息已编码载荷
     */
    private CoreMessage message(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        // 为本条原消息递增的来源序号
        long next = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(424, next), core.productLine,
                CommandSource.GATEWAY, 424, next, user, TIME, next), payload);
    }
}
