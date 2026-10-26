package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 撮合接入账户 Lane 与原 System Ledger 的集成测试，检查 Owner 读取边界、订单删除回执优先级、手续费和自成交预防。这里的资金账本不同于只跟踪序号的 MatcherEvidenceLedger；测试使用本地运行时，不启动真实 Cluster。
 */
class CoreMatcherLedgerTest {
    /** 本测试实例递增的命令源序号，同时用于 UUID 和模拟位置。 */
    private long sequence;
    /** 当前测试统一使用的固定毫秒业务时间。 */
    private static final long TIME = 1_700_000_000_000L;

    /**
     * 先建立活动订单，在 Owner 读取守卫中反射写入 changedOrders 的删除值，模拟 Lane 删除回执已收集而旧路由尚未退休，要求运行时和响应读取都以删除结果为准；并未人为阻塞真实 Lane 来制造该窗口。
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void collectedOrderDeletionWinsBeforeOldActiveRouteIsRetired() throws Exception {
        try (/* 本场景创建并在结束时关闭的 U 本位永续运行时。 */ var state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applied(state, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "1", "BTC", "USDT", "USDT", 1, 1, 1,
                            new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                            0, 0, 0, -1, 0), ProductLine.LINEAR_PERPETUAL));
            applied(state, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME), ProductLine.LINEAR_PERPETUAL));
            applied(state, CoreMessageType.ADJUST_BALANCE, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100_000)));
            applied(state, CoreMessageType.PLACE_ORDER, 1001, TradingCommandCodec.encodePlaceOrder(order(1, CoreOrderSide.BUY, 99, 1)));
            try (/* 测试期间禁止 Owner 越过账户读取边界的可关闭守卫。 */ var guard = new OwnerAccountReadGuard(state.runtimeState)) {
                assertThat(state.activeOrderIndex().activeOrderRoute(1).userId()).isEqualTo(1001);
                assertThatThrownBy(() -> state.runtimeOrder(1)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> state.runtimeOrder(999)).isInstanceOf(IllegalStateException.class);
                // 模拟Lane删除回执已收集、Owner活跃路由尚未发布的提交窗口。
                var field = com.surprising.aeron.service.core.TradingAccountRuntime.class.getDeclaredField("changedOrders");
                field.setAccessible(true);
                // 当前运行时订单变更缓冲，用 null 值模拟已收集删除。
                var changes = field.get(state.runtimeState);
                // 变更缓冲 put(long,Object) 的反射方法句柄。
                var put = changes.getClass().getDeclaredMethod("put", long.class, Object.class);
                put.setAccessible(true);
                put.invoke(changes, 1L, (Object) null);
                assertThat(state.activeOrderIndex().activeOrderRoute(1)).isNotNull();
                assertThat(state.runtimeState.hasChangedOrder(1)).isTrue();
                assertThat(state.runtimeOrder(1)).isNull();
                assertThat(state.responseOrder(1)).isNull();
            }
        }
    }

    /**
     * 要求原 System Ledger 已启动且 Owner 不能直接读取权威财库，在读取守卫下完成单笔/批量成交和撤单，最后核对手续费六、用户总额十九万九千九百九十四、活动订单及预留清空，并确认回调已完成。
     */
    @Test
    void matchingAndBatchFeesUseOriginalLedgerAndAccountFactsRemainConserved() throws Exception {
        try (/* 本场景创建并在结束时关闭的 U 本位永续运行时。 */ var state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applied(state, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "1", "BTC", "USDT", "USDT", 1, 1, 1,
                            new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                            10_000, 10_000, 0, -1, 0), ProductLine.LINEAR_PERPETUAL));
            applied(state, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME), ProductLine.LINEAR_PERPETUAL));
            for (long /* 本轮测试用户，用于注入余额或最终核对其预留已清空。 */ user : new long[]{1001, 1002}) applied(state, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100_000)));
            assertThat(state.runtimeState.systemLedgerStarted()).isTrue();
            assertThat(state.runtimeState.treasury().fee(state.identities.assetId("USDT"))).isZero();
            try (/* 测试期间禁止 Owner 越过账户读取边界的可关闭守卫。 */ var guard = new OwnerAccountReadGuard(state.runtimeState)) {
                applied(state, CoreMessageType.PLACE_ORDER, 1002, TradingCommandCodec.encodePlaceOrder(order(1, CoreOrderSide.SELL, 100, 3)));
                applied(state, CoreMessageType.PLACE_ORDER, 1001, TradingCommandCodec.encodePlaceOrder(order(2, CoreOrderSide.BUY, 100, 1)));
                // 批量成交或当前完成辅助器返回的命令响应。
                var response = applied(state, CoreMessageType.PLACE_ORDER_BATCH, 1001,
                        TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(order(3, CoreOrderSide.BUY, 100, 1), order(4, CoreOrderSide.BUY, 100, 1)))));
                assertThat(TradingOrderBatchCodec.firstNonAppliedItem(response, 2)).isEqualTo(-1);
                applied(state, CoreMessageType.PLACE_ORDER, 1002, TradingCommandCodec.encodePlaceOrder(order(5, CoreOrderSide.SELL, 110, 1)));
                applied(state, CoreMessageType.CANCEL_ORDER_BATCH, 1002,
                        TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(new CancelOrderCommand(5)))));
            }
            // 全部待测命令完成后导出的业务状态视图。
            var trading = state.tradingState();
            assertThat(trading.treasuryState().feeBalances()).containsEntry("USDT", 6L);
            assertThat(trading.users().values().stream().mapToLong(/* 正在合计其 USDT 可用与冻结总额的用户业务值。 */ u -> u.totalUnits("USDT")).sum()).isEqualTo(199_994);
            assertThat(trading.orders()).isEmpty();
            assertThat(trading.users().values()).allSatisfy(/* 本轮测试用户，用于注入余额或最终核对其预留已清空。 */ user -> assertThat(user.reservations()).isEmpty());
            state.assertClusterCallbackComplete();
        }
    }

    /**
     * 在 Owner 读取守卫下提交同用户相交反向单，要求旧单撤销而只剩新单，用户总额十万、无自成交持仓且无手续费，最后确认回调完成。
     */
    @Test
    void laneAdmissionCancelsOwnCrossingOrderWithoutOwnerBusinessReads() throws Exception {
        try (/* 本场景创建并在结束时关闭的 U 本位永续运行时。 */ var state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applied(state, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "1", "BTC", "USDT", "USDT", 1, 1, 1,
                            new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                            0, 0, 0, -1, 0), ProductLine.LINEAR_PERPETUAL));
            applied(state, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME), ProductLine.LINEAR_PERPETUAL));
            applied(state, CoreMessageType.ADJUST_BALANCE, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100_000)));
            try (/* 测试期间禁止 Owner 越过账户读取边界的可关闭守卫。 */ var guard = new OwnerAccountReadGuard(state.runtimeState)) {
                applied(state, CoreMessageType.PLACE_ORDER, 1001, TradingCommandCodec.encodePlaceOrder(order(1, CoreOrderSide.BUY, 100, 1)));
                applied(state, CoreMessageType.PLACE_ORDER, 1001, TradingCommandCodec.encodePlaceOrder(order(2, CoreOrderSide.SELL, 100, 1)));
            }
            // 全部待测命令完成后导出的业务状态视图。
            var trading = state.tradingState();
            assertThat(trading.orders()).containsOnlyKeys(2L);
            assertThat(trading.users().get(1001L).totalUnits("USDT")).isEqualTo(100_000);
            assertThat(trading.users().get(1001L).positions()).isEmpty();
            assertThat(trading.treasuryState().feeBalances().getOrDefault("USDT", 0L)).isZero();
            state.assertClusterCallbackComplete();
        }
    }

    /**
     * 构造币对一的全仓净持仓限价 GTC 普通订单，客户号由编号生成。
     * @param id 普通订单业务编号
     * @param side 买入或卖出方向
     * @param price 订单限价
     * @param qty 订单原数量
     */
    private PlaceOrderCommand order(long id, CoreOrderSide side, long price, long qty) {
        return new PlaceOrderCommand(id, "1", side, price, qty, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "ledger-" + id);
    }

    /**
     * 递增本测试源序号并构造固定产品线运营命令，经原异步完成辅助器推进后要求命令状态 APPLIED。
     * @param state 当前 U 本位永续测试运行时
     * @param type 本条协议命令类型
     * @param user 本条请求关联用户编号
     * @param payload 已经编码的业务载荷
     */
    private CoreResponse applied(TradingCoreRuntime state, CoreMessageType type, long user, byte[] payload) {
        // 本次递增后的命令源序号，写入 UUID 和请求头。
        long seq = ++sequence;
        // 携带固定来源和当前源序号的运营命令帧。
        var message = new CoreMessage(CoreMessageHeader.command(type, new UUID(927, seq), ProductLine.LINEAR_PERPETUAL,
                CommandSource.OPERATIONS, 927, seq, user, TIME, seq), payload);
        // 批量成交或当前完成辅助器返回的命令响应。
        var response = CoreTestCompletion.applyAsynchronously(state, message);
        assertThat(response.commandStatus()).as("%s: %s", type, response.resultCode()).isEqualTo(ResponseStatus.APPLIED);
        return response;
    }
}
