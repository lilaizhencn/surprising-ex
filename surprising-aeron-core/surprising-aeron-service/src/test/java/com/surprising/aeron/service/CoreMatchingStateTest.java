package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.state.CoreOrderStatus;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CoreMatchingStateTest {

    @Test
    void spotMatchUpdatesBothUsersFundsOrdersAndRecoverableBookAtomically() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 10)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 5, ReservationKind.SPOT_ASSET, "BTC", 5));
            long restingBookHash = state.tradingState().bookStateHash("BTC-USDT");
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 3, ReservationKind.SPOT_ASSET, "USDT", 300));

            assertThat(state.tradingState().order(101).status()).isEqualTo(CoreOrderStatus.OPEN);
            assertThat(state.tradingState().order(101).remainingQuantitySteps()).isEqualTo(2);
            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.FILLED);
            assertThat(state.tradingState().user(11).totalUnits("BTC")).isEqualTo(7);
            assertThat(state.tradingState().user(11).totalUnits("USDT")).isEqualTo(300);
            assertThat(state.tradingState().user(22).totalUnits("BTC")).isEqualTo(3);
            assertThat(state.tradingState().user(22).totalUnits("USDT")).isEqualTo(700);
            assertThat(state.tradingState().user(11).totalUnits("BTC")
                    + state.tradingState().user(22).totalUnits("BTC")).isEqualTo(10);
            assertThat(state.tradingState().user(11).totalUnits("USDT")
                    + state.tradingState().user(22).totalUnits("USDT")).isEqualTo(1_000);
            assertThat(state.tradingState().bookStateHash("BTC-USDT")).isNotEqualTo(restingBookHash);
            assertThat(state.matchingStateHash()).isNotZero();

            try (CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState()).isEqualTo(state.tradingState());
                assertThat(restored.tradingState().bookStateHash("BTC-USDT"))
                        .isEqualTo(state.tradingState().bookStateHash("BTC-USDT"));
                assertThat(restored.matchingStateHash()).isNotZero();
                apply(restored, 5, 22, CoreMessageType.PLACE_ORDER,
                        place(203, CoreOrderSide.BUY, 100, 2, ReservationKind.SPOT_ASSET, "USDT", 200));
                assertThat(restored.tradingState().order(101).status()).isEqualTo(CoreOrderStatus.FILLED);
                assertThat(restored.tradingState().bookState().openOrders()).isEmpty();
            }
        }
    }

    @ParameterizedTest
    @MethodSource("derivativeLines")
    void nonOptionDerivativeLinesCreatePositionMarginFromMatchedReservations(ProductLine productLine) {
        try (CoreProbeState state = new CoreProbeState(productLine)) {
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));

            assertThat(state.tradingState().orders().values())
                    .allMatch(order -> order.status() == CoreOrderStatus.FILLED);
            assertThat(state.tradingState().bookState().openOrders()).isEmpty();
            assertThat(state.tradingState().user(11).positions().get("BTC-USDT").signedQuantitySteps())
                    .isEqualTo(-2);
            assertThat(state.tradingState().user(22).positions().get("BTC-USDT").signedQuantitySteps())
                    .isEqualTo(2);
            assertThat(state.tradingState().user(11).balances().get("USDT").lockedUnits()).isEqualTo(200);
            assertThat(state.tradingState().user(22).balances().get("USDT").lockedUnits()).isEqualTo(200);
        }
    }

    @Test
    void optionFillFailsClosedUntilPremiumSettlementIsAuthoritative() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.OPTION)) {
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            long businessHashBefore = state.tradingState().businessStateHash();
            CoreMessage buy = message(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));

            var result = state.apply(buy);

            assertThat(result.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(result.resultCode().name()).isEqualTo("OPTION_MATCH_REQUIRES_PREMIUM_MODEL");
            assertThat(state.tradingState().businessStateHash()).isEqualTo(businessHashBefore);
            assertThat(state.tradingState().order(202)).isNull();
            assertThat(state.tradingState().order(101).status()).isEqualTo(CoreOrderStatus.OPEN);
        }
    }

    @Test
    void replaceLosesPriorityCanMatchAndRebuildsToSameExchangeCoreHash() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 10)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 110, 2, ReservationKind.SPOT_ASSET, "BTC", 2));
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.SPOT_ASSET, "USDT", 200));
            apply(state, 5, 22, CoreMessageType.REPLACE_ORDER,
                    TradingCommandCodec.encodeReplaceOrder(new ReplaceOrderCommand(202, "BTC", "USDT", 110, 220)));

            assertThat(state.tradingState().bookState().openOrders()).isEmpty();
            assertThat(state.tradingState().order(101).status()).isEqualTo(CoreOrderStatus.FILLED);
            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.FILLED);
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState().bookState().openOrders()).isEmpty();
            }
        }
    }

    private static byte[] place(
            long orderId,
            CoreOrderSide side,
            long priceTicks,
            long quantitySteps,
            ReservationKind reservationKind,
            String reservationAsset,
            long reservedUnits) {
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "BTC-USDT", 1,
                "BTC", "USDT", "USDT", side, priceTicks, quantitySteps, false,
                reservationKind, reservationAsset, reservedUnits));
    }

    private static void apply(
            CoreProbeState state,
            long sequence,
            long userId,
            CoreMessageType messageType,
            byte[] payload) {
        CoreMessage message = message(state, sequence, userId, messageType, payload);
        assertThat(state.apply(message).status()).isEqualTo(ResponseStatus.APPLIED);
    }

    private static CoreMessage message(CoreProbeState state, long sequence, long userId,
                                       CoreMessageType messageType, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(messageType, UUID.randomUUID(),
                state.productLine(), CommandSource.GATEWAY, 77, sequence, userId, 1_000, sequence), payload);
    }

    private static Stream<ProductLine> derivativeLines() {
        return Stream.of(ProductLine.values())
                .filter(ProductLine::isDerivative)
                .filter(productLine -> !productLine.isOptionProduct());
    }
}
