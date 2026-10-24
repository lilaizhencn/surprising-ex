package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreCancelAllAfterKey;
import com.surprising.aeron.model.CoreCancelAllAfterState;
import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.service.account.AccountControlTask;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.account.DerivativeAccountCommandProcessor;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.state.RuntimeTestStateTransitions;
import com.surprising.aeron.service.snapshot.state.*;
import com.surprising.aeron.service.snapshot.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.snapshot.state.TradingStateSnapshotCodec;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static com.surprising.aeron.service.matcher.MatcherEventFixtures.trade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通过冷状态转换辅助覆盖订单冻结、撤单解冻、手续费、风险限额及衍生品账户规则。部分保证金与杠杆用例另启动账户 Lane 比较控制任务结果；撮合事件由测试显式提供，不是网络或完整撮合器端到端测试。
 */
class RuntimeStateBusinessRulesTest {

    /** 复用既有运行时业务转换的冷状态测试辅助，返回新的查询状态用于逐步断言。 */
    private final RuntimeTestStateTransitions reducer = new RuntimeTestStateTransitions();

    /**
     * 现货买单由核心按意图算出报价资产冻结一百，撤单后全部解冻；原余额总额不变，订单与预留终态一致。辅助的预留入参三千不直接决定冻结量。
     */
    @Test
    void reservesAndReleasesSpotBuyQuoteAssetAtomically() {
        // 本用例已经给用户一百零一注资的冷状态，后续转换不能改写它
        TradingCoreState funded = funded(ProductLine.SPOT, "USDT", 10_000);

        // 本用例接受委托后的冷状态，保留订单和预留事实供断言
        TradingCoreState placed = reducer.placeOrder(funded, 101,
                order(1, CoreOrderSide.BUY));
        // 已撤单且剩余预留解冻后的冷状态
        TradingCoreState canceled = reducer.cancelOrder(placed, 101, new CancelOrderCommand(1));

        assertBalance(placed, "USDT", 9_900, 100);
        assertThat(placed.user(101).totalUnits("USDT")).isEqualTo(10_000);
        assertThat(placed.user(101).reservations().get(1L).remainingUnits()).isEqualTo(100);
        assertThat(placed.order(1).status()).isEqualTo(CoreOrderStatus.OPEN);
        assertBalance(canceled, "USDT", 10_000, 0);
        assertThat(canceled.user(101).reservations().get(1L).remainingUnits()).isZero();
        assertThat(canceled.order(1).status()).isEqualTo(CoreOrderStatus.CANCELED);
    }

    /**
     * 现货卖单冻结基础资产十，显式构造以报价资产预留的错误准入结果应按资产不符拒绝。
     */
    @Test
    void spotSellReservesBaseAssetAndRejectsWrongAsset() {
        // 本用例已经给用户一百零一注资的冷状态，后续转换不能改写它
        TradingCoreState funded = funded(ProductLine.SPOT, "BTC", 20);
        // 本用例接受委托后的冷状态，保留订单和预留事实供断言
        TradingCoreState placed = reducer.placeOrder(funded, 101,
                order(2, CoreOrderSide.SELL));

        assertBalance(placed, "BTC", 10, 10);
        // 本用例需要拒绝的委托意图，错误预留类型或资产另由 resolved 显式附加
        PlaceOrderCommand invalid = order(3, CoreOrderSide.SELL);
        assertThatThrownBy(() -> reducer.placeOrder(funded, 101,
                resolved(funded, invalid, ReservationKind.SPOT_ASSET, "USDT", 0, 0)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定代码核对拒绝原因 */ exception -> assertThat(exception.code()).isEqualTo("INVALID_SPOT_RESERVATION_ASSET"));
    }

    /**
     * 五条衍生品线都必须使用结算资产保证金预留；错误地传入现货预留类型应被拒绝，正确下单只移动可用和锁定、不改变总额。
     * @param productLine 当前参数化用例或冷状态所属的独立产品线
     */
    @ParameterizedTest
    @MethodSource("derivativeProductLines")
    void allDerivativeLinesRequireMarginReservation(ProductLine productLine) {
        // 反向期货采用 BTC，其余衍生品采用 USDT 的结算资产
        String settleAsset = productLine == ProductLine.INVERSE_PERPETUAL
                || productLine == ProductLine.INVERSE_DELIVERY ? "BTC" : "USDT";
        // 本用例已经给用户一百零一注资的冷状态，后续转换不能改写它
        TradingCoreState funded = funded(productLine, settleAsset, 10_000);
        // 本用例接受委托后的冷状态，保留订单和预留事实供断言
        TradingCoreState placed = reducer.placeOrder(funded, 101,
                order(10 + productLine.ordinal(), CoreOrderSide.BUY));

        assertThat(placed.user(101).totalUnits(settleAsset)).isEqualTo(10_000);
        assertThat(placed.user(101).balances().get(settleAsset).lockedUnits()).isPositive();
        assertThat(placed.user(101).balances().get(settleAsset).lockedUnits()).isLessThan(2_000);
        // 本用例需要拒绝的委托意图，错误预留类型或资产另由 resolved 显式附加
        PlaceOrderCommand invalid = order(100 + productLine.ordinal(), CoreOrderSide.BUY);
        assertThatThrownBy(() -> reducer.placeOrder(funded, 101,
                resolved(funded, invalid, ReservationKind.SPOT_ASSET, settleAsset, 0, 0)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定代码核对拒绝原因 */ exception -> assertThat(exception.code()).isEqualTo("INVALID_RESERVATION_KIND"));
    }

    /**
     * 反向永续用 BTC 结算，显式把准入预留资产写成 USDT 时必须按衍生品结算资产不符拒绝。
     */
    @Test
    void derivativeReservationMustUseSettleAsset() {
        // 已注册反向永续标的五、尚未注资的冷状态
        TradingCoreState base = reducer.registerInstrument(TradingCoreState.empty(ProductLine.INVERSE_PERPETUAL),
                CoreStateTestFixtures.instrument(ProductLine.INVERSE_PERPETUAL,
                        "5", "BTC", "USD", "BTC"));
        // 本用例已经给用户一百零一注资的冷状态，后续转换不能改写它
        TradingCoreState funded = reducer.adjustBalance(base, 101,
                new BalanceAdjustmentCommand("USDT", 1_000));

        // 本用例需要拒绝的委托意图，错误预留类型或资产另由 resolved 显式附加
        PlaceOrderCommand invalid = new PlaceOrderCommand(1, "5", CoreOrderSide.BUY, 60_000, 1,
                false, CoreMarginMode.CROSS, CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "");
        assertThatThrownBy(() -> reducer.placeOrder(funded, 101,
                resolved(funded, invalid, ReservationKind.DERIVATIVE_MARGIN, "USDT", 0, 0)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定代码核对拒绝原因 */ exception -> assertThat(exception.code())
                                .isEqualTo("INVALID_DERIVATIVE_RESERVATION_ASSET"));
    }

    /**
     * 余额九十九不足以冻结一百时，原哈希和订单表不变；资金足够后同订单 ID 重下被拒，已冻结金额保持一百。
     */
    @Test
    void insufficientFundsAndDuplicateOrderLeavePriorStateUntouched() {
        // 本用例已经给用户一百零一注资的冷状态，后续转换不能改写它
        TradingCoreState funded = funded(ProductLine.SPOT, "USDT", 99);
        // 资金不足下单前的完整业务哈希，拒绝后必须相同
        long initialHash = funded.businessStateHash();

        assertThatThrownBy(() -> reducer.placeOrder(funded, 101,
                order(1, CoreOrderSide.BUY)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定代码核对拒绝原因 */ exception -> assertThat(exception.code()).isEqualTo("INSUFFICIENT_AVAILABLE_BALANCE"));
        assertThat(funded.businessStateHash()).isEqualTo(initialHash);
        assertThat(funded.orders()).isEmpty();

        // 余额一千的独立冷状态，用于接受首单后再验证同 ID 重单
        TradingCoreState sufficientlyFunded = funded(ProductLine.SPOT, "USDT", 1_000);
        // 本用例接受委托后的冷状态，保留订单和预留事实供断言
        TradingCoreState placed = reducer.placeOrder(sufficientlyFunded, 101,
                order(1, CoreOrderSide.BUY));
        assertThatThrownBy(() -> reducer.placeOrder(placed, 101,
                order(1, CoreOrderSide.BUY)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定代码核对拒绝原因 */ exception -> assertThat(exception.code()).isEqualTo("DUPLICATE_ORDER_ID"));
        assertBalance(placed, "USDT", 900, 100);
    }

    /**
     * 对同订单连续撤单，第二次得到与第一次相同的状态，资金只能解冻一次。
     */
    @Test
    void repeatedCancelDoesNotReleaseTwice() {
        // 本用例接受委托后的冷状态，保留订单和预留事实供断言
        TradingCoreState placed = reducer.placeOrder(funded(ProductLine.SPOT, "USDT", 1_000), 101,
                order(1, CoreOrderSide.BUY));
        // 第一次撤单并释放预留后的冷状态
        TradingCoreState firstCancel = reducer.cancelOrder(placed, 101, new CancelOrderCommand(1));
        // 相同订单再次撤单后的冷状态，不应重复释放资金
        TradingCoreState secondCancel = reducer.cancelOrder(firstCancel, 101, new CancelOrderCommand(1));

        assertThat(secondCancel).isEqualTo(firstCancel);
        assertBalance(secondCancel, "USDT", 1_000, 0);
    }

    /**
     * 即使测试辅助预留数量传零，核心仍按价格十、数量十的买单意图冻结一百。
     */
    @Test
    void coreComputesExactReservationFromIntent() {
        // 本用例已经给用户一百零一注资的冷状态，后续转换不能改写它
        TradingCoreState funded = funded(ProductLine.SPOT, "USDT", 1_000);
        // 本用例接受委托后的冷状态，保留订单和预留事实供断言
        TradingCoreState placed = reducer.placeOrder(funded, 101,
                order(1, CoreOrderSide.BUY));
        assertBalance(placed, "USDT", 900, 100);
    }

    /**
     * 显式采用挂单返佣和吃单收费快照应用现货成交，检查双方两种资产及财库净手续费五，验证费用使用订单接受时的费率。
     */
    @Test
    void spotMatchAppliesOrderFeeSnapshotsAndPreservesAssets() {
        // 本用例按注资、挂单和成交顺序推进的冷状态
        TradingCoreState state = funded(ProductLine.SPOT, "USDT", 1_000);
        state = reducer.adjustBalance(state, 202, new BalanceAdjustmentCommand("BTC", 10));
        // 显式固定挂单返佣的现货卖方准入结果
        ResolvedPlaceOrder makerSell = feeOrder(state, 2, CoreOrderSide.SELL, ReservationKind.SPOT_ASSET,
                "BTC", -50_000, 100_000);
        // 显式固定吃单收费的现货买方准入结果
        ResolvedPlaceOrder takerBuy = feeOrder(state, 1, CoreOrderSide.BUY, ReservationKind.SPOT_ASSET,
                "USDT", 0, 100_000);
        state = reducer.placeOrder(state, 202, makerSell);
        state = reducer.placeOrder(state, 101, takerBuy);

        // 应用本用例显式成交事件后的冷状态，供费用、持仓及守恒断言
        TradingCoreState matched = reducer.applyMatches(state, 1, "BTC", "USDT",
                List.of(trade(2, 202, 10, 10, true, true)));

        assertThat(matched.user(101).totalUnits("USDT")).isEqualTo(890);
        assertThat(matched.user(101).totalUnits("BTC")).isEqualTo(10);
        assertThat(matched.user(202).totalUnits("BTC")).isZero();
        assertThat(matched.user(202).totalUnits("USDT")).isEqualTo(105);
        assertThat(matched.treasuryState().feeBalances()).containsEntry("USDT", 5L);
    }

    /**
     * 按挂单返佣及吃单收费快照应用正向永续成交，检查双方总额、持仓保证金和财库净手续费十。
     */
    @Test
    void derivativeMatchUsesMakerAndTakerFeeSnapshots() {
        // 本用例按注资、挂单和成交顺序推进的冷状态
        TradingCoreState state = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 1_000);
        state = reducer.adjustBalance(state, 202, new BalanceAdjustmentCommand("USDT", 1_000));
        state = reducer.placeOrder(state, 202, feeOrder(state, 2, CoreOrderSide.SELL,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", -100_000, 200_000));
        state = reducer.placeOrder(state, 101, feeOrder(state, 1, CoreOrderSide.BUY,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 0, 200_000));

        // 应用本用例显式成交事件后的冷状态，供费用、持仓及守恒断言
        TradingCoreState matched = reducer.applyMatches(state, 1, "BTC", "USDT",
                List.of(trade(2, 202, 10, 10, true, true)));

        assertThat(matched.user(101).totalUnits("USDT")).isEqualTo(980);
        assertThat(matched.user(202).totalUnits("USDT")).isEqualTo(1_010);
        assertThat(matched.user(101).positions().get("1").positionMarginUnits()).isEqualTo(10);
        assertThat(matched.user(202).positions().get("1").positionMarginUnits()).isEqualTo(10);
        assertThat(matched.treasuryState().feeBalances()).containsEntry("USDT", 10L);
    }

    /**
     * 市价卖单以更高买价二十成交，确认订单完成、接受时预留分配出的保证金为十一，并保持双方总资金两千。
     */
    @Test
    void betterPricedLinearSellFillUsesTheAcceptedReservationWithoutDiverging() {
        // 本用例按注资、挂单和成交顺序推进的冷状态
        TradingCoreState state = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 1_000);
        state = reducer.adjustBalance(state, 202, new BalanceAdjustmentCommand("USDT", 1_000));
        // 价格二十的挂单买单，给市价卖单提供较优成交价
        PlaceOrderCommand makerBuy = new PlaceOrderCommand(2, "1", CoreOrderSide.BUY, 20, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "maker-2");
        // 价格零的市价 IOC 卖单，由既有准入确定参考价与冻结
        PlaceOrderCommand takerSell = new PlaceOrderCommand(1, "1", CoreOrderSide.SELL, 0, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.MARKET, com.surprising.aeron.protocol.CoreTimeInForce.IOC, false, "taker-1");
        state = reducer.placeOrder(state, 202, makerBuy);
        state = reducer.placeOrder(state, 101, takerSell);

        // 应用本用例显式成交事件后的冷状态，供费用、持仓及守恒断言
        TradingCoreState matched = reducer.applyMatches(state, 1, "BTC", "USDT",
                List.of(trade(2, 202, 20, 10, true, true)));

        assertThat(matched.order(1).status()).isEqualTo(CoreOrderStatus.FILLED);
        assertThat(matched.user(101).positions().get("1").positionMarginUnits()).isEqualTo(11);
        assertThat(matched.user(101).balances().get("USDT")).isEqualTo(new AssetBalance("USDT", 989, 11));
        assertThat(matched.user(101).totalUnits("USDT") + matched.user(202).totalUnits("USDT"))
                .isEqualTo(2_000);
    }

    /**
     * 同一挂单用户先高价再低价挂卖，市价买单成交低价单后保证金为十，总资金不因先前高价单而改变。
     */
    @Test
    void higherAskPlacedBeforeLowerAskCannotLeaveTheLowerFillUnderReserved() {
        // 本用例按注资、挂单和成交顺序推进的冷状态
        TradingCoreState state = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 1_000);
        state = reducer.adjustBalance(state, 202, new BalanceAdjustmentCommand("USDT", 1_000));
        // 先后顺序由用例控制的价格二十卖单，用于检查多笔报价下的保证金分配
        PlaceOrderCommand higherMakerAsk = new PlaceOrderCommand(2, "1", CoreOrderSide.SELL, 20, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "maker-high");
        // 同一挂单用户价格十的卖单，与高价单共同验证分笔成交保证金
        PlaceOrderCommand lowerMakerAsk = new PlaceOrderCommand(3, "1", CoreOrderSide.SELL, 10, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "maker-low");
        // 市价 IOC 买单，预期只成交较低卖价的一笔
        PlaceOrderCommand takerBuy = new PlaceOrderCommand(1, "1", CoreOrderSide.BUY, 0, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.MARKET, com.surprising.aeron.protocol.CoreTimeInForce.IOC, false, "taker");
        state = reducer.placeOrder(state, 202, higherMakerAsk);
        state = reducer.placeOrder(state, 202, lowerMakerAsk);
        state = reducer.placeOrder(state, 101, takerBuy);

        // 应用本用例显式成交事件后的冷状态，供费用、持仓及守恒断言
        TradingCoreState matched = reducer.applyMatches(state, 1, "BTC", "USDT",
                List.of(trade(3, 202, 10, 10, true, true)));

        assertThat(matched.order(1).status()).isEqualTo(CoreOrderStatus.FILLED);
        assertThat(matched.user(202).positions().get("1").positionMarginUnits()).isEqualTo(10);
        assertThat(matched.user(101).totalUnits("USDT") + matched.user(202).totalUnits("USDT"))
                .isEqualTo(2_000);
    }

    /**
     * 同一卖方依次在价格十和二十成交，累计空头二十、保证金三十；各笔按自身成交价分配，三方总资金仍为三千。
     */
    @Test
    void consecutiveAskFillsAllocateMarginAtEachFillPriceWithoutRepricingTheExistingPosition() {
        // 本用例按注资、挂单和成交顺序推进的冷状态
        TradingCoreState state = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 1_000);
        state = reducer.adjustBalance(state, 202, new BalanceAdjustmentCommand("USDT", 1_000));
        state = reducer.adjustBalance(state, 303, new BalanceAdjustmentCommand("USDT", 1_000));
        // 同一挂单用户价格十的卖单，与高价单共同验证分笔成交保证金
        PlaceOrderCommand lowerMakerAsk = new PlaceOrderCommand(2, "1", CoreOrderSide.SELL, 10, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "maker-low");
        // 先后顺序由用例控制的价格二十卖单，用于检查多笔报价下的保证金分配
        PlaceOrderCommand higherMakerAsk = new PlaceOrderCommand(3, "1", CoreOrderSide.SELL, 20, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "maker-high");
        // 用户一百零一的市价买单，先成交低价卖单
        PlaceOrderCommand firstTaker = new PlaceOrderCommand(1, "1", CoreOrderSide.BUY, 0, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.MARKET, com.surprising.aeron.protocol.CoreTimeInForce.IOC, false, "taker-1");
        // 用户三百零三价格二十的 IOC 买单，后成交高价卖单
        PlaceOrderCommand secondTaker = new PlaceOrderCommand(4, "1", CoreOrderSide.BUY, 20, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.IOC, false, "taker-2");
        state = reducer.placeOrder(state, 202, lowerMakerAsk);
        state = reducer.placeOrder(state, 202, higherMakerAsk);
        state = reducer.placeOrder(state, 101, firstTaker);
        state = reducer.applyMatches(state, 1, "BTC", "USDT",
                List.of(trade(2, 202, 10, 10, true, true)));
        state = reducer.placeOrder(state, 303, secondTaker);

        // 应用本用例显式成交事件后的冷状态，供费用、持仓及守恒断言
        TradingCoreState matched = reducer.applyMatches(state, 4, "BTC", "USDT",
                List.of(trade(3, 202, 20, 10, true, true)));

        assertThat(matched.user(202).positions().get("1").signedQuantitySteps()).isEqualTo(-20);
        assertThat(matched.user(202).positions().get("1").positionMarginUnits()).isEqualTo(30);
        assertThat(matched.user(101).totalUnits("USDT") + matched.user(202).totalUnits("USDT")
                + matched.user(303).totalUnits("USDT")).isEqualTo(3_000);
    }

    /**
     * 五倍杠杆已有持仓后继续加仓并跨入更高保证金率档位，新增委托连同已有持仓差额一起冻结，成交后保证金和锁定都为六十。
     */
    @Test
    void addingIntoHigherRiskBracketFreezesAndAllocatesTheExistingPositionDelta() {
        // 本用例按注资、挂单和成交顺序推进的冷状态
        TradingCoreState state = derivativeWithBrackets();
        state = reducer.updateLeverage(state, 101,
                new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 5_000_000L));
        state = reducer.updateLeverage(state, 202,
                new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 5_000_000L));
        state = reducer.adjustBalance(state, 101, new BalanceAdjustmentCommand("USDT", 1_000));
        state = reducer.adjustBalance(state, 202, new BalanceAdjustmentCommand("USDT", 1_000));
        state = reducer.placeOrder(state, 202,
                order(2, CoreOrderSide.SELL));
        state = reducer.placeOrder(state, 101,
                order(1, CoreOrderSide.BUY));
        state = reducer.applyMatches(state, 1, "BTC", "USDT",
                List.of(trade(2, 202, 10, 10, true, true)));

        // 已有基础持仓后，卖方再挂数量二十的加仓单所得到的状态
        TradingCoreState withMaker = reducer.placeOrder(state, 202,
                new PlaceOrderCommand(3, "1", CoreOrderSide.SELL, 10, 20, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, ""));
        // 买方也挂数量二十后、尚未应用成交的状态，用来检查跨档预冻结总量
        TradingCoreState withAdd = reducer.placeOrder(withMaker, 101,
                new PlaceOrderCommand(4, "1", CoreOrderSide.BUY, 10, 20, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, ""));

        assertThat(withAdd.user(101).balances().get("USDT").lockedUnits()).isEqualTo(60);
        // 应用本用例显式成交事件后的冷状态，供费用、持仓及守恒断言
        TradingCoreState matched = reducer.applyMatches(withAdd, 4, "BTC", "USDT",
                List.of(trade(3, 202, 10, 20, true, true)));
        assertThat(matched.user(101).positions().get("1").positionMarginUnits()).isEqualTo(60);
        assertThat(matched.user(101).balances().get("USDT").lockedUnits()).isEqualTo(60);
    }

    /**
     * 当前八倍杠杆可设置，但新订单预计进入更高保证金档位时，必须按档位杠杆约束拒绝。
     */
    @Test
    void leverageMustMeetTheProjectedRiskBracketMarginRate() {
        // 本用例按注资、挂单和成交顺序推进的冷状态
        TradingCoreState state = derivativeWithBrackets();
        // 已经设置本用例目标杠杆的冷状态，用于后续准入或快照断言
        TradingCoreState leveraged = reducer.updateLeverage(state, 101,
                new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 8_000_000L));
        // 本用例已经给用户一百零一注资的冷状态，后续转换不能改写它
        TradingCoreState funded = reducer.adjustBalance(leveraged, 101,
                new BalanceAdjustmentCommand("USDT", 1_000));

        assertThatThrownBy(() -> reducer.placeOrder(funded, 101,
                new PlaceOrderCommand(9, "1", CoreOrderSide.BUY, 10, 30, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "")))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定代码核对拒绝原因 */ exception -> assertThat(exception.code()).isEqualTo("LEVERAGE_EXCEEDS_RISK_BRACKET"));
    }

    /**
     * 已有同向挂单名义金额一百，再挂六十会超过标的一百五十固定上限，必须拒绝。
     */
    @Test
    void projectedSameSideOrdersCannotExceedCoreInstrumentLimit() {
        // 本用例按注资、挂单和成交顺序推进的冷状态
        TradingCoreState state = derivativeWithRiskPolicy(150, 1_000, 10_000_000L, 150, 10_000_000L);
        state = reducer.adjustBalance(state, 101, new BalanceAdjustmentCommand("USDT", 10_000));
        state = reducer.placeOrder(state, 101,
                order(1, CoreOrderSide.BUY));
        // 本用例接受委托后的冷状态，保留订单和预留事实供断言
        TradingCoreState placed = state;

        assertThatThrownBy(() -> reducer.placeOrder(placed, 101,
                new PlaceOrderCommand(2, "1", CoreOrderSide.BUY, 10, 6, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "")))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定代码核对拒绝原因 */ exception -> assertThat(exception.code()).isEqualTo("POSITION_NOTIONAL_LIMIT_EXCEEDED"));
    }

    /**
     * 分别配置未平仓比例的最低额度八十及风险档位五倍杠杆上限，确认核心按对应规则拒绝超过限制的订单。
     */
    @Test
    void dynamicOpenInterestFloorAndRiskBracketLeverageAreCoreAuthority() {
        // 按未平仓比例算额度且最低名义金额为八十的标的冷状态
        TradingCoreState floorLimited = derivativeWithRiskPolicy(1_000, 80, 1_000_000L,
                1_000, 10_000_000L);
        floorLimited = reducer.adjustBalance(floorLimited, 101, new BalanceAdjustmentCommand("USDT", 10_000));
        // 给比例额度用例注资后的只读捕获引用，供异常断言闭包使用
        TradingCoreState fundedFloorLimited = floorLimited;
        assertThatThrownBy(() -> reducer.placeOrder(fundedFloorLimited, 101,
                order(1, CoreOrderSide.BUY)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定代码核对拒绝原因 */ exception -> assertThat(exception.code()).isEqualTo("OPEN_INTEREST_LIMIT_EXCEEDED"));

        // 唯一风险档位限制五倍杠杆的标的冷状态
        TradingCoreState leverageLimited = derivativeWithRiskPolicy(1_000, 1_000, 10_000_000L,
                1_000, 5_000_000L);
        leverageLimited = reducer.adjustBalance(leverageLimited, 101,
                new BalanceAdjustmentCommand("USDT", 10_000));
        // 给档位杠杆用例注资后的只读捕获引用，供异常断言闭包使用
        TradingCoreState fundedLeverageLimited = leverageLimited;
        assertThatThrownBy(() -> reducer.placeOrder(fundedLeverageLimited, 101,
                order(1, CoreOrderSide.BUY)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定代码核对拒绝原因 */ exception -> assertThat(exception.code()).isEqualTo("LEVERAGE_EXCEEDS_RISK_BRACKET"));
    }

    /**
     * 用户已有 long 最大余额，再加一必须抛算术异常，原余额仍为最大值。
     */
    @Test
    void arithmeticOverflowFailsClosed() {
        // 用户可用余额已为 long 最大值的冷状态
        TradingCoreState maximum = funded(ProductLine.SPOT, "USDT", Long.MAX_VALUE);

        assertThatThrownBy(() -> reducer.adjustBalance(maximum, 101,
                new BalanceAdjustmentCommand("USDT", 1)))
                .isInstanceOf(ArithmeticException.class);
        assertThat(maximum.user(101).totalUnits("USDT")).isEqualTo(Long.MAX_VALUE);
    }

    /**
     * 没有持仓时提交只减仓卖单必须被拒绝，只减仓标志不能绕过持仓状态校验。
     */
    @Test
    void reduceOnlyWaitsForPositionStateInsteadOfBypassingValidation() {
        // 本用例已经给用户一百零一注资的冷状态，后续转换不能改写它
        TradingCoreState funded = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 1_000);
        // 没有持仓时需要被拒绝的只减仓卖单
        PlaceOrderCommand reduceOnly = new PlaceOrderCommand(1, "1", CoreOrderSide.SELL, 60_000, 1, true, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "");

        assertThatThrownBy(() -> reducer.placeOrder(funded, 101, reduceOnly))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定代码核对拒绝原因 */ exception -> assertThat(exception.code())
                                .isEqualTo("REDUCE_ONLY_REQUIRES_POSITION_STATE"));
    }

    /**
     * 切换双向模式后可挂逐仓多头单，用户和订单保留该模式及方向；仍有挂单时不能切回单向模式。
     */
    @Test
    void positionModePersistsAndOpenOrderBlocksSwitch() {
        // 本用例已经给用户一百零一注资的冷状态，后续转换不能改写它
        TradingCoreState funded = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 10_000);
        // 用户已切换到双向持仓模式的冷状态
        TradingCoreState hedge = reducer.updatePositionMode(funded, 101,
                new UpdatePositionModeCommand(CorePositionMode.HEDGE));
        // 双向模式下逐仓 LONG 买单，用于阻止仍有挂单时切换模式
        PlaceOrderCommand openLong = new PlaceOrderCommand(91, "1", CoreOrderSide.BUY, 10, 10, false, CoreMarginMode.ISOLATED, CorePositionSide.LONG, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "");
        // 本用例接受委托后的冷状态，保留订单和预留事实供断言
        TradingCoreState placed = reducer.placeOrder(hedge, 101, openLong);

        assertThat(placed.user(101).positionMode()).isEqualTo(CorePositionMode.HEDGE);
        assertThat(placed.order(91).positionSide()).isEqualTo(CorePositionSide.LONG);
        assertThatThrownBy(() -> reducer.updatePositionMode(placed, 101,
                new UpdatePositionModeCommand(CorePositionMode.ONE_WAY)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定代码核对拒绝原因 */ exception -> assertThat(exception.code()).isEqualTo("POSITION_MODE_SWITCH_BLOCKED"));
    }

    /**
     * 五条衍生品线手工建立保证金一千的逐仓持仓，加五百再减三百后权益不变，并可快照往返；冷运行时的越界请求保持原状态，启动 Lane 后两次合法控制任务得到相同最终状态。
     * @param product 本次验证逐仓保证金调整的衍生品产品线
     */
    @ParameterizedTest
    @MethodSource("derivativeProductLines")
    void isolatedMarginAdjustmentMovesFundsWithoutChangingEquity(ProductLine product) {
        // 本次逐仓调整或杠杆重计所用产品线的结算资产
        String asset = CoreStateTestFixtures.settleAsset(product);
        // 本用例已经给用户一百零一注资的冷状态，后续转换不能改写它
        TradingCoreState funded = funded(product, asset, 10_000);
        // 手工建立逐仓持仓前的原用户状态
        CoreUserState current = funded.user(101);
        // 复制的资产表，将一千从可用改为锁定以匹配手工持仓
        Map<String, AssetBalance> balances = new TreeMap<>(current.balances());
        balances.put(asset, new AssetBalance(asset, 9_000, 1_000));
        // 本用例手工建立的数量十、保证金一千的逐仓净持仓
        CorePositionState position = new CorePositionState("1", asset, CoreMarginMode.ISOLATED,
                CorePositionSide.NET, 10, 10, 100, 0, 1_000);
        // 替换了余额和逐仓持仓、用户修订加一的冷用户状态
        CoreUserState user = new CoreUserState(current.productLine(), current.userId(), current.revision() + 1,
                balances, current.reservations(), Map.of(position.key(), position), current.positionMode());
        // 复制的用户表，只替换本用例用户的手工逐仓状态
        Map<Long, CoreUserState> users = new TreeMap<>(funded.users());
        users.put(user.userId(), user);
        // 带手工逐仓持仓及对应锁定余额的完整冷状态
        TradingCoreState withPosition = new TradingCoreState(funded.productLine(), funded.revision() + 1,
                users, funded.orders(), funded.instruments(), funded.riskState(),
                funded.treasuryState());

        // 追加逐仓保证金五百后的预期冷状态
        TradingCoreState added = reducer.adjustPositionMargin(withPosition, 101,
                new AdjustPositionMarginCommand("1", CorePositionSide.NET, 500));
        // 再减少逐仓保证金三百后的预期冷状态，可用八千八百、锁定一千二百
        TradingCoreState removed = reducer.adjustPositionMargin(added, 101,
                new AdjustPositionMarginCommand("1", CorePositionSide.NET, -300));

        assertThat(added.user(101).totalUnits(asset)).isEqualTo(10_000);
        assertThat(added.user(101).positions().get("1").positionMarginUnits()).isEqualTo(1_500);
        assertBalance(removed, asset, 8_800, 1_200);
        assertThat(removed.user(101).positions().get("1").positionMarginUnits()).isEqualTo(1_200);
        assertThat(TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(removed),
                product)).isEqualTo(removed);

        // 本次运行时投影独用的整数身份表
        var identities = new RuntimeIdentityRegistry();
        try (/* 从用例原状态投影的运行时，直接业务或账户控制任务在其中执行，作用域结束关闭 */ var runtime = RuntimeStateProjector.project(withPosition, identities)) {
            for (long /* 超过可用、超过持仓保证金或取 long 极小负数的故意非法调整金额 */ rejected : new long[] {9_001, -1_001, Long.MIN_VALUE}) {
                assertThatThrownBy(() -> DerivativeAccountCommandProcessor.adjustPositionMargin(runtime, identities, 101,
                        new AdjustPositionMarginCommand("1", CorePositionSide.NET, rejected)))
                        .isInstanceOf(RuntimeException.class);
                assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(withPosition);
            }
            runtime.startAccountLanes();
            for (long /* 账户 Lane 依次执行的合法逐仓保证金调整额，先加五百后减三百 */ amount : new long[] {500, -300}) {
                // 本次逐仓保证金调整的可轮询账户控制任务
                var work = new AccountControlTask().preparePositionMarginAdjustment(runtime, identities, 101,
                        new AdjustPositionMarginCommand("1", CorePositionSide.NET, amount));
                // 本次控制任务轮询的单调时钟五秒期限
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                // 账户控制任务是否完成，退出轮询后必须为真
                boolean done = false;
                while (!(done = work.poll()) && System.nanoTime() < deadline) Thread.onSpinWait();
                assertThat(done).isTrue();
            }
            assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(removed);
        }
    }

    /**
     * 超过标的十倍上限的杠杆拒绝；改为五倍后名义金额一百的订单冻结二十，杠杆配置可快照往返。
     */
    @Test
    void leverageIsAuthoritativeForReservationAndSurvivesSnapshot() {
        // 本用例已经给用户一百零一注资的冷状态，后续转换不能改写它
        TradingCoreState funded = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 10_000);
        assertThatThrownBy(() -> reducer.updateLeverage(funded, 101,
                new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 20_000_000L)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定代码核对拒绝原因 */ exception -> assertThat(exception.code()).isEqualTo("LEVERAGE_EXCEEDS_INSTRUMENT_LIMIT"));

        // 已经设置本用例目标杠杆的冷状态，用于后续准入或快照断言
        TradingCoreState leveraged = reducer.updateLeverage(funded, 101,
                new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 5_000_000L));
        // 价格十、数量十的全仓净仓买单，用于核对五倍杠杆下预留二十
        PlaceOrderCommand order = new PlaceOrderCommand(301, "1", CoreOrderSide.BUY, 10, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "");

        // 本用例接受委托后的冷状态，保留订单和预留事实供断言
        TradingCoreState placed = reducer.placeOrder(leveraged, 101, order);
        assertThat(placed.user(101).reservations().get(301L).reservedUnits()).isEqualTo(20);
        assertThat(leveraged.leverages()).containsEntry(
                new CoreLeverageKey(101, "1", CoreMarginMode.CROSS), 5_000_000L);
        assertThat(TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(leveraged),
                ProductLine.LINEAR_PERPETUAL)).isEqualTo(leveraged);
    }

    /**
     * 四条期货产品线已有全仓持仓时，普通改杠杆被拒；显式允许重计后降到两倍，增加锁定及持仓保证金、保留总额及对手状态，重复执行无变化，快照与账户 Lane 结果一致。
     * @param line 本次建立持仓或降低杠杆的期货产品线
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ProductLine.class,
            names = {"LINEAR_PERPETUAL", "LINEAR_DELIVERY", "INVERSE_PERPETUAL", "INVERSE_DELIVERY"})
    void lowerCrossLeverageFundsPositionsAtomicallyAndSurvivesLanePublicationAndSnapshot(ProductLine line) {
        // 本次逐仓调整或杠杆重计所用产品线的结算资产
        String asset = CoreStateTestFixtures.settleAsset(line);
        // 杠杆重计前的完整冷状态，失败时必须原样保留
        TradingCoreState before = leveragePosition(line);
        // 显式允许已有持仓重计的全仓两倍目标杠杆命令
        var command = new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 2_000_000, true);
        assertThatThrownBy(() -> reducer.updateLeverage(before, 101,
                new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 2_000_000)))
                .hasMessageContaining("open orders or positions exist");
        // 冷转换降低杠杆后的完整预期状态，用来比较快照和账户 Lane 结果
        TradingCoreState expected = reducer.updateLeverage(before, 101, command);
        assertThat(expected.user(101).totalUnits(asset)).isEqualTo(before.user(101).totalUnits(asset));
        assertThat(expected.user(202)).isEqualTo(before.user(202));
        assertThat(expected.user(101).positions().get("1").positionMarginUnits())
                .isGreaterThan(before.user(101).positions().get("1").positionMarginUnits());
        assertThat(expected.user(101).balances().get(asset).lockedUnits())
                .isEqualTo(expected.user(101).positions().get("1").positionMarginUnits());
        assertThat(reducer.updateLeverage(expected, 101, command)).isEqualTo(expected);
        assertThat(TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(expected), line)).isEqualTo(expected);
        // 本次运行时投影独用的整数身份表
        var identities = new RuntimeIdentityRegistry();
        try (/* 从用例原状态投影的运行时，直接业务或账户控制任务在其中执行，作用域结束关闭 */ var runtime = RuntimeStateProjector.project(before, identities)) {
            runtime.startAccountLanes();
            // 本次降低全仓杠杆并补足保证金的可轮询账户控制任务
            var work = new AccountControlTask().prepareLeverageChange(runtime, identities, 101, command);
            // 本次控制任务轮询的单调时钟五秒期限
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            // 账户控制任务是否完成，退出轮询后必须为真
            boolean done = false;
            while (!(done = work.poll()) && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(done).isTrue();
            assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(expected);
        }
    }

    /**
     * 可用余额耗尽时降杠杆补款失败，完整状态不变；另验证有挂单、逐仓及试图提高杠杆均不能使用该全仓降杠杆重计路径。
     */
    @Test
    void rejectedLeverageRepricingLeavesMoneyPositionsAndLeverageUnchanged() {
        // 杠杆重计前的完整冷状态，失败时必须原样保留
        TradingCoreState before = leveragePosition(ProductLine.LINEAR_PERPETUAL);
        before = reducer.adjustBalance(before, 101, new BalanceAdjustmentCommand("USDT",
                -before.user(101).balances().get("USDT").availableUnits()));
        // 本次运行时投影独用的整数身份表
        var identities = new RuntimeIdentityRegistry();
        try (/* 从用例原状态投影的运行时，直接业务或账户控制任务在其中执行，作用域结束关闭 */ var runtime = RuntimeStateProjector.project(before, identities)) {
            // 显式允许已有持仓重计的全仓两倍目标杠杆命令
            var command = new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 2_000_000, true);
            assertThatThrownBy(() -> DerivativeAccountCommandProcessor.updateLeverage(runtime, identities, 101, command))
                    .hasMessageContaining("insufficient available funds");
            assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(before);
        }
        // 已有持仓上又挂新单的冷状态，必须先撤单才允许该杠杆重计
        TradingCoreState withOrder = reducer.placeOrder(leveragePosition(ProductLine.LINEAR_PERPETUAL), 101,
                order(3, CoreOrderSide.BUY));
        // 显式允许已有持仓重计的全仓两倍目标杠杆命令
        var command = new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 2_000_000, true);
        assertThatThrownBy(() -> reducer.updateLeverage(withOrder, 101, command)).hasMessageContaining("cancel open orders");
        assertThatThrownBy(() -> reducer.updateLeverage(withOrder, 101,
                new UpdateLeverageCommand("1", CoreMarginMode.ISOLATED, 2_000_000, true)))
                .hasMessageContaining("cross margin is required");
        // 已降到两倍杠杆的冷状态，再尝试五倍用于验证只允许降低
        TradingCoreState lowered = reducer.updateLeverage(leveragePosition(ProductLine.LINEAR_PERPETUAL), 101, command);
        assertThatThrownBy(() -> reducer.updateLeverage(lowered, 101,
                new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 5_000_000, true)))
                .hasMessageContaining("only lowering leverage");
    }

    /**
     * 为给定期货线给双方注资并应用一组成交，建立用户一百零一和二百零二的对向全仓持仓，供杠杆重计用例使用。
     * @param line 本次建立持仓或降低杠杆的期货产品线
     */
    private TradingCoreState leveragePosition(ProductLine line) {
        // 本次逐仓调整或杠杆重计所用产品线的结算资产
        String asset = CoreStateTestFixtures.settleAsset(line);
        // 本用例按注资、挂单和成交顺序推进的冷状态
        TradingCoreState state = funded(line, asset, 10_000);
        state = reducer.adjustBalance(state, 202, new BalanceAdjustmentCommand(asset, 10_000));
        state = reducer.placeOrder(state, 202, order(2, CoreOrderSide.SELL));
        state = reducer.placeOrder(state, 101, order(1, CoreOrderSide.BUY));
        return reducer.applyMatches(state, 1, "BTC", "USDT", List.of(trade(2, 202, 10, 10, true, true)));
    }

    /**
     * 建立算法父单和真实子单后更新父单关联；相同修订重发被拒，关联可快照往返，不存在的子单不能挂到父单。
     */
    @Test
    void algoParentUsesAuthoritativeChildOrdersAndSurvivesSnapshot() {
        // 本用例已经给用户一百零一注资的冷状态，后续转换不能改写它
        TradingCoreState funded = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 10_000);
        // 尚未关联子单的算法父单首个修订
        var initial = algo(501, 101, 1, List.of());
        // 已保存算法父单、尚未创建子单的冷状态
        TradingCoreState created = reducer.upsertAlgoOrder(funded, 101, initial);
        // 真实子单六百零一已接受、尚未更新父单关联的冷状态
        TradingCoreState withChild = reducer.placeOrder(created, 101,
                new PlaceOrderCommand(601, "1", CoreOrderSide.BUY, 10, 10, false,
                        CoreMarginMode.CROSS, CorePositionSide.NET,
                        com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                        com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "algo-501-0"));
        // 父单新修订关联真实子单后的冷状态
        TradingCoreState linked = reducer.upsertAlgoOrder(withChild, 101, algo(501, 101, 2, List.of(601L)));

        assertThat(linked.algoOrders().get(501L).childOrderIds()).containsExactly(601L);
        assertThatThrownBy(() -> reducer.upsertAlgoOrder(linked, 101, algo(501, 101, 2, List.of(601L))))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定代码核对拒绝原因 */ exception -> assertThat(exception.code()).isEqualTo("STALE_ALGO_ORDER_REVISION"));
        assertThat(TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(linked),
                ProductLine.LINEAR_PERPETUAL)).isEqualTo(linked);
        assertThatThrownBy(() -> reducer.upsertAlgoOrder(withChild, 101,
                algo(501, 101, 2, List.of(999L))))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定代码核对拒绝原因 */ exception -> assertThat(exception.code()).isEqualTo("INVALID_ALGO_CHILD"));
    }

    /**
     * 依次设置、认领和完成延时全撤计时器，核对状态、统计及快照往返；旧修订再次认领被拒。这里只更新计时器状态，没有实际撤三笔订单。
     */
    @Test
    void cancelAllAfterUsesRevisionedAeronStateAndSurvivesSnapshot() {
        // 本用例从空现货状态开始，只推进延时全撤计时器，不创建真实订单
        TradingCoreState state = TradingCoreState.empty(ProductLine.SPOT);
        // 为用户一百零一设置标的一延时全撤计时器的命令
        var set = new com.surprising.aeron.protocol.CoreCancelAllAfterCommand(com.surprising.aeron.protocol.CoreCancelAllAfterAction.SET, "1", 1_000, 0, 0, 0, 1_000);
        // 计时器已设置为 ACTIVE 的冷状态
        TradingCoreState active = reducer.updateCancelAllAfter(state, 101, set);
        // 首个有效计时器及其修订，供认领命令做版本校验
        CoreCancelAllAfterState activeTimer = active.cancelAllAfterTimers()
                .get(new CoreCancelAllAfterKey(101, "1"));
        // 按有效修订和到期时间认领计时器的命令
        var claim = new com.surprising.aeron.protocol.CoreCancelAllAfterCommand(com.surprising.aeron.protocol.CoreCancelAllAfterAction.CLAIM, "1", 1_000, activeTimer.revision(), 0, 0, 2_000);
        // 计时器已认领为 TRIGGERING 的冷状态
        TradingCoreState triggering = reducer.updateCancelAllAfter(active, 101, claim);
        // 已认领计时器及其新修订，供完成命令校验
        CoreCancelAllAfterState triggeringTimer = triggering.cancelAllAfterTimers()
                .get(new CoreCancelAllAfterKey(101, "1"));
        // 按当前修订结束计时器并记录普通撤单三、触发撤单二的命令，未在此实际执行撤单
        var complete = new com.surprising.aeron.protocol.CoreCancelAllAfterCommand(com.surprising.aeron.protocol.CoreCancelAllAfterAction.COMPLETE, "1", 1_000, triggeringTimer.revision(), 3, 2, 2_100);
        // 计时器记录为已触发并保留完成统计的冷状态
        TradingCoreState completed = reducer.updateCancelAllAfter(triggering, 101, complete);

        assertThat(activeTimer.status()).isEqualTo(com.surprising.aeron.protocol.CoreCancelAllAfterStatus.ACTIVE);
        assertThat(triggeringTimer.status())
                .isEqualTo(com.surprising.aeron.protocol.CoreCancelAllAfterStatus.TRIGGERING);
        assertThat(completed.cancelAllAfterTimers().get(activeTimer.key()).status())
                .isEqualTo(com.surprising.aeron.protocol.CoreCancelAllAfterStatus.TRIGGERED);
        assertThat(completed.cancelAllAfterTimers().get(activeTimer.key()).canceledOrders()).isEqualTo(3);
        assertThat(TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(completed), ProductLine.SPOT))
                .isEqualTo(completed);
        assertThatThrownBy(() -> reducer.updateCancelAllAfter(triggering, 101, claim))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 本次预期的业务拒绝异常，用稳定代码核对拒绝原因 */ exception -> assertThat(exception.code()).isEqualTo("STALE_CANCEL_ALL_AFTER_REVISION"));
    }

    /**
     * 构造标的一的算法父单视图，使用调用方身份、修订及子单清单，其余使用本用例的固定买单调度参数。
     * @param id 本算法父单的业务 ID
     * @param userId 本算法父单所属用户
     * @param revision 本算法父单的目标业务修订，辅助构造时在对应位置传入同一值
     * @param children 本算法父单需要关联的真实子订单 ID 清单
     */
    private static com.surprising.aeron.protocol.CoreAlgoOrderView algo(
            long id, long userId, long revision, List<Long> children) {
        return new com.surprising.aeron.protocol.CoreAlgoOrderView(id, userId, "algo-client", "1", 0,
                CoreOrderSide.BUY, 0, 100, 10, 1, 10, CoreMarginMode.CROSS, CorePositionSide.NET,
                false, com.surprising.aeron.protocol.CoreTimeInForce.IOC, 0, 0, "",
                1, 1, 0, 1, revision, revision, children, 0, 0, 0);
    }

    /**
     * 创建带默认标的的冷状态；衍生品补充初始标记价，期权另带指数及远期价，然后给用户一百零一注入指定资产。
     * @param productLine 当前参数化用例或冷状态所属的独立产品线
     * @param asset 本次明确指定的注资、预留或断言资产
     * @param units 给用户一百零一增加的资产整数数量
     */
    private TradingCoreState funded(ProductLine productLine, String asset, long units) {
        // 本用例按注资、挂单和成交顺序推进的冷状态
        TradingCoreState state = CoreStateTestFixtures.withInstrument(reducer, productLine);
        if (productLine.isDerivative()) {
            state = reducer.applyMarkPrice(state,
                    productLine == ProductLine.OPTION
                            ? new ApplyMarkPriceCommand("1", 10, 100, 100, 1, 1)
                            : new ApplyMarkPriceCommand("1", 10, 1, 1));
        }
        return reducer.adjustBalance(state, 101, new BalanceAdjustmentCommand(asset, units));
    }

    /**
     * 构造标的一、价格十、数量十的全仓净仓限价GTC意图；冻结资产和金额由后续准入计算。
     * @param orderId 本次测试委托的订单 ID
     * @param side 本次测试委托的买卖方向
     */
    private static PlaceOrderCommand order(
            long orderId,
            CoreOrderSide side) {
        return new PlaceOrderCommand(orderId, "1", side, 10, 10, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "");
    }

    /**
     * 构造固定限价意图，并把调用方指定的预留类型、资产及挂吃单费率装入显式准入结果，供费用和拒绝规则测试。
     * @param state 用于查标的或检查用户余额的当前冷状态
     * @param orderId 本次测试委托的订单 ID
     * @param side 本次测试委托的买卖方向
     * @param kind 显式准入结果采用的预留类型
     * @param asset 本次明确指定的注资、预留或断言资产
     * @param makerFeeRatePpm 需要固定在本订单准入结果中的挂单费率，单位为百万分之一
     * @param takerFeeRatePpm 需要固定在本订单准入结果中的吃单费率，单位为百万分之一
     */
    private static ResolvedPlaceOrder feeOrder(
            TradingCoreState state,
            long orderId,
            CoreOrderSide side,
            ReservationKind kind,
            String asset,
            long makerFeeRatePpm,
            long takerFeeRatePpm) {
        // 准备附加显式预留及费用快照的固定限价下单意图
        PlaceOrderCommand intent = new PlaceOrderCommand(orderId, "1", side, 10, 10, false,
                CoreMarginMode.CROSS, CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "");
        return resolved(state, intent, kind, asset, makerFeeRatePpm, takerFeeRatePpm);
    }

    /**
     * 从冷状态查意图所属标的，用限价固定参考价格并附上指定预留与费率；该夹具只有标的一，使用其首次注册后的稠密编号0。
     * @param state 用于查标的或检查用户余额的当前冷状态
     * @param intent 已构造的下单意图，提供标的和价格数量
     * @param kind 显式准入结果采用的预留类型
     * @param asset 本次明确指定的注资、预留或断言资产
     * @param makerFeeRatePpm 需要固定在本订单准入结果中的挂单费率，单位为百万分之一
     * @param takerFeeRatePpm 需要固定在本订单准入结果中的吃单费率，单位为百万分之一
     */
    private static ResolvedPlaceOrder resolved(
            TradingCoreState state, PlaceOrderCommand intent, ReservationKind kind, String asset,
            long makerFeeRatePpm, long takerFeeRatePpm) {
        // 意图所属的已有标的，供显式准入结果使用
        CoreInstrument instrument = state.instruments().get(intent.instrumentId());
        return new ResolvedPlaceOrder(intent, instrument, 0, intent.limitPriceTicks(), intent.limitPriceTicks(),
                intent.limitPriceTicks(), kind, asset, makerFeeRatePpm, takerFeeRatePpm);
    }

    /**
     * 枚举所有衍生品产品线，排除现货，作为保证金用例的数据来源。
     */
    private static Stream<ProductLine> derivativeProductLines() {
        return Stream.of(ProductLine.values()).filter(ProductLine::isDerivative);
    }

    /**
     * 建立正向永续标的一，使用指定固定上限、未平仓比例及最低额和单档杠杆上限，并设置标记价十。
     * @param maxPosition 标的允许的用户固定名义金额上限
     * @param openInterestFloor 按市场未平仓比例计算额度时采用的最低名义金额
     * @param openInterestRate 用户额度相对未平仓总额的比例，单位为百万分之一
     * @param bracketCap 该测试唯一风险档位覆盖的名义金额上界
     * @param bracketMaxLeverage 该风险档位允许的最大杠杆，按一百万倍缩放
     */
    private TradingCoreState derivativeWithRiskPolicy(long maxPosition, long openInterestFloor,
                                                       long openInterestRate, long bracketCap,
                                                       long bracketMaxLeverage) {
        // 使用指定固定限额、比例限额和唯一风险档位的注册命令
        RegisterInstrumentCommand instrument = new RegisterInstrumentCommand(
                "1", "BTC", "USDT", "USDT", 1, 1, 1,
                new CoreInstrumentRiskLimits(
                        100_000, 10_000_000L, maxPosition, openInterestRate, openInterestFloor,
                        List.of(new CoreRiskLimitBracket(bracketCap, bracketMaxLeverage, 100_000, 50_000))),
                0, 0, 0, -1, 0);
        return reducer.applyMarkPrice(
                reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), instrument),
                new ApplyMarkPriceCommand("1", 10, 1, 1));
    }

    /**
     * 建立两档正向永续风险限额：两百以内初始率百分之十，之后至一千初始率百分之二十，供跨档加仓和杠杆约束测试。
     */
    private TradingCoreState derivativeWithBrackets() {
        // 包含两档初始保证金率的正向永续注册命令
        RegisterInstrumentCommand instrument = new RegisterInstrumentCommand(
                "1", "BTC", "USDT", "USDT", 1, 1, 1,
                new CoreInstrumentRiskLimits(
                        100_000, 10_000_000L, 1_000, 10_000_000L, 1_000,
                        List.of(new CoreRiskLimitBracket(200, 10_000_000L, 100_000, 50_000),
                        new CoreRiskLimitBracket(1_000, 10_000_000L, 200_000, 100_000))),
                0, 0, 0, -1, 0);
        return reducer.applyMarkPrice(
                reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), instrument),
                new ApplyMarkPriceCommand("1", 10, 1, 1));
    }

    /**
     * 检查用户一百零一指定资产的可用与锁定金额。
     * @param state 用于查标的或检查用户余额的当前冷状态
     * @param asset 本次明确指定的注资、预留或断言资产
     * @param available 预期可用余额整数单位
     * @param locked 预期锁定余额整数单位
     */
    private static void assertBalance(
            TradingCoreState state,
            String asset,
            long available,
            long locked) {
        // 用户一百零一该资产的余额，用于分别核对可用和锁定
        AssetBalance balance = state.user(101).balances().get(asset);
        assertThat(balance.availableUnits()).isEqualTo(available);
        assertThat(balance.lockedUnits()).isEqualTo(locked);
    }
}
