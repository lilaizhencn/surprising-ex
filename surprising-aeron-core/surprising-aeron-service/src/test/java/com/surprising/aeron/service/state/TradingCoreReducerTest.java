package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.service.matching.CoreMatch;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TradingCoreReducerTest {

    private final TradingCoreReducer reducer = new TradingCoreReducer();

    @Test
    void reservesAndReleasesSpotBuyQuoteAssetAtomically() {
        TradingCoreState funded = funded(ProductLine.SPOT, "USDT", 10_000);

        TradingCoreState placed = reducer.placeOrder(funded, 101,
                order(1, CoreOrderSide.BUY, ReservationKind.SPOT_ASSET, "USDT", 3_000));
        TradingCoreState canceled = reducer.cancelOrder(placed, 101, new CancelOrderCommand(1));

        assertThat(StateMapSupport.isDelta(placed.users())).isTrue();
        assertThat(StateMapSupport.isDelta(placed.orders())).isTrue();
        assertThat(StateMapSupport.isDelta(placed.user(101).balances())).isTrue();
        assertThat(StateMapSupport.isDelta(placed.user(101).reservations())).isTrue();
        assertThat(StateMapSupport.isDelta(canceled.users())).isTrue();
        assertThat(StateMapSupport.isDelta(canceled.orders())).isTrue();
        assertThat(StateMapSupport.isDelta(canceled.user(101).balances())).isTrue();
        assertThat(StateMapSupport.isDelta(canceled.user(101).reservations())).isTrue();
        assertBalance(placed, "USDT", 9_900, 100);
        assertThat(placed.user(101).totalUnits("USDT")).isEqualTo(10_000);
        assertThat(placed.user(101).reservations().get(1L).remainingUnits()).isEqualTo(100);
        assertThat(placed.order(1).status()).isEqualTo(CoreOrderStatus.OPEN);
        assertBalance(canceled, "USDT", 10_000, 0);
        assertThat(canceled.user(101).reservations().get(1L).remainingUnits()).isZero();
        assertThat(canceled.order(1).status()).isEqualTo(CoreOrderStatus.CANCELED);
    }

    @Test
    void spotSellReservesBaseAssetAndRejectsWrongAsset() {
        TradingCoreState funded = funded(ProductLine.SPOT, "BTC", 20);
        TradingCoreState placed = reducer.placeOrder(funded, 101,
                order(2, CoreOrderSide.SELL, ReservationKind.SPOT_ASSET, "BTC", 10));

        assertBalance(placed, "BTC", 10, 10);
        assertThatThrownBy(() -> reducer.placeOrder(funded, 101,
                order(3, CoreOrderSide.SELL, ReservationKind.SPOT_ASSET, "USDT", 5)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_SPOT_RESERVATION_ASSET"));
    }

    @ParameterizedTest
    @MethodSource("derivativeProductLines")
    void allDerivativeLinesRequireMarginReservation(ProductLine productLine) {
        String settleAsset = productLine == ProductLine.INVERSE_PERPETUAL
                || productLine == ProductLine.INVERSE_DELIVERY ? "BTC" : "USDT";
        TradingCoreState funded = funded(productLine, settleAsset, 10_000);
        TradingCoreState placed = reducer.placeOrder(funded, 101,
                order(10 + productLine.ordinal(), CoreOrderSide.BUY,
                        ReservationKind.DERIVATIVE_MARGIN, settleAsset, 2_000));

        assertThat(placed.user(101).totalUnits(settleAsset)).isEqualTo(10_000);
        assertThat(placed.user(101).balances().get(settleAsset).lockedUnits()).isPositive();
        assertThat(placed.user(101).balances().get(settleAsset).lockedUnits()).isLessThan(2_000);
        assertThatThrownBy(() -> reducer.placeOrder(funded, 101,
                order(100 + productLine.ordinal(), CoreOrderSide.BUY,
                        ReservationKind.SPOT_ASSET, settleAsset, 2_000)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_RESERVATION_KIND"));
    }

    @Test
    void derivativeReservationMustUseSettleAsset() {
        TradingCoreState base = reducer.upsertInstrument(TradingCoreState.empty(ProductLine.INVERSE_PERPETUAL),
                CoreStateTestFixtures.instrument(ProductLine.INVERSE_PERPETUAL,
                        "BTC-USD", "BTC", "USD", "BTC", 1));
        TradingCoreState funded = reducer.adjustBalance(base, 101,
                new BalanceAdjustmentCommand("USDT", 1_000));

        assertThatThrownBy(() -> reducer.placeOrder(funded, 101,
                new PlaceOrderCommand(
                        1,
                        "BTC-USD",
                        1,
                        "BTC",
                        "USD",
                        "BTC",
                        CoreOrderSide.BUY,
                        60_000,
                        60_000,
                        60_000,
                        60_000,
                        1,
                        false,
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.NET,
                        ReservationKind.DERIVATIVE_MARGIN,
                        "USDT",
                        100,
                        com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                        com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                        false,
                        "",
                        0,
                        0
                )))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("INVALID_DERIVATIVE_RESERVATION_ASSET"));
    }

    @Test
    void insufficientFundsAndDuplicateOrderLeavePriorStateUntouched() {
        TradingCoreState funded = funded(ProductLine.SPOT, "USDT", 99);
        long initialHash = funded.businessStateHash();

        assertThatThrownBy(() -> reducer.placeOrder(funded, 101,
                order(1, CoreOrderSide.BUY, ReservationKind.SPOT_ASSET, "USDT", 2_000)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INSUFFICIENT_AVAILABLE_BALANCE"));
        assertThat(funded.businessStateHash()).isEqualTo(initialHash);
        assertThat(funded.orders()).isEmpty();

        TradingCoreState sufficientlyFunded = funded(ProductLine.SPOT, "USDT", 1_000);
        TradingCoreState placed = reducer.placeOrder(sufficientlyFunded, 101,
                order(1, CoreOrderSide.BUY, ReservationKind.SPOT_ASSET, "USDT", 400));
        assertThatThrownBy(() -> reducer.placeOrder(placed, 101,
                order(1, CoreOrderSide.BUY, ReservationKind.SPOT_ASSET, "USDT", 400)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("DUPLICATE_ORDER_ID"));
        assertBalance(placed, "USDT", 900, 100);
    }

    @Test
    void repeatedCancelDoesNotReleaseTwice() {
        TradingCoreState placed = reducer.placeOrder(funded(ProductLine.SPOT, "USDT", 1_000), 101,
                order(1, CoreOrderSide.BUY, ReservationKind.SPOT_ASSET, "USDT", 400));
        TradingCoreState firstCancel = reducer.cancelOrder(placed, 101, new CancelOrderCommand(1));
        TradingCoreState secondCancel = reducer.cancelOrder(firstCancel, 101, new CancelOrderCommand(1));

        assertThat(secondCancel).isSameAs(firstCancel);
        assertBalance(secondCancel, "USDT", 1_000, 0);
    }

    @Test
    void coreComputesExactReservationAndRejectsUnderstatedPositiveHint() {
        TradingCoreState funded = funded(ProductLine.SPOT, "USDT", 1_000);

        assertThatThrownBy(() -> reducer.placeOrder(funded, 101,
                order(1, CoreOrderSide.BUY, ReservationKind.SPOT_ASSET, "USDT", 50)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INSUFFICIENT_ORDER_RESERVATION"));

        TradingCoreState placed = reducer.placeOrder(funded, 101,
                order(1, CoreOrderSide.BUY, ReservationKind.SPOT_ASSET, "USDT", 0));
        assertBalance(placed, "USDT", 900, 100);
    }

    @Test
    void spotMatchAppliesOrderFeeSnapshotsAndPreservesAssets() {
        TradingCoreState state = funded(ProductLine.SPOT, "USDT", 1_000);
        state = reducer.adjustBalance(state, 202, new BalanceAdjustmentCommand("BTC", 10));
        PlaceOrderCommand makerSell = feeOrder(2, CoreOrderSide.SELL, ReservationKind.SPOT_ASSET,
                "BTC", -50_000, 100_000);
        PlaceOrderCommand takerBuy = feeOrder(1, CoreOrderSide.BUY, ReservationKind.SPOT_ASSET,
                "USDT", 0, 100_000);
        state = reducer.placeOrder(state, 202, makerSell);
        state = reducer.placeOrder(state, 101, takerBuy);

        TradingCoreState matched = reducer.applyMatches(state, 1, "BTC", "USDT",
                List.of(new CoreMatch(2, 202, 10, 10, true, true)));

        assertThat(matched.user(101).totalUnits("USDT")).isEqualTo(890);
        assertThat(matched.user(101).totalUnits("BTC")).isEqualTo(10);
        assertThat(matched.user(202).totalUnits("BTC")).isZero();
        assertThat(matched.user(202).totalUnits("USDT")).isEqualTo(105);
        assertThat(matched.treasuryState().feeBalances()).containsEntry("USDT", 5L);
    }

    @Test
    void derivativeMatchUsesMakerAndTakerFeeSnapshots() {
        TradingCoreState state = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 1_000);
        state = reducer.adjustBalance(state, 202, new BalanceAdjustmentCommand("USDT", 1_000));
        state = reducer.placeOrder(state, 202, feeOrder(2, CoreOrderSide.SELL,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", -100_000, 200_000));
        state = reducer.placeOrder(state, 101, feeOrder(1, CoreOrderSide.BUY,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 0, 200_000));

        TradingCoreState matched = reducer.applyMatches(state, 1, "BTC", "USDT",
                List.of(new CoreMatch(2, 202, 10, 10, true, true)));

        assertThat(matched.user(101).totalUnits("USDT")).isEqualTo(980);
        assertThat(matched.user(202).totalUnits("USDT")).isEqualTo(1_010);
        assertThat(matched.user(101).positions().get("BTC-USDT").positionMarginUnits()).isEqualTo(10);
        assertThat(matched.user(202).positions().get("BTC-USDT").positionMarginUnits()).isEqualTo(10);
        assertThat(matched.treasuryState().feeBalances()).containsEntry("USDT", 10L);
    }

    @Test
    void betterPricedLinearSellFillUsesTheAcceptedReservationWithoutDiverging() {
        TradingCoreState state = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 1_000);
        state = reducer.adjustBalance(state, 202, new BalanceAdjustmentCommand("USDT", 1_000));
        PlaceOrderCommand makerBuy = new PlaceOrderCommand(
                2,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                "USDT",
                CoreOrderSide.BUY,
                20,
                20,
                20,
                20,
                10,
                false,
                CoreMarginMode.CROSS,
                CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN,
                "USDT",
                0,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                false,
                "maker-2",
                0,
                0
        );
        PlaceOrderCommand takerSell = new PlaceOrderCommand(
                1,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                "USDT",
                CoreOrderSide.SELL,
                10,
                10,
                10,
                10,
                10,
                false,
                CoreMarginMode.CROSS,
                CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN,
                "USDT",
                0,
                com.surprising.aeron.protocol.CoreOrderType.MARKET,
                com.surprising.aeron.protocol.CoreTimeInForce.IOC,
                false,
                "taker-1",
                0,
                0
        );
        state = reducer.placeOrder(state, 202, makerBuy);
        state = reducer.placeOrder(state, 101, takerSell);

        TradingCoreState matched = reducer.applyMatches(state, 1, "BTC", "USDT",
                List.of(new CoreMatch(2, 202, 20, 10, true, true)));

        assertThat(matched.order(1).status()).isEqualTo(CoreOrderStatus.FILLED);
        assertThat(matched.user(101).positions().get("BTC-USDT").positionMarginUnits()).isEqualTo(10);
        assertThat(matched.user(101).balances().get("USDT")).isEqualTo(new AssetBalance("USDT", 990, 10));
        assertThat(matched.user(101).totalUnits("USDT") + matched.user(202).totalUnits("USDT"))
                .isEqualTo(2_000);
    }

    @Test
    void higherAskPlacedBeforeLowerAskCannotLeaveTheLowerFillUnderReserved() {
        TradingCoreState state = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 1_000);
        state = reducer.adjustBalance(state, 202, new BalanceAdjustmentCommand("USDT", 1_000));
        PlaceOrderCommand higherMakerAsk = new PlaceOrderCommand(
                2,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                "USDT",
                CoreOrderSide.SELL,
                20,
                20,
                20,
                20,
                10,
                false,
                CoreMarginMode.CROSS,
                CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN,
                "USDT",
                0,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                false,
                "maker-high",
                0,
                0
        );
        PlaceOrderCommand lowerMakerAsk = new PlaceOrderCommand(
                3,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                "USDT",
                CoreOrderSide.SELL,
                10,
                10,
                10,
                10,
                10,
                false,
                CoreMarginMode.CROSS,
                CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN,
                "USDT",
                0,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                false,
                "maker-low",
                0,
                0
        );
        PlaceOrderCommand takerBuy = new PlaceOrderCommand(
                1,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                "USDT",
                CoreOrderSide.BUY,
                10,
                10,
                10,
                10,
                10,
                false,
                CoreMarginMode.CROSS,
                CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN,
                "USDT",
                0,
                com.surprising.aeron.protocol.CoreOrderType.MARKET,
                com.surprising.aeron.protocol.CoreTimeInForce.IOC,
                false,
                "taker",
                0,
                0
        );
        state = reducer.placeOrder(state, 202, higherMakerAsk);
        state = reducer.placeOrder(state, 202, lowerMakerAsk);
        state = reducer.placeOrder(state, 101, takerBuy);

        TradingCoreState matched = reducer.applyMatches(state, 1, "BTC", "USDT",
                List.of(new CoreMatch(3, 202, 10, 10, true, true)));

        assertThat(matched.order(1).status()).isEqualTo(CoreOrderStatus.FILLED);
        assertThat(matched.user(202).positions().get("BTC-USDT").positionMarginUnits()).isEqualTo(10);
        assertThat(matched.user(101).totalUnits("USDT") + matched.user(202).totalUnits("USDT"))
                .isEqualTo(2_000);
    }

    @Test
    void consecutiveAskFillsAllocateMarginAtEachFillPriceWithoutRepricingTheExistingPosition() {
        TradingCoreState state = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 1_000);
        state = reducer.adjustBalance(state, 202, new BalanceAdjustmentCommand("USDT", 1_000));
        state = reducer.adjustBalance(state, 303, new BalanceAdjustmentCommand("USDT", 1_000));
        PlaceOrderCommand lowerMakerAsk = new PlaceOrderCommand(
                2,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                "USDT",
                CoreOrderSide.SELL,
                10,
                10,
                10,
                10,
                10,
                false,
                CoreMarginMode.CROSS,
                CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN,
                "USDT",
                0,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                false,
                "maker-low",
                0,
                0
        );
        PlaceOrderCommand higherMakerAsk = new PlaceOrderCommand(
                3,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                "USDT",
                CoreOrderSide.SELL,
                20,
                20,
                20,
                20,
                10,
                false,
                CoreMarginMode.CROSS,
                CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN,
                "USDT",
                0,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                false,
                "maker-high",
                0,
                0
        );
        PlaceOrderCommand firstTaker = new PlaceOrderCommand(
                1,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                "USDT",
                CoreOrderSide.BUY,
                10,
                10,
                10,
                10,
                10,
                false,
                CoreMarginMode.CROSS,
                CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN,
                "USDT",
                0,
                com.surprising.aeron.protocol.CoreOrderType.MARKET,
                com.surprising.aeron.protocol.CoreTimeInForce.IOC,
                false,
                "taker-1",
                0,
                0
        );
        PlaceOrderCommand secondTaker = new PlaceOrderCommand(
                4,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                "USDT",
                CoreOrderSide.BUY,
                20,
                20,
                20,
                20,
                10,
                false,
                CoreMarginMode.CROSS,
                CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN,
                "USDT",
                0,
                com.surprising.aeron.protocol.CoreOrderType.MARKET,
                com.surprising.aeron.protocol.CoreTimeInForce.IOC,
                false,
                "taker-2",
                0,
                0
        );
        state = reducer.placeOrder(state, 202, lowerMakerAsk);
        state = reducer.placeOrder(state, 202, higherMakerAsk);
        state = reducer.placeOrder(state, 101, firstTaker);
        state = reducer.applyMatches(state, 1, "BTC", "USDT",
                List.of(new CoreMatch(2, 202, 10, 10, true, true)));
        state = reducer.placeOrder(state, 303, secondTaker);

        TradingCoreState matched = reducer.applyMatches(state, 4, "BTC", "USDT",
                List.of(new CoreMatch(3, 202, 20, 10, true, true)));

        assertThat(matched.user(202).positions().get("BTC-USDT").signedQuantitySteps()).isEqualTo(-20);
        assertThat(matched.user(202).positions().get("BTC-USDT").positionMarginUnits()).isEqualTo(30);
        assertThat(matched.user(101).totalUnits("USDT") + matched.user(202).totalUnits("USDT")
                + matched.user(303).totalUnits("USDT")).isEqualTo(3_000);
    }

    @Test
    void addingIntoHigherRiskBracketFreezesAndAllocatesTheExistingPositionDelta() {
        TradingCoreState state = derivativeWithBrackets();
        state = reducer.updateLeverage(state, 101,
                new UpdateLeverageCommand("BTC-USDT", CoreMarginMode.CROSS, 5_000_000L));
        state = reducer.updateLeverage(state, 202,
                new UpdateLeverageCommand("BTC-USDT", CoreMarginMode.CROSS, 5_000_000L));
        state = reducer.adjustBalance(state, 101, new BalanceAdjustmentCommand("USDT", 1_000));
        state = reducer.adjustBalance(state, 202, new BalanceAdjustmentCommand("USDT", 1_000));
        state = reducer.placeOrder(state, 202,
                order(2, CoreOrderSide.SELL, ReservationKind.DERIVATIVE_MARGIN, "USDT", 0));
        state = reducer.placeOrder(state, 101,
                order(1, CoreOrderSide.BUY, ReservationKind.DERIVATIVE_MARGIN, "USDT", 0));
        state = reducer.applyMatches(state, 1, "BTC", "USDT",
                List.of(new CoreMatch(2, 202, 10, 10, true, true)));

        TradingCoreState withMaker = reducer.placeOrder(state, 202,
                new PlaceOrderCommand(
                        3,
                        "BTC-USDT",
                        1,
                        "BTC",
                        "USDT",
                        "USDT",
                        CoreOrderSide.SELL,
                        10,
                        10,
                        10,
                        10,
                        20,
                        false,
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.NET,
                        ReservationKind.DERIVATIVE_MARGIN,
                        "USDT",
                        0,
                        com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                        com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                        false,
                        "",
                        0,
                        0
                ));
        TradingCoreState withAdd = reducer.placeOrder(withMaker, 101,
                new PlaceOrderCommand(
                        4,
                        "BTC-USDT",
                        1,
                        "BTC",
                        "USDT",
                        "USDT",
                        CoreOrderSide.BUY,
                        10,
                        10,
                        10,
                        10,
                        20,
                        false,
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.NET,
                        ReservationKind.DERIVATIVE_MARGIN,
                        "USDT",
                        50,
                        com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                        com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                        false,
                        "",
                        0,
                        0
                ));

        assertThat(withAdd.user(101).balances().get("USDT").lockedUnits()).isEqualTo(60);
        TradingCoreState matched = reducer.applyMatches(withAdd, 4, "BTC", "USDT",
                List.of(new CoreMatch(3, 202, 10, 20, true, true)));
        assertThat(matched.user(101).positions().get("BTC-USDT").positionMarginUnits()).isEqualTo(60);
        assertThat(matched.user(101).balances().get("USDT").lockedUnits()).isEqualTo(60);
    }

    @Test
    void leverageMustMeetTheProjectedRiskBracketMarginRate() {
        TradingCoreState state = derivativeWithBrackets();
        TradingCoreState leveraged = reducer.updateLeverage(state, 101,
                new UpdateLeverageCommand("BTC-USDT", CoreMarginMode.CROSS, 8_000_000L));
        TradingCoreState funded = reducer.adjustBalance(leveraged, 101,
                new BalanceAdjustmentCommand("USDT", 1_000));

        assertThatThrownBy(() -> reducer.placeOrder(funded, 101,
                new PlaceOrderCommand(
                        9,
                        "BTC-USDT",
                        1,
                        "BTC",
                        "USDT",
                        "USDT",
                        CoreOrderSide.BUY,
                        10,
                        10,
                        10,
                        10,
                        30,
                        false,
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.NET,
                        ReservationKind.DERIVATIVE_MARGIN,
                        "USDT",
                        0,
                        com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                        com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                        false,
                        "",
                        0,
                        0
                )))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LEVERAGE_EXCEEDS_RISK_BRACKET"));
    }

    @Test
    void projectedSameSideOrdersCannotExceedCoreInstrumentLimit() {
        TradingCoreState state = derivativeWithRiskPolicy(150, 1_000, 10_000_000L, 150, 10_000_000L);
        state = reducer.adjustBalance(state, 101, new BalanceAdjustmentCommand("USDT", 10_000));
        state = reducer.placeOrder(state, 101,
                order(1, CoreOrderSide.BUY, ReservationKind.DERIVATIVE_MARGIN, "USDT", 0));
        TradingCoreState placed = state;

        assertThatThrownBy(() -> reducer.placeOrder(placed, 101,
                new PlaceOrderCommand(
                        2,
                        "BTC-USDT",
                        1,
                        "BTC",
                        "USDT",
                        "USDT",
                        CoreOrderSide.BUY,
                        10,
                        10,
                        10,
                        10,
                        6,
                        false,
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.NET,
                        ReservationKind.DERIVATIVE_MARGIN,
                        "USDT",
                        0,
                        com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                        com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                        false,
                        "",
                        0,
                        0
                )))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("POSITION_NOTIONAL_LIMIT_EXCEEDED"));
    }

    @Test
    void dynamicOpenInterestFloorAndRiskBracketLeverageAreCoreAuthority() {
        TradingCoreState floorLimited = derivativeWithRiskPolicy(1_000, 80, 1_000_000L,
                1_000, 10_000_000L);
        floorLimited = reducer.adjustBalance(floorLimited, 101, new BalanceAdjustmentCommand("USDT", 10_000));
        TradingCoreState fundedFloorLimited = floorLimited;
        assertThatThrownBy(() -> reducer.placeOrder(fundedFloorLimited, 101,
                order(1, CoreOrderSide.BUY, ReservationKind.DERIVATIVE_MARGIN, "USDT", 0)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("OPEN_INTEREST_LIMIT_EXCEEDED"));

        TradingCoreState leverageLimited = derivativeWithRiskPolicy(1_000, 1_000, 10_000_000L,
                1_000, 5_000_000L);
        leverageLimited = reducer.adjustBalance(leverageLimited, 101,
                new BalanceAdjustmentCommand("USDT", 10_000));
        TradingCoreState fundedLeverageLimited = leverageLimited;
        assertThatThrownBy(() -> reducer.placeOrder(fundedLeverageLimited, 101,
                order(1, CoreOrderSide.BUY, ReservationKind.DERIVATIVE_MARGIN, "USDT", 0)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LEVERAGE_EXCEEDS_RISK_BRACKET"));
    }

    @Test
    void arithmeticOverflowFailsClosed() {
        TradingCoreState maximum = funded(ProductLine.SPOT, "USDT", Long.MAX_VALUE);

        assertThatThrownBy(() -> reducer.adjustBalance(maximum, 101,
                new BalanceAdjustmentCommand("USDT", 1)))
                .isInstanceOf(ArithmeticException.class);
        assertThat(maximum.user(101).totalUnits("USDT")).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void reduceOnlyWaitsForPositionStateInsteadOfBypassingValidation() {
        TradingCoreState funded = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 1_000);
        PlaceOrderCommand reduceOnly = new PlaceOrderCommand(
                1,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                "USDT",
                CoreOrderSide.SELL,
                60_000,
                60_000,
                60_000,
                60_000,
                1,
                true,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN,
                "USDT",
                100,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                false,
                "",
                0,
                0
        );

        assertThatThrownBy(() -> reducer.placeOrder(funded, 101, reduceOnly))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("REDUCE_ONLY_REQUIRES_POSITION_STATE"));
    }

    @Test
    void positionModePersistsAndOpenOrderBlocksSwitch() {
        TradingCoreState funded = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 10_000);
        TradingCoreState hedge = reducer.updatePositionMode(funded, 101,
                new UpdatePositionModeCommand(CorePositionMode.HEDGE));
        PlaceOrderCommand openLong = new PlaceOrderCommand(
                91,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                "USDT",
                CoreOrderSide.BUY,
                10,
                10,
                10,
                10,
                10,
                false,
                CoreMarginMode.ISOLATED,
                CorePositionSide.LONG,
                ReservationKind.DERIVATIVE_MARGIN,
                "USDT",
                2_000,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                false,
                "",
                0,
                0
        );
        TradingCoreState placed = reducer.placeOrder(hedge, 101, openLong);

        assertThat(placed.user(101).positionMode()).isEqualTo(CorePositionMode.HEDGE);
        assertThat(placed.order(91).positionSide()).isEqualTo(CorePositionSide.LONG);
        assertThatThrownBy(() -> reducer.updatePositionMode(placed, 101,
                new UpdatePositionModeCommand(CorePositionMode.ONE_WAY)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("POSITION_MODE_SWITCH_BLOCKED"));
    }

    @Test
    void isolatedMarginAdjustmentMovesFundsWithoutChangingEquity() {
        TradingCoreState funded = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 10_000);
        CoreUserState current = funded.user(101);
        Map<String, AssetBalance> balances = new TreeMap<>(current.balances());
        balances.put("USDT", new AssetBalance("USDT", 9_000, 1_000));
        CorePositionState position = new CorePositionState("BTC-USDT", "USDT", CoreMarginMode.ISOLATED,
                CorePositionSide.NET, 1, 10, 10, 100, 0, 1_000);
        CoreUserState user = new CoreUserState(current.productLine(), current.userId(), current.revision() + 1,
                balances, current.reservations(), Map.of(position.key(), position), current.positionMode());
        Map<Long, CoreUserState> users = new TreeMap<>(funded.users());
        users.put(user.userId(), user);
        TradingCoreState withPosition = new TradingCoreState(funded.productLine(), funded.revision() + 1,
                users, funded.orders(), funded.instruments(), funded.riskState(),
                funded.treasuryState());

        TradingCoreState added = reducer.adjustPositionMargin(withPosition, 101,
                new AdjustPositionMarginCommand("BTC-USDT", CoreMarginMode.ISOLATED,
                        CorePositionSide.NET, 500));
        TradingCoreState removed = reducer.adjustPositionMargin(added, 101,
                new AdjustPositionMarginCommand("BTC-USDT", CoreMarginMode.ISOLATED,
                        CorePositionSide.NET, -300));

        assertThat(added.user(101).totalUnits("USDT")).isEqualTo(10_000);
        assertThat(added.user(101).positions().get("BTC-USDT").positionMarginUnits()).isEqualTo(1_500);
        assertBalance(removed, "USDT", 8_800, 1_200);
        assertThat(removed.user(101).positions().get("BTC-USDT").positionMarginUnits()).isEqualTo(1_200);
        assertThat(TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(removed),
                ProductLine.LINEAR_PERPETUAL)).isEqualTo(removed);
    }

    @Test
    void leverageIsAuthoritativeForReservationAndSurvivesSnapshot() {
        TradingCoreState funded = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 10_000);
        assertThatThrownBy(() -> reducer.updateLeverage(funded, 101,
                new UpdateLeverageCommand("BTC-USDT", CoreMarginMode.CROSS, 20_000_000L)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LEVERAGE_EXCEEDS_INSTRUMENT_LIMIT"));

        TradingCoreState leveraged = reducer.updateLeverage(funded, 101,
                new UpdateLeverageCommand("BTC-USDT", CoreMarginMode.CROSS, 5_000_000L));
        assertThat(StateMapSupport.isDelta(leveraged.leverages())).isTrue();
        PlaceOrderCommand underReserved = new PlaceOrderCommand(
                301,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                "USDT",
                CoreOrderSide.BUY,
                10,
                10,
                10,
                10,
                10,
                false,
                CoreMarginMode.CROSS,
                CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN,
                "USDT",
                19,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                false,
                "",
                0,
                0
        );

        assertThatThrownBy(() -> reducer.placeOrder(leveraged, 101, underReserved))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INSUFFICIENT_ORDER_RESERVATION"));
        assertThat(leveraged.leverages()).containsEntry(
                new CoreLeverageKey(101, "BTC-USDT", CoreMarginMode.CROSS), 5_000_000L);
        assertThat(TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(leveraged),
                ProductLine.LINEAR_PERPETUAL)).isEqualTo(leveraged);
    }

    @Test
    void algoParentUsesAuthoritativeChildOrdersAndSurvivesSnapshot() {
        TradingCoreState funded = funded(ProductLine.LINEAR_PERPETUAL, "USDT", 10_000);
        var initial = algo(501, 101, 1, List.of());
        TradingCoreState created = reducer.upsertAlgoOrder(funded, 101, initial);
        assertThat(StateMapSupport.isDelta(created.algoOrders())).isTrue();
        TradingCoreState withChild = reducer.placeOrder(created, 101,
                order(601, CoreOrderSide.BUY, ReservationKind.DERIVATIVE_MARGIN, "USDT", 100));
        TradingCoreState linked = reducer.upsertAlgoOrder(withChild, 101, algo(501, 101, 2, List.of(601L)));

        assertThat(linked.algoOrders().get(501L).childOrderIds()).containsExactly(601L);
        assertThatThrownBy(() -> reducer.upsertAlgoOrder(linked, 101, algo(501, 101, 2, List.of(601L))))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("STALE_ALGO_ORDER_REVISION"));
        assertThat(TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(linked),
                ProductLine.LINEAR_PERPETUAL)).isEqualTo(linked);
        assertThatThrownBy(() -> reducer.upsertAlgoOrder(withChild, 101,
                algo(501, 101, 2, List.of(999L))))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_ALGO_CHILD"));
    }

    @Test
    void cancelAllAfterUsesRevisionedAeronStateAndSurvivesSnapshot() {
        TradingCoreState state = TradingCoreState.empty(ProductLine.SPOT);
        var set = new com.surprising.aeron.protocol.CoreCancelAllAfterCommand(
                com.surprising.aeron.protocol.CoreCancelAllAfterAction.SET, 101, "BTC-USDT",
                1_000, 2_000, 0, 0, 0, 1_000);
        TradingCoreState active = reducer.updateCancelAllAfter(state, 101, set);
        assertThat(StateMapSupport.isDelta(active.cancelAllAfterTimers())).isTrue();
        CoreCancelAllAfterState activeTimer = active.cancelAllAfterTimers()
                .get(new CoreCancelAllAfterKey(101, "BTC-USDT"));
        var claim = new com.surprising.aeron.protocol.CoreCancelAllAfterCommand(
                com.surprising.aeron.protocol.CoreCancelAllAfterAction.CLAIM, 101, "BTC-USDT",
                1_000, 2_000, activeTimer.revision(), 0, 0, 2_000);
        TradingCoreState triggering = reducer.updateCancelAllAfter(active, 101, claim);
        CoreCancelAllAfterState triggeringTimer = triggering.cancelAllAfterTimers()
                .get(new CoreCancelAllAfterKey(101, "BTC-USDT"));
        var complete = new com.surprising.aeron.protocol.CoreCancelAllAfterCommand(
                com.surprising.aeron.protocol.CoreCancelAllAfterAction.COMPLETE, 101, "BTC-USDT",
                1_000, 2_000, triggeringTimer.revision(), 3, 2, 2_100);
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
                        exception -> assertThat(exception.code()).isEqualTo("STALE_CANCEL_ALL_AFTER_REVISION"));
    }

    private static com.surprising.aeron.protocol.CoreAlgoOrderView algo(
            long id, long userId, long revision, List<Long> children) {
        return new com.surprising.aeron.protocol.CoreAlgoOrderView(id, userId, "algo-client", "BTC-USDT", 0,
                CoreOrderSide.BUY, 0, 100, 10, 1, 10, CoreMarginMode.CROSS, CorePositionSide.NET,
                false, false, com.surprising.aeron.protocol.CoreTimeInForce.IOC, 0, 0, "", "trace",
                1, 1, 0, 1, revision, revision, children, 0, 0, 0);
    }

    private TradingCoreState funded(ProductLine productLine, String asset, long units) {
        return reducer.adjustBalance(CoreStateTestFixtures.withInstrument(reducer, productLine), 101,
                new BalanceAdjustmentCommand(asset, units));
    }

    private static PlaceOrderCommand order(
            long orderId,
            CoreOrderSide side,
            ReservationKind kind,
            String asset,
            long reservedUnits) {
        String settleAsset = kind == ReservationKind.DERIVATIVE_MARGIN ? asset : "USDT";
        return new PlaceOrderCommand(
                orderId,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                settleAsset,
                side,
                10,
                10,
                10,
                10,
                10,
                false,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                kind,
                asset,
                reservedUnits,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                false,
                "",
                0,
                0
        );
    }

    private static PlaceOrderCommand feeOrder(
            long orderId,
            CoreOrderSide side,
            ReservationKind kind,
            String asset,
            long makerFeeRatePpm,
            long takerFeeRatePpm) {
        String settleAsset = kind == ReservationKind.DERIVATIVE_MARGIN ? asset : "USDT";
        return new PlaceOrderCommand(
                orderId,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                settleAsset,
                side,
                10,
                10,
                10,
                10,
                10,
                false,
                CoreMarginMode.CROSS,
                CorePositionSide.NET,
                kind,
                asset,
                0,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                false,
                "",
                makerFeeRatePpm,
                takerFeeRatePpm
        );
    }

    private static Stream<ProductLine> derivativeProductLines() {
        return Stream.of(ProductLine.values()).filter(ProductLine::isDerivative);
    }

    private TradingCoreState derivativeWithRiskPolicy(long maxPosition, long openInterestFloor,
                                                       long openInterestRate, long bracketCap,
                                                       long bracketMaxLeverage) {
        UpsertInstrumentCommand instrument = new UpsertInstrumentCommand("BTC-USDT", 1,
                com.surprising.instrument.api.model.ContractType.LINEAR_PERPETUAL.ordinal(),
                "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0,
                0, -1, 0, 10_000_000L, maxPosition, openInterestRate, openInterestFloor,
                List.of(new CoreRiskLimitBracket(1, 0, bracketCap, bracketMaxLeverage, 100_000, 50_000)));
        return reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), instrument);
    }

    private TradingCoreState derivativeWithBrackets() {
        UpsertInstrumentCommand instrument = new UpsertInstrumentCommand("BTC-USDT", 1,
                com.surprising.instrument.api.model.ContractType.LINEAR_PERPETUAL.ordinal(),
                "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0,
                0, -1, 0, 10_000_000L, 1_000, 10_000_000L, 1_000,
                List.of(new CoreRiskLimitBracket(1, 0, 200, 10_000_000L, 100_000, 50_000),
                        new CoreRiskLimitBracket(2, 200, 1_000, 10_000_000L, 200_000, 100_000)));
        return reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), instrument);
    }

    private static void assertBalance(
            TradingCoreState state,
            String asset,
            long available,
            long locked) {
        AssetBalance balance = state.user(101).balances().get(asset);
        assertThat(balance.availableUnits()).isEqualTo(available);
        assertThat(balance.lockedUnits()).isEqualTo(locked);
    }
}
