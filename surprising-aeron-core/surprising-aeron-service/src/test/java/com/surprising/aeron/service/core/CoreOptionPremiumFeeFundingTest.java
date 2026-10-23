package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 期权买卖开仓再平仓的权利金与手续费测试，分别覆盖中途保留原运行时和从内存快照恢复后平仓。核对指定两用户余额、手续费、冻结和持仓终态，不执行永续资金费，也不启动真实 Cluster。
 */
class CoreOptionPremiumFeeFundingTest {
    /** 本测试实例的递增命令源序号，同时推进请求时间与模拟位置。 */
    private long sequence;
    /** 每个测试用户调整入账的初始 USDT 最小单位总额。 */
    private static final long WALLET = 10_000_000_000_000L;

    /**
     * 用带费用的期权配置进行一次买入开仓和较优卖出平仓，核对用户一减少三十万、用户二增加十六万、财库收十四万，且两用户冻结和预留清空、持仓归零，总额仍为两份初始钱包。finally 关闭原实例及可选恢复实例。
     * @param restoreBeforeClose 是否在开仓后先从内存快照恢复新运行时再平仓
     */
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void betterSellFillPaysFeeFromPremiumWithoutLosingFunds(boolean restoreBeforeClose) {
        // 本测试首次创建的期权运行时，无论是否恢复都在 finally 关闭。
        var original = new TradingCoreRuntime(ProductLine.OPTION);
        // 当前执行实例，必要时切换为快照恢复后的新实例。
        TradingCoreRuntime runtime = original;
        try {
            // 固定期权参数、手续费、风险档和订单支持范围的注册命令。
            var registration = new RegisterInstrumentCommand("1", "BTC", "USDT", "USDT",
                    10_000, 10_000_000, 1, 10_000, 5_000, 200, 500, 1_800_000_000_000L, 0, 500_000,
                    100_000_000, 1_000_000_000_000_000L, 0, 1_000_000_000_000_000L,
                    List.of(new CoreRiskLimitBracket(1, 0, 1_000_000_000_000_000L, 100_000_000, 10_000, 5_000)),
                    1, true, true, true, 0b11, 0b1111, 1);
            apply(runtime, CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(registration));
            for (long /* 当前初始化钱包或核对终态的用户编号。 */ user : List.of(101L, 202L)) apply(runtime, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", WALLET)));
            apply(runtime, CoreMessageType.APPLY_MARK_PRICE, 0, TradingCommandCodec.encodeApplyMarkPrice(
                    new ApplyMarkPriceCommand("1", 10_000, 500_000, 505_000, 1, 1_700_000_000_000L)));
            place(runtime, 202, 1, CoreOrderSide.SELL, 10_010, CoreOrderType.LIMIT, false);
            place(runtime, 101, 2, CoreOrderSide.BUY, 0, CoreOrderType.MARKET, false);
            if (restoreBeforeClose) runtime = TradingCoreRuntime.fromSnapshot(ProductLine.OPTION, original.snapshot(71));
            place(runtime, 202, 3, CoreOrderSide.BUY, 9_990, CoreOrderType.LIMIT, true);
            place(runtime, 101, 4, CoreOrderSide.SELL, 0, CoreOrderType.MARKET, true);
            // 平仓结束后用于核对金额和终态的交易业务视图。
            var state = runtime.tradingState();
            assertThat(state.user(101).totalUnits("USDT")).isEqualTo(WALLET - 300_000);
            assertThat(state.user(202).totalUnits("USDT")).isEqualTo(WALLET + 160_000);
            assertThat(state.treasuryState().feeBalances().get("USDT")).isEqualTo(140_000);
            for (long /* 当前初始化钱包或核对终态的用户编号。 */ user : List.of(101L, 202L)) {
                assertThat(state.user(user).balances().get("USDT").lockedUnits()).isZero();
                assertThat(state.user(user).reservations()).isEmpty();
                assertThat(state.user(user).positions().values()).allMatch(/* 当前用户的一条持仓，断言其数量为零。 */ position -> position.signedQuantitySteps() == 0);
            }
            assertThat(state.user(101).totalUnits("USDT") + state.user(202).totalUnits("USDT")
                    + state.treasuryState().feeBalances().get("USDT")).isEqualTo(2 * WALLET);
        } finally { if (runtime != original) runtime.close(); original.close(); }
    }

    /**
     * 构造币对一、数量一的期权限价或市价单；市价使用 IOC，限价使用 GTC，reduce 参数决定是否只减仓。
     * @param runtime 本次命令发送到的原实例或恢复实例
     * @param user 期权订单或余额调整所属用户编号
     * @param id 订单业务编号
     * @param side 买入或卖出方向
     * @param price 限价单价格，市价请求使用零
     * @param type 限价或市价订单类型
     * @param reduce 是否只允许减少已有持仓
     */
    private void place(TradingCoreRuntime runtime, long user, long id, CoreOrderSide side, long price, CoreOrderType type, boolean reduce) {
        apply(runtime, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                id, "1", side, price, 1, reduce, CoreMarginMode.CROSS, CorePositionSide.NET,
                type, type == CoreOrderType.MARKET ? CoreTimeInForce.IOC : CoreTimeInForce.GTC, false, "option-fee-" + id)));
    }

    /**
     * 构造递增源序号命令，先等待最多五秒的直接控制续步；若撮合未完成再调用原同步完成辅助器，最后要求命令已应用。五秒截止点只约束这里的直接命令轮询，不替代撮合辅助器自身约束。
     * @param runtime 本次命令发送到的原实例或恢复实例
     * @param type 协议命令类型
     * @param user 期权订单或余额调整所属用户编号
     * @param payload 已编码命令载荷
     */
    private void apply(TradingCoreRuntime runtime, CoreMessageType type, long user, byte[] payload) {
        // 本次递增后的源序号，用于请求头、时间和模拟位置。
        long seq = ++sequence;
        // 使用随机 UUID 构造的期权运营命令帧。
        var message = new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(), ProductLine.OPTION,
                CommandSource.OPERATIONS, 991, seq, user, 1_700_000_000_000L + seq, seq), payload);
        // 当前原始或续步完成后的命令响应。
        var result = runtime.apply(message);
        // 直接控制命令轮询的五秒纳秒截止点。
        long directDeadline = System.nanoTime() + 5_000_000_000L;
        while (result == null && runtime.directCommand.active() && System.nanoTime() < directDeadline) {
            result = runtime.directCommand.poll();
            if (result == null) Thread.onSpinWait();
        }
        if (result == null && runtime.directCommand.active()) {
            throw new AssertionError("Direct command timed out: " + type);
        }
        if (result.resultCode() == CoreResultCode.MATCHING_PENDING)
            result = CoreTestCompletion.completeMatchingSynchronously(runtime, runtime.matchingSequence(message.header().commandId()),
                    1_700_000_000_000L + seq, seq);
        assertThat(result.commandStatus()).as(type + " " + result.resultCode()).isEqualTo(ResponseStatus.APPLIED);
    }
}
