package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.product.api.ProductLine;
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
                order(2, CoreOrderSide.SELL, ReservationKind.SPOT_ASSET, "BTC", 5));

        assertBalance(placed, "BTC", 15, 5);
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
        TradingCoreState funded = funded(ProductLine.INVERSE_PERPETUAL, "USDT", 1_000);

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

    private TradingCoreState funded(ProductLine productLine, String asset, long units) {
        return reducer.adjustBalance(TradingCoreState.empty(productLine), 101,
                new BalanceAdjustmentCommand(asset, units));
    }

    private static PlaceOrderCommand order(
            long orderId,
            CoreOrderSide side,
            ReservationKind kind,
            String asset,
            long reservedUnits) {
        String settleAsset = asset.equals("BTC") ? "BTC" : "USDT";
        return new PlaceOrderCommand(orderId, "BTC-USDT", 1, "BTC", "USDT", settleAsset, side,
                60_000, 10, false, kind, asset, reservedUnits);
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
