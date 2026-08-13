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
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
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

        assertBalance(placed, "USDT", 7_000, 3_000);
        assertThat(placed.user(101).totalUnits("USDT")).isEqualTo(10_000);
        assertThat(placed.user(101).reservations().get(1L).remainingUnits()).isEqualTo(3_000);
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

        assertBalance(placed, settleAsset, 8_000, 2_000);
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
                new PlaceOrderCommand(1, "BTC-USD", 1, "BTC", "USD", "BTC",
                        CoreOrderSide.BUY, 60_000, 1, false,
                        ReservationKind.DERIVATIVE_MARGIN, "USDT", 100)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("INVALID_DERIVATIVE_RESERVATION_ASSET"));
    }

    @Test
    void insufficientFundsAndDuplicateOrderLeavePriorStateUntouched() {
        TradingCoreState funded = funded(ProductLine.SPOT, "USDT", 1_000);
        long initialHash = funded.businessStateHash();

        assertThatThrownBy(() -> reducer.placeOrder(funded, 101,
                order(1, CoreOrderSide.BUY, ReservationKind.SPOT_ASSET, "USDT", 2_000)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INSUFFICIENT_AVAILABLE_BALANCE"));
        assertThat(funded.businessStateHash()).isEqualTo(initialHash);
        assertThat(funded.orders()).isEmpty();

        TradingCoreState placed = reducer.placeOrder(funded, 101,
                order(1, CoreOrderSide.BUY, ReservationKind.SPOT_ASSET, "USDT", 400));
        assertThatThrownBy(() -> reducer.placeOrder(placed, 101,
                order(1, CoreOrderSide.BUY, ReservationKind.SPOT_ASSET, "USDT", 400)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("DUPLICATE_ORDER_ID"));
        assertBalance(placed, "USDT", 600, 400);
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
        PlaceOrderCommand reduceOnly = new PlaceOrderCommand(1, "BTC-USDT", 1, "BTC", "USDT", "USDT",
                CoreOrderSide.SELL, 60_000, 1, true,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 100);

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
        PlaceOrderCommand openLong = new PlaceOrderCommand(91, "BTC-USDT", 1, "BTC", "USDT", "USDT",
                CoreOrderSide.BUY, 10, 10, false, CoreMarginMode.ISOLATED, CorePositionSide.LONG,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 2_000);
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
                users, funded.orders(), funded.bookState(), funded.instruments(), funded.riskState(),
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
        return new PlaceOrderCommand(orderId, "BTC-USDT", 1, "BTC", "USDT", settleAsset, side,
                10, 10, false, kind, asset, reservedUnits);
    }

    private static Stream<ProductLine> derivativeProductLines() {
        return Stream.of(ProductLine.values()).filter(ProductLine::isDerivative);
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
