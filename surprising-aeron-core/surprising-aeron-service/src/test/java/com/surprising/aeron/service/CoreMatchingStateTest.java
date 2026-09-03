package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.aeron.service.state.CoreOrderState;
import com.surprising.aeron.service.state.CoreOrderStatus;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CoreMatchingStateTest {

    @Test
    void inFlightIdempotencyUsesPendingIndexWithoutPollutingTerminalResultLedger() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 7, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 500)));
            CoreMessage command = message(state, 2, 7, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.BUY, 100, 1,
                            ReservationKind.SPOT_ASSET, "USDT", 100));

            CoreResponse pending = state.apply(command);
            CoreResponse duplicate = state.apply(command);
            CoreMessage conflicting = new CoreMessage(command.header(),
                    place(102, CoreOrderSide.BUY, 100, 1,
                            ReservationKind.SPOT_ASSET, "USDT", 100));
            CoreResponse conflict = state.apply(conflicting);

            assertThat(state.commandResults()).doesNotContainKey(command.header().commandId());
            assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(duplicate.commandStatus()).isEqualTo(ResponseStatus.OK);
            assertThat(duplicate.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            assertThat(duplicate.appliedCommandCount()).isEqualTo(pending.appliedCommandCount());
            assertThat(duplicate.stateHash()).isEqualTo(pending.stateHash());
            assertThat(conflict.resultCode()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);

            CoreResponse completed = drainMatching(state, pending, command);
            assertThat(completed.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.commandResults()).containsKey(command.header().commandId());
        }
    }

    @Test
    void crossLaneIocPartialFillCommitsBothOwnersAndReleasesUnusedFunds() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 7, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 2)));
            apply(state, 2, 8, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 500)));
            apply(state, 3, 7, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.SPOT_ASSET, "BTC", 2));
            CoreMessage crossing = message(state, 4, 8, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 5, ReservationKind.SPOT_ASSET, "USDT", 500,
                            CoreOrderType.LIMIT, CoreTimeInForce.IOC, 100, false));
            long committedBefore = state.committedCoreSequence();
            CoreResponse pending = state.apply(crossing);

            assertThat(pending.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            assertThat(state.committedCoreSequence()).isEqualTo(committedBefore);

            CoreResponse completed = drainMatching(state, pending, crossing);
            assertThat(completed.status()).isEqualTo(ResponseStatus.APPLIED);

            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.CANCELED);
            assertThat(state.tradingState().order(202).executedQuantitySteps()).isEqualTo(2);
            assertThat(state.tradingState().user(8).balances().get("USDT").availableUnits()).isEqualTo(300);
            assertThat(state.tradingState().user(8).balances().get("USDT").lockedUnits()).isZero();
            assertThat(state.tradingState().user(8).totalUnits("BTC")).isEqualTo(2);
            CoreLaneMetrics metrics = state.laneMetrics();
            assertThat(metrics.accountLaneAppliedSequences()[0]).isEqualTo(state.snapshotProjectionSequence());
            assertThat(metrics.accountLaneCommittedSequences()[0]).isEqualTo(state.snapshotProjectionSequence());
            assertThat(metrics.accountLaneAppliedSequences()[2]).isEqualTo(state.snapshotProjectionSequence());
            assertThat(metrics.accountLaneCommittedSequences()[2]).isEqualTo(state.snapshotProjectionSequence());
            assertThat(state.tradingState().orders().values())
                    .noneMatch(order -> order.status() == CoreOrderStatus.OPEN);
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
            CoreResponse pending = state.apply(crossingPostOnly);
            CoreResponse completed = drainMatching(state, pending, crossingPostOnly);
            assertThat(completed.status()).isEqualTo(ResponseStatus.REJECTED);

            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.REJECTED);
            assertThat(state.tradingState().user(22).balances().get("USDT").availableUnits()).isEqualTo(500);
            assertThat(state.tradingState().user(22).balances().get("USDT").lockedUnits()).isZero();
            assertThat(state.tradingState().orders().values())
                    .filteredOn(order -> order.status() == CoreOrderStatus.OPEN)
                    .extracting(CoreOrderState::orderId)
                    .containsExactly(101L);
        }
    }

    @Test
    void finalMatcherFactCarriesTheRestingOrderWithoutExportingPendingState() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 500)));
            CoreMessage order = message(state, 2, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.SPOT_ASSET, "USDT", 200));

            CoreResponse completed = drainMatching(state, state.apply(order), order);

            assertThat(completed.status()).isEqualTo(ResponseStatus.APPLIED);
            state.exportState().pending();
            CoreMessage exportQuery = new CoreMessage(CoreMessageHeader.query(
                    CoreMessageType.EXPORT_BATCH_QUERY, UUID.randomUUID(), ProductLine.SPOT,
                    CommandSource.OPERATIONS, 88, 0, 0, 3, 3), CoreExportCodec.encodeBatchQuery(20));
            var events = CoreExportCodec.decodeBatchResponse(state.apply(exportQuery).data()).events().stream()
                    .map(message -> CoreExportCodec.decodeEvent(message.payload()))
                    .filter(event -> event.commandId().equals(order.header().commandId()))
                    .toList();

            assertThat(events).singleElement()
                    .satisfies(event -> assertThat(event.resultCode()).isEqualTo(CoreResultCode.NONE));
            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.OPEN);
            assertThat(events.getFirst().terminalIds().orderIds()).isEmpty();
        }
    }

    @Test
    void marketOrderUsesProtectionPriceAndNeverRestsOnBook() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 1)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 101)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 1, ReservationKind.SPOT_ASSET, "BTC", 1));
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 0, 1, ReservationKind.SPOT_ASSET, "USDT", 100,
                            CoreOrderType.MARKET, CoreTimeInForce.IOC, 100, false));

            assertThat(state.tradingState().order(202).priceTicks()).isZero();
            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.FILLED);
            assertThat(state.tradingState().user(22).balances().get("USDT").lockedUnits()).isZero();
            assertThat(state.tradingState().orders().values())
                    .noneMatch(order -> order.status() == CoreOrderStatus.OPEN);
        }
    }

    @Test
    void spotMatchUpdatesBothUsersFundsOrdersAndNativeMatcherAtomically() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 10)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 5, ReservationKind.SPOT_ASSET, "BTC", 5));
            int restingBookHash = awaitMatchingHash(state);
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
            int matchedBookHash = awaitMatchingHash(state);
            assertThat(matchedBookHash).isNotEqualTo(restingBookHash).isNotZero();

            try (CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState()).isEqualTo(state.tradingState());
                assertThat(awaitMatchingHash(restored)).isEqualTo(matchedBookHash);
                apply(restored, 5, 22, CoreMessageType.PLACE_ORDER,
                        place(203, CoreOrderSide.BUY, 100, 2, ReservationKind.SPOT_ASSET, "USDT", 200));
                assertThat(restored.tradingState().order(101).status()).isEqualTo(CoreOrderStatus.FILLED);
                assertThat(restored.tradingState().orders().values())
                        .noneMatch(order -> order.status() == CoreOrderStatus.OPEN);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allProductLines")
    void sameUserCrossCancelsTheRestingOrderBeforeSubmittingTheTaker(ProductLine productLine) {
        try (CoreProbeState state = new CoreProbeState(productLine)) {
            applyInstrument(state);
            ReservationKind reservationKind = productLine == ProductLine.SPOT
                    ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN;
            String sellerAsset = productLine == ProductLine.SPOT ? "BTC" : settleAsset(productLine);
            String buyerAsset = productLine == ProductLine.SPOT ? "USDT" : settleAsset(productLine);
            long initialSellerUnits = productLine == ProductLine.SPOT ? 10 : 20_000;
            long initialBuyerUnits = productLine == ProductLine.SPOT ? 1_000 : 20_000;
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(
                            new BalanceAdjustmentCommand(sellerAsset, initialSellerUnits)));
            if (productLine == ProductLine.SPOT) {
                apply(state, 2, 11, CoreMessageType.ADJUST_BALANCE,
                        TradingCommandCodec.encodeBalanceAdjustment(
                                new BalanceAdjustmentCommand(buyerAsset, initialBuyerUnits)));
            }
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 5, reservationKind, sellerAsset,
                            productLine == ProductLine.SPOT ? 5 : 1_000));

            apply(state, 4, 11, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 3, reservationKind, buyerAsset,
                            productLine == ProductLine.SPOT ? 300 : 1_000));

            assertThat(state.tradingState().order(101).status()).isEqualTo(CoreOrderStatus.CANCELED);
            assertThat(state.tradingState().order(101).executedQuantitySteps()).isZero();
            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.OPEN);
            assertThat(state.tradingState().order(202).executedQuantitySteps()).isZero();
            if (productLine == ProductLine.SPOT) {
                assertThat(state.tradingState().user(11).balances().get("BTC").availableUnits()).isEqualTo(10);
                assertThat(state.tradingState().user(11).balances().get("BTC").lockedUnits()).isZero();
                assertThat(state.tradingState().user(11).balances().get("USDT").availableUnits()).isEqualTo(700);
                assertThat(state.tradingState().user(11).balances().get("USDT").lockedUnits()).isEqualTo(300);
            } else {
                var balance = state.tradingState().user(11).balances().get(buyerAsset);
                assertThat(balance.lockedUnits()).isPositive();
                assertThat(Math.addExact(balance.availableUnits(), balance.lockedUnits())).isEqualTo(20_000);
                assertThat(state.tradingState().user(11).positions()).isEmpty();
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
            assertThat(state.tradingState().orders().values())
                    .noneMatch(order -> order.status() == CoreOrderStatus.OPEN);
            assertThat(state.tradingState().user(11).positions().get("BTC-USDT").signedQuantitySteps())
                    .isEqualTo(-2);
            assertThat(state.tradingState().user(22).positions().get("BTC-USDT").signedQuantitySteps())
                    .isEqualTo(2);
            assertThat(state.tradingState().user(11).balances().get(settleAsset).lockedUnits()).isPositive();
            assertThat(state.tradingState().user(22).balances().get(settleAsset).lockedUnits()).isPositive();
        }
    }

    @Test
    void multiMakerPerpetualSettlementPublishesOnlyAfterTheUnifiedLaneBarrier() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state);
            long sequence = 1;
            for (long makerId = 11; makerId < 19; makerId++) {
                apply(state, sequence++, makerId, CoreMessageType.ADJUST_BALANCE,
                        TradingCommandCodec.encodeBalanceAdjustment(
                                new BalanceAdjustmentCommand("USDT", 2_000)));
                apply(state, sequence++, makerId, CoreMessageType.PLACE_ORDER,
                        place(1_000 + makerId, CoreOrderSide.SELL, 100, 1,
                                ReservationKind.DERIVATIVE_MARGIN, "USDT", 100));
            }
            apply(state, sequence++, 99, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(
                            new BalanceAdjustmentCommand("USDT", 2_000)));
            CoreMessage taker = message(state, sequence, 99, CoreMessageType.PLACE_ORDER,
                    place(2_000, CoreOrderSide.BUY, 100, 8,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 800));
            CoreResponse pending = state.apply(taker);
            assertThat(pending.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            long matchingSequence = state.matchingSequence(taker.header().commandId());
            var matching = state.awaitMatchingResult(matchingSequence);
            assertThat(matching).isNotNull();
            CoreResponse completed = completeUntilTerminalOrFailure(state, matchingSequence, matching,
                    taker.header().submittedAtEpochMillis(), taker.header().sourceSequence());
            assertThat(completed).isNotNull();
            assertThat(completed.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().user(99).positions()).isNotEmpty();
            for (long makerId = 11; makerId < 19; makerId++) {
                assertThat(state.tradingState().user(makerId).positions()).isNotEmpty();
            }
            assertThat(state.tradingState().user(99).positions().get("BTC-USDT").signedQuantitySteps())
                    .isEqualTo(8);
            for (long makerId = 11; makerId < 19; makerId++) {
                assertThat(state.tradingState().user(makerId).positions().get("BTC-USDT").signedQuantitySteps())
                        .isEqualTo(-1);
            }
            assertThat(total(state, "USDT")).isEqualTo(18_000);
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
            assertThat(drainMatching(state, state.apply(tooLarge), tooLarge).resultCode().name())
                    .isEqualTo("REDUCE_ONLY_CAPACITY_EXCEEDED");

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
    void coreDerivesTheFullDerivativeCloseReservation() {
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

            CoreMessage close = message(state, 5, 22, CoreMessageType.PLACE_ORDER,
                    place(203, CoreOrderSide.SELL, 110, 1, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1));

            CoreResponse response = drainMatching(state, state.apply(close), close);

            assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().order(203).status()).isEqualTo(CoreOrderStatus.OPEN);
            assertThat(state.tradingState().user(22).reservations().get(203L).reservedUnits()).isGreaterThan(1);
            assertThat(state.tradingState().user(22).positions().get("BTC-USDT").signedQuantitySteps())
                    .isEqualTo(2);
        }
    }

    @Test
    void normalCloseCancelsNewestConflictingReduceOnlyOrderBeforeMatcherSubmission() {
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
            apply(state, 5, 22, CoreMessageType.PLACE_ORDER,
                    place(203, CoreOrderSide.SELL, 110, 1, true,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1));
            apply(state, 6, 22, CoreMessageType.PLACE_ORDER,
                    place(204, CoreOrderSide.SELL, 120, 1, true,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1));

            apply(state, 7, 22, CoreMessageType.PLACE_ORDER,
                    place(205, CoreOrderSide.SELL, 130, 1, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));

            assertThat(state.tradingState().order(203).status()).isEqualTo(CoreOrderStatus.OPEN);
            assertThat(state.tradingState().order(204).status()).isEqualTo(CoreOrderStatus.CANCELED);
            assertThat(state.tradingState().order(205).status()).isEqualTo(CoreOrderStatus.OPEN);
            apply(state, 8, 11, CoreMessageType.PLACE_ORDER,
                    placeWithFees(301, CoreOrderSide.BUY, 130, 2, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 200,
                            CoreOrderType.LIMIT, CoreTimeInForce.IOC, 130, false, 0, 0));
            assertThat(state.tradingState().order(203).status()).isEqualTo(CoreOrderStatus.FILLED);
            assertThat(state.tradingState().order(204).status()).isEqualTo(CoreOrderStatus.CANCELED);
            assertThat(state.tradingState().order(205).status()).isEqualTo(CoreOrderStatus.FILLED);
            assertThat(state.tradingState().user(22).positions().get("BTC-USDT").signedQuantitySteps()).isZero();
        }
    }

    @Test
    void linearPerpetualMatchConservesFundsWithMakerTakerFees() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state, -50_000, 100_000);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    placeWithFees(101, CoreOrderSide.SELL, 100, 2, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 200,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, 100, false, -50_000, 0));

            long fundsBefore = total(state, "USDT");
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    placeWithFees(202, CoreOrderSide.BUY, 100, 2, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 200,
                            CoreOrderType.LIMIT, CoreTimeInForce.IOC, 100, false, 0, 100_000));

            assertThat(state.tradingState().order(101).status()).isEqualTo(CoreOrderStatus.FILLED);
            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.FILLED);
            assertThat(state.tradingState().user(11).positions().get("BTC-USDT").signedQuantitySteps())
                    .isEqualTo(-2);
            assertThat(state.tradingState().user(22).positions().get("BTC-USDT").signedQuantitySteps())
                    .isEqualTo(2);
            assertThat(state.tradingState().treasuryState().feeBalances()).containsEntry("USDT", 10L);
            assertThat(total(state, "USDT")).isEqualTo(fundsBefore);
        }
    }

    @Test
    void linearPerpetualActiveCloseAcceptsBetterFillThanSellMarketProtectionPrice() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 3, 33, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 4, 11, CoreMessageType.PLACE_ORDER,
                    placeWithFees(101, CoreOrderSide.SELL, 100, 1, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1_000,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, 100, false, 0, 100_000));
            apply(state, 5, 22, CoreMessageType.PLACE_ORDER,
                    placeWithFees(202, CoreOrderSide.BUY, 0, 1, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1_000,
                            CoreOrderType.MARKET, CoreTimeInForce.IOC, 100, false, 0, 100_000));
            apply(state, 6, 33, CoreMessageType.PLACE_ORDER,
                    placeWithFees(303, CoreOrderSide.BUY, 110, 1, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1_000,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, 110, false, 0, 100_000));

            long fundsBeforeClose = total(state, "USDT");
            apply(state, 7, 22, CoreMessageType.PLACE_ORDER,
                    placeWithFees(204, CoreOrderSide.SELL, 0, 1, true,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1_000,
                            CoreOrderType.MARKET, CoreTimeInForce.IOC, 90, false, 0, 100_000));

            assertThat(state.tradingState().order(204).status()).isEqualTo(CoreOrderStatus.FILLED);
            assertThat(state.tradingState().user(22).positions().get("BTC-USDT").signedQuantitySteps()).isZero();
            assertThat(state.tradingState().user(22).balances().get("USDT").lockedUnits()).isZero();
            assertThat(state.tradingState().user(22).reservations().get(204L).remainingUnits()).isZero();
            assertThat(total(state, "USDT")).isEqualTo(fundsBeforeClose);
        }
    }

    @ParameterizedTest
    @MethodSource("allProductLines")
    void everyProductLineMatchConservesSettlementFundsWithNonzeroFees(ProductLine productLine) {
        try (CoreProbeState state = new CoreProbeState(productLine)) {
            applyInstrument(state, 100_000, 200_000);
            String settlementAsset = settleAsset(productLine);
            boolean spot = productLine == ProductLine.SPOT;
            ReservationKind reservationKind = spot
                    ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN;
            String sellerAsset = spot ? "BTC" : settlementAsset;
            String buyerAsset = spot ? "USDT" : settlementAsset;
            long wallet = spot ? 1_000 : 20_000;
            long sellerReservation = spot ? 2 : 10_000;
            long buyerReservation = spot ? 240 : 10_000;
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(
                            sellerAsset, spot ? 2 : wallet)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(buyerAsset, wallet)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    placeWithFees(101, CoreOrderSide.SELL, 100, 2, false, reservationKind,
                            sellerAsset, sellerReservation, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 100, false,
                            100_000, 200_000));
            long fundsBefore = total(state, settlementAsset);
            long feeBefore = state.tradingState().treasuryState().feeBalances()
                    .getOrDefault(settlementAsset, 0L);
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    placeWithFees(202, CoreOrderSide.BUY, 100, 2, false, reservationKind,
                            buyerAsset, buyerReservation, CoreOrderType.LIMIT, CoreTimeInForce.IOC,
                            100, false, 100_000, 200_000));

            assertThat(state.tradingState().order(101).status()).isEqualTo(CoreOrderStatus.FILLED);
            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.FILLED);
            assertThat(state.tradingState().treasuryState().feeBalances().getOrDefault(settlementAsset, 0L))
                    .isGreaterThan(feeBefore);
            assertThat(total(state, settlementAsset)).isEqualTo(fundsBefore);
        }
    }

    @Test
    void spotMatchExportsTheChangedTreasuryBalance() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applyInstrument(state, 100_000, 200_000);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 2)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 240)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    placeWithFees(101, CoreOrderSide.SELL, 100, 2, false,
                            ReservationKind.SPOT_ASSET, "BTC", 2,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, 100, false, 100_000, 200_000));
            CoreMessage buyer = message(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    placeWithFees(202, CoreOrderSide.BUY, 100, 2, false,
                            ReservationKind.SPOT_ASSET, "USDT", 240,
                            CoreOrderType.LIMIT, CoreTimeInForce.IOC, 100, false, 100_000, 200_000));

            CoreResponse completed = drainMatching(state, state.apply(buyer), buyer);
            assertThat(completed.status()).isEqualTo(ResponseStatus.APPLIED);
            state.exportState().pending();
            CoreMessage exportQuery = new CoreMessage(CoreMessageHeader.query(
                    CoreMessageType.EXPORT_BATCH_QUERY, UUID.randomUUID(), ProductLine.SPOT,
                    CommandSource.OPERATIONS, 88, 0, 0, 5, 5), CoreExportCodec.encodeBatchQuery(20));
            var event = CoreExportCodec.decodeBatchResponse(state.apply(exportQuery).data()).events().stream()
                    .map(message -> CoreExportCodec.decodeEvent(message.payload()))
                    .filter(value -> value.commandId().equals(buyer.header().commandId()))
                    .max(java.util.Comparator.comparingLong(
                            com.surprising.aeron.protocol.CoreExportEvent::exportSequence))
                    .orElseThrow();

            assertThat(event.fundsPostings()).anySatisfy(posting -> {
                assertThat(posting.asset()).isEqualTo("USDT");
                assertThat(posting.subledger())
                        .isEqualTo(com.surprising.aeron.protocol.CoreFundsPostingView.Subledger.FEE);
                assertThat(posting.units()).isEqualTo(60);
            });
        }
    }

    @Test
    void linearPerpetualPostOnlyRejectionPreservesFundsAndReservations() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            long fundsBefore = total(state, "USDT");

            CoreMessage crossingPostOnly = message(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 200,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTX, 100, true));
            CoreResponse pending = state.apply(crossingPostOnly);
            CoreResponse completed = drainMatching(state, pending, crossingPostOnly);

            assertThat(completed.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.REJECTED);
            assertThat(state.tradingState().user(22).balances().get("USDT").lockedUnits()).isZero();
            assertThat(total(state, "USDT")).isEqualTo(fundsBefore);
        }
    }

    @Test
    void replaceLosesPriorityCanMatchAndRestoresToSameExchangeCoreHash() {
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
                    TradingCommandCodec.encodeReplaceOrder(new ReplaceOrderCommand(202,
                            new PlaceOrderCommand(203, "BTC-USDT", 1, CoreOrderSide.BUY, 110, 2, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, ""))));

            assertThat(state.tradingState().orders().values())
                    .noneMatch(order -> order.status() == CoreOrderStatus.OPEN);
            assertThat(state.tradingState().order(101).status()).isEqualTo(CoreOrderStatus.FILLED);
            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.CANCELED);
            assertThat(state.tradingState().order(203).status()).isEqualTo(CoreOrderStatus.FILLED);
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState().orders().values())
                        .noneMatch(order -> order.status() == CoreOrderStatus.OPEN);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allProductLines")
    void preservesFifoAcrossNativeSnapshotWithoutCoreBookState(ProductLine productLine) {
        try (CoreProbeState state = new CoreProbeState(productLine)) {
            applyInstrument(state);
            ReservationKind reservationKind = productLine == ProductLine.SPOT
                    ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN;
            String sellerAsset = productLine == ProductLine.SPOT ? "BTC" : settleAsset(productLine);
            String buyerAsset = productLine == ProductLine.SPOT ? "USDT" : settleAsset(productLine);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(sellerAsset, 2_000)));
            apply(state, 2, 12, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(sellerAsset, 2_000)));
            apply(state, 3, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(buyerAsset, 2_000)));
            apply(state, 4, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 5, reservationKind, sellerAsset,
                            productLine == ProductLine.SPOT ? 5 : 1_000));
            apply(state, 5, 12, CoreMessageType.PLACE_ORDER,
                    place(102, CoreOrderSide.SELL, 100, 5, reservationKind, sellerAsset,
                            productLine == ProductLine.SPOT ? 5 : 1_000));
            apply(state, 6, 22, CoreMessageType.PLACE_ORDER,
                    place(201, CoreOrderSide.BUY, 100, 1, reservationKind, buyerAsset, 100));

            byte[] snapshot = state.snapshot();
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(productLine, snapshot)) {
                assertThat(restored.tradingState()).isEqualTo(state.tradingState());
                assertThat(awaitMatchingHash(restored)).isEqualTo(awaitMatchingHash(state));
                apply(restored, 7, 22, CoreMessageType.PLACE_ORDER,
                        place(202, CoreOrderSide.BUY, 100, 4, reservationKind, buyerAsset, 400));

                assertThat(restored.tradingState().order(101).status()).isEqualTo(CoreOrderStatus.FILLED);
                assertThat(restored.tradingState().order(102).status()).isEqualTo(CoreOrderStatus.OPEN);
                assertThat(restored.tradingState().order(102).remainingQuantitySteps()).isEqualTo(5);
            }
        }
    }

    @Test
    void amendPatchReadsOriginalInsideCoreAndReturnsReplacement() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000)));
            apply(state, 2, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.SPOT_ASSET, "USDT", 200));

            CoreMessage amend = message(state, 3, 22, CoreMessageType.AMEND_ORDER,
                    TradingCommandCodec.encodeAmendOrder(new AmendOrderCommand(202, 203, "amended",
                            110L, 2L, CoreTimeInForce.GTC, null)));
            var response = drainMatching(state, state.apply(amend), amend);

            assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(TradingCommandCodec.decodeAmendOrder(amend.payload()).replacementOrderId()).isEqualTo(203);
            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.CANCELED);
            assertThat(state.tradingState().order(203).status()).isEqualTo(CoreOrderStatus.OPEN);
            assertThat(state.tradingState().order(203).priceTicks()).isEqualTo(110);
            assertThat(state.tradingState().order(203).clientOrderId()).isEqualTo("amended");
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
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "BTC-USDT", 1, side, priceTicks, quantitySteps, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, ""));
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
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "BTC-USDT", 1, side, priceTicks, quantitySteps, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, orderType, timeInForce, postOnly, "client-" + orderId));
    }

    private static byte[] placeWithFees(
            long orderId,
            CoreOrderSide side,
            long priceTicks,
            long quantitySteps,
            boolean reduceOnly,
            ReservationKind reservationKind,
            String reservationAsset,
            long reservedUnits,
            CoreOrderType orderType,
            CoreTimeInForce timeInForce,
            long matchingPriceTicks,
            boolean postOnly,
            long makerFeeRatePpm,
            long takerFeeRatePpm) {
        String settleAsset = reservationKind == ReservationKind.DERIVATIVE_MARGIN ? reservationAsset : "USDT";
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "BTC-USDT", 1, side, priceTicks, quantitySteps, reduceOnly, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, orderType, timeInForce, postOnly, "client-" + orderId));
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
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "BTC-USDT", 1, side, priceTicks, quantitySteps, reduceOnly, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, ""));
    }

    private static void applyInstrument(CoreProbeState state) {
        applyInstrument(state, 0, 0);
    }

    private static void applyInstrument(CoreProbeState state, long makerFeeRatePpm, long takerFeeRatePpm) {
        ProductLine productLine = state.productLine();
        ContractType type = ContractType.valueOf(productLine.contractTypeCode());
        long expiry = type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0;
        UpsertInstrumentCommand instrument = new UpsertInstrumentCommand("BTC-USDT", 1, type.ordinal(),
                "BTC", "USDT", settleAsset(productLine), 1, 1, type.isInverse() ? 1_000 : 1,
                100_000, 50_000, makerFeeRatePpm, takerFeeRatePpm, expiry,
                type.isOption() ? 0 : -1, type.isOption() ? 100 : 0);
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.UPSERT_INSTRUMENT,
                UUID.randomUUID(), productLine, CommandSource.OPERATIONS, 88, 1, 1,
                1_000, 1), TradingCommandCodec.encodeUpsertInstrument(instrument));
        assertThat(state.apply(message).status()).isEqualTo(ResponseStatus.APPLIED);
        CoreMessage mark = new CoreMessage(CoreMessageHeader.command(CoreMessageType.APPLY_MARK_PRICE,
                UUID.randomUUID(), productLine, CommandSource.KAFKA_INPUT_BRIDGE, 89, 1, 1,
                1_000, 2), TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1, 1_000)));
        assertThat(state.apply(mark).status()).isEqualTo(ResponseStatus.APPLIED);
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
        CoreResponse response = drainMatching(state, state.apply(message), message);
        assertThat(response.status()).as("%s %s users=%s orders=%s", messageType, response.resultCode(),
                        state.tradingState().users().keySet(), state.tradingState().orders().keySet())
                .isIn(ResponseStatus.APPLIED, ResponseStatus.OK);
    }

    private static CoreResponse drainMatching(CoreProbeState state, CoreResponse response, CoreMessage message) {
        if (response.resultCode() != CoreResultCode.MATCHING_PENDING) return response;
        long sequence = state.matchingSequence(message.header().commandId());
        CoreResponse completed = state.completeMatchingSynchronously(sequence,
                message.header().submittedAtEpochMillis(), message.header().sourceSequence());
        assertThat(completed).isNotNull();
        assertThat(completed.status()).isIn(ResponseStatus.APPLIED, ResponseStatus.REJECTED);
        return completed;
    }

    private static CoreResponse completeUntilTerminalOrFailure(
            CoreProbeState state, long sequence,
            com.surprising.aeron.service.matching.CoreMatchingResult result,
            long clusterTimestamp, long clusterPosition) {
        CoreResponse completed = null;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (completed == null && System.nanoTime() < deadline) {
            completed = state.completeMatching(sequence, result, clusterTimestamp, clusterPosition);
            if (completed == null) Thread.onSpinWait();
        }
        if (completed == null) throw new AssertionError("account lane settlement did not complete");
        return completed;
    }

    private static int awaitMatchingHash(CoreProbeState state) {
        CompletableFuture<Integer> future = state.matchingStateHashAsync();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!future.isDone() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertThat(future).isDone();
        return future.getNow(0);
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

    private static Stream<ProductLine> allProductLines() {
        return Stream.of(ProductLine.values());
    }

    private static long total(CoreProbeState state, String asset) {
        long users = state.tradingState().users().values().stream()
                .mapToLong(user -> user.totalUnits(asset)).sum();
        var treasury = state.tradingState().treasuryState();
        long total = users;
        total = Math.addExact(total, treasury.feeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.insuranceBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.liquidationFeeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.fundingResidualBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.roundingResidualBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.clearingPnlBalances().getOrDefault(asset, 0L));
        return Math.subtractExact(total, treasury.insuranceDeficits().getOrDefault(asset, 0L));
    }
}
