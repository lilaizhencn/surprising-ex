package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.aeron.service.state.CoreOrderStatus;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CoreMatchingStateTest {

    @Test
    void iocPartialFillCancelsRemainderAndReleasesUnusedFunds() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 2)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 500)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.SPOT_ASSET, "BTC", 2));
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 5, ReservationKind.SPOT_ASSET, "USDT", 500,
                            CoreOrderType.LIMIT, CoreTimeInForce.IOC, 100, false));

            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.CANCELED);
            assertThat(state.tradingState().order(202).executedQuantitySteps()).isEqualTo(2);
            assertThat(state.tradingState().user(22).balances().get("USDT").availableUnits()).isEqualTo(300);
            assertThat(state.tradingState().user(22).balances().get("USDT").lockedUnits()).isZero();
            assertThat(state.tradingState().user(22).totalUnits("BTC")).isEqualTo(2);
            assertThat(state.tradingState().bookState().openOrders()).isEmpty();
        }
    }

    @Test
    void postOnlyRejectionLeavesNoOrderOrReservedFunds() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 2)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 500)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.SPOT_ASSET, "BTC", 2));

            CoreMessage crossingPostOnly = message(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 1, ReservationKind.SPOT_ASSET, "USDT", 100,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTX, 100, true));
            assertThat(state.apply(crossingPostOnly).status()).isEqualTo(ResponseStatus.REJECTED);

            assertThat(state.tradingState().order(202)).isNull();
            assertThat(state.tradingState().user(22).balances().get("USDT").availableUnits()).isEqualTo(500);
            assertThat(state.tradingState().user(22).balances().get("USDT").lockedUnits()).isZero();
            assertThat(state.tradingState().bookState().openOrders()).containsOnlyKeys(101L);
        }
    }

    @Test
    void marketOrderUsesProtectionPriceAndNeverRestsOnBook() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 1)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 1, ReservationKind.SPOT_ASSET, "BTC", 1));
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 0, 1, ReservationKind.SPOT_ASSET, "USDT", 100,
                            CoreOrderType.MARKET, CoreTimeInForce.IOC, 100, false));

            assertThat(state.tradingState().order(202).priceTicks()).isZero();
            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.FILLED);
            assertThat(state.tradingState().user(22).balances().get("USDT").lockedUnits()).isZero();
            assertThat(state.tradingState().bookState().openOrders()).isEmpty();
        }
    }

    @Test
    void spotMatchUpdatesBothUsersFundsOrdersAndRecoverableBookAtomically() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applyInstrument(state);
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
            applyInstrument(state);
            String settleAsset = settleAsset(productLine);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(settleAsset, 1_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(settleAsset, 1_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.DERIVATIVE_MARGIN, settleAsset, 200));
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.DERIVATIVE_MARGIN, settleAsset, 200));

            assertThat(state.tradingState().orders().values())
                    .allMatch(order -> order.status() == CoreOrderStatus.FILLED);
            assertThat(state.tradingState().bookState().openOrders()).isEmpty();
            assertThat(state.tradingState().user(11).positions().get("BTC-USDT").signedQuantitySteps())
                    .isEqualTo(-2);
            assertThat(state.tradingState().user(22).positions().get("BTC-USDT").signedQuantitySteps())
                    .isEqualTo(2);
            assertThat(state.tradingState().user(11).balances().get(settleAsset).lockedUnits()).isPositive();
            assertThat(state.tradingState().user(22).balances().get(settleAsset).lockedUnits()).isPositive();
        }
    }

    @Test
    void optionFillTransfersPremiumAndCreatesBuyerSellerPositions() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.OPTION)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 300));
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));

            assertThat(state.tradingState().order(101).status()).isEqualTo(CoreOrderStatus.FILLED);
            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.FILLED);
            assertThat(state.tradingState().user(11).positions().get("BTC-USDT").signedQuantitySteps()).isEqualTo(-2);
            assertThat(state.tradingState().user(22).positions().get("BTC-USDT").signedQuantitySteps()).isEqualTo(2);
            assertThat(state.tradingState().user(11).totalUnits("USDT")).isEqualTo(1_200);
            assertThat(state.tradingState().user(22).totalUnits("USDT")).isEqualTo(800);
        }
    }

    @Test
    void derivativeCloseReverseAndReduceOnlyCapacityAreAuthoritative() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));

            CoreMessage tooLarge = message(state, 5, 22, CoreMessageType.PLACE_ORDER,
                    place(203, CoreOrderSide.SELL, 110, 3, true,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1));
            assertThat(state.apply(tooLarge).resultCode().name()).isEqualTo("REDUCE_ONLY_CAPACITY_EXCEEDED");

            apply(state, 6, 11, CoreMessageType.PLACE_ORDER,
                    place(301, CoreOrderSide.BUY, 110, 3,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 40));
            apply(state, 7, 22, CoreMessageType.PLACE_ORDER,
                    place(302, CoreOrderSide.SELL, 110, 3,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 40));

            assertThat(state.tradingState().user(11).positions().get("BTC-USDT").signedQuantitySteps()).isEqualTo(1);
            assertThat(state.tradingState().user(22).positions().get("BTC-USDT").signedQuantitySteps()).isEqualTo(-1);
            assertThat(state.tradingState().user(11).positions().get("BTC-USDT").entryPriceTicks()).isEqualTo(110);
            assertThat(state.tradingState().user(22).positions().get("BTC-USDT").entryPriceTicks()).isEqualTo(110);
            long total = state.tradingState().user(11).totalUnits("USDT")
                    + state.tradingState().user(22).totalUnits("USDT")
                    + state.tradingState().treasuryState().insuranceBalances().getOrDefault("USDT", 0L);
            assertThat(total).isEqualTo(4_000);
        }
    }

    @Test
    void replaceLosesPriorityCanMatchAndRebuildsToSameExchangeCoreHash() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applyInstrument(state);
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
        String settleAsset = reservationKind == ReservationKind.DERIVATIVE_MARGIN ? reservationAsset : "USDT";
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "BTC-USDT", 1,
                "BTC", "USDT", settleAsset, side, priceTicks, quantitySteps, false,
                reservationKind, reservationAsset, reservedUnits));
    }

    private static byte[] place(
            long orderId,
            CoreOrderSide side,
            long priceTicks,
            long quantitySteps,
            ReservationKind reservationKind,
            String reservationAsset,
            long reservedUnits,
            CoreOrderType orderType,
            CoreTimeInForce timeInForce,
            long matchingPriceTicks,
            boolean postOnly) {
        String settleAsset = reservationKind == ReservationKind.DERIVATIVE_MARGIN ? reservationAsset : "USDT";
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "BTC-USDT", 1,
                "BTC", "USDT", settleAsset, side, priceTicks, quantitySteps, false,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                reservationKind, reservationAsset, reservedUnits, orderType, timeInForce,
                matchingPriceTicks, postOnly));
    }

    private static byte[] place(
            long orderId,
            CoreOrderSide side,
            long priceTicks,
            long quantitySteps,
            boolean reduceOnly,
            ReservationKind reservationKind,
            String reservationAsset,
            long reservedUnits) {
        String settleAsset = reservationKind == ReservationKind.DERIVATIVE_MARGIN ? reservationAsset : "USDT";
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "BTC-USDT", 1,
                "BTC", "USDT", settleAsset, side, priceTicks, quantitySteps, reduceOnly,
                reservationKind, reservationAsset, reservedUnits));
    }

    private static void applyInstrument(CoreProbeState state) {
        ProductLine productLine = state.productLine();
        ContractType type = ContractType.valueOf(productLine.contractTypeCode());
        long expiry = type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0;
        UpsertInstrumentCommand instrument = new UpsertInstrumentCommand("BTC-USDT", 1, type.ordinal(),
                "BTC", "USDT", settleAsset(productLine), 1, 1, type.isInverse() ? 1_000 : 1,
                100_000, 50_000, 0, 0, expiry, type.isOption() ? 0 : -1, type.isOption() ? 100 : 0);
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.UPSERT_INSTRUMENT,
                UUID.randomUUID(), productLine, CommandSource.OPERATIONS, 88, 1, 1,
                1_000, 1), TradingCommandCodec.encodeUpsertInstrument(instrument));
        assertThat(state.apply(message).status()).isEqualTo(ResponseStatus.APPLIED);
    }

    private static String settleAsset(ProductLine productLine) {
        return productLine == ProductLine.INVERSE_PERPETUAL || productLine == ProductLine.INVERSE_DELIVERY
                ? "BTC" : "USDT";
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
