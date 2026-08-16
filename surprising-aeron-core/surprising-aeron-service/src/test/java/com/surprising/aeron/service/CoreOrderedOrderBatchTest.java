package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreOrderBatchResult;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreOrderedOrderBatchTest {

    @Test
    void processesMaximumBatchesInInputOrder() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);
            UUID commandId = UUID.randomUUID();
            List<PlaceOrderCommand> orders = new ArrayList<>();
            for (int index = 0; index < PlaceOrderBatchCommand.MAX_ORDERS; index++) {
                orders.add(place(10_000 + index, "batch-place-" + index, 1_000));
            }
            CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, commandId, 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)));

            CoreResponse response = drainBatch(state, batch);

            assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
            CoreOrderBatchResult result = TradingOrderBatchCodec.decodeResult(response.data());
            assertThat(result.items()).hasSize(PlaceOrderBatchCommand.MAX_ORDERS)
                    .extracting(CoreOrderBatchResult.Item::index)
                    .containsExactlyElementsOf(java.util.stream.IntStream.range(0, orders.size())
                            .boxed().toList());
            assertThat(result.items()).allMatch(item -> item.status() == ResponseStatus.APPLIED);
            assertThat(state.tradingState().orders().keySet())
                    .containsExactlyInAnyOrderElementsOf(orders.stream()
                            .map(PlaceOrderCommand::orderId).toList());

            CoreResponse replay = state.apply(batch);
            assertThat(replay.status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(replay.requiredExportSequence()).isEqualTo(response.requiredExportSequence());
            assertThat(replay.data()).containsExactly(response.data());
            var stateAfterBatch = state.tradingState();
            CoreResponse conflict = state.apply(command(CoreMessageType.PLACE_ORDER_BATCH, commandId, 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(99_999, "changed-batch", 1_000))))));
            assertThat(conflict.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(conflict.resultCode()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
            assertThat(state.tradingState()).isSameAs(stateAfterBatch);

            var events = CoreExportCodec.decodeBatchResponse(state.apply(new CoreMessage(
                    CoreMessageHeader.query(CoreMessageType.EXPORT_BATCH_QUERY, UUID.randomUUID(),
                            ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 1001, 2_000, 3),
                    CoreExportCodec.encodeBatchQuery(256))).data()).events().stream()
                    .map(message -> CoreExportCodec.decodeEvent(message.payload()))
                    .filter(event -> event.commandId().equals(commandId)).toList();
            assertThat(events).hasSize(1);
            assertThat(response.requiredExportSequence()).isEqualTo(events.getFirst().exportSequence());
            assertThat(events.getFirst().changedUsers()).extracting(value -> value.userId())
                    .containsExactly(1001L);
            assertThat(events.getFirst().changedOrders()).extracting(value -> value.orderId())
                    .containsExactlyElementsOf(orders.stream().map(PlaceOrderCommand::orderId).toList());
        }
    }

    @Test
    void keepsPriorItemsButFailsStickyAfterMatcherDivergence() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 10_000);
            UUID commandId = UUID.randomUUID();
            PlaceOrderBatchCommand command = new PlaceOrderBatchCommand(List.of(
                    place(11_001, "batch-first", 1_000),
                    place(11_001, "batch-duplicate", 1_000),
                    place(11_003, "batch-third", 1_000)));
            CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, commandId, 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(command));

            assertThat(state.apply(batch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            long sequence = state.matchingSequence(commandId);
            var firstResult = awaitMatching(state, sequence);
            CoreResponse firstCompletion = state.completeMatching(sequence, firstResult, 2_000, 3);
            assertThat(firstCompletion).isNull();
            CoreResponse completed = drainBatchAfterFirst(state, batch, sequence);
            CoreOrderBatchResult result = TradingOrderBatchCodec.decodeResult(completed.data());
            assertThat(result.items()).extracting(CoreOrderBatchResult.Item::status)
                    .containsExactly(ResponseStatus.APPLIED, ResponseStatus.REJECTED, ResponseStatus.APPLIED);
            assertThat(result.items().get(1).resultCode()).isEqualTo(CoreResultCode.DUPLICATE_ORDER_ID);
            assertThat(state.tradingState().order(11_001).status())
                    .isEqualTo(com.surprising.aeron.service.state.CoreOrderStatus.OPEN);
            assertThat(state.tradingState().order(11_003).status())
                    .isEqualTo(com.surprising.aeron.service.state.CoreOrderStatus.OPEN);

            UUID fatalId = UUID.randomUUID();
            CoreMessage fatalBatch = command(CoreMessageType.PLACE_ORDER_BATCH, fatalId, 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(11_004, "fatal-fourth", 1_000),
                            place(11_005, "fatal-fifth", 1_000)))));
            assertThat(state.apply(fatalBatch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            long fatalSequence = state.matchingSequence(fatalId);
            var fatal = new com.surprising.aeron.service.matching.CoreMatchingResult(
                    false, "EXCHANGE_CORE_FAILURE", List.of());
            Throwable divergence = org.assertj.core.api.Assertions.catchThrowable(
                    () -> state.completeMatching(fatalSequence, fatal, 3_000, 4));
            assertThat(divergence).isInstanceOf(
                    com.surprising.aeron.service.matching.FatalMatchingDivergenceException.class);
            assertThatThrownBy(() -> state.apply(probe(UUID.randomUUID(), 4)))
                    .isSameAs(divergence);
            assertThat(state.tradingState().order(11_005)).isNull();
        }
    }

    @Test
    void executesCancelAndAmendBatchesWithOrderedPerItemOutcomes() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);

            CoreMessage place = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(12_001, "cancel-amend-source", 1_000)))));
            assertThat(drainBatch(state, place).status()).isEqualTo(ResponseStatus.APPLIED);

            CoreMessage amend = command(CoreMessageType.AMEND_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(
                            new AmendOrderCommand(12_001, 12_002, "amended", 1_100L, 2L,
                                    CoreTimeInForce.GTC, false),
                            new AmendOrderCommand(19_999, 12_003, "missing", 1_200L, 1L,
                                    CoreTimeInForce.GTC, false)))));
            CoreOrderBatchResult amended = TradingOrderBatchCodec.decodeResult(drainBatch(state, amend).data());
            assertThat(amended.items()).extracting(CoreOrderBatchResult.Item::status)
                    .containsExactly(ResponseStatus.APPLIED, ResponseStatus.REJECTED);
            assertThat(amended.items().get(1).resultCode()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
            assertThat(state.tradingState().order(12_001).status())
                    .isEqualTo(com.surprising.aeron.service.state.CoreOrderStatus.CANCELED);
            assertThat(state.tradingState().order(12_002).status())
                    .isEqualTo(com.surprising.aeron.service.state.CoreOrderStatus.OPEN);

            CoreMessage cancel = command(CoreMessageType.CANCEL_ORDER_BATCH, UUID.randomUUID(), 4,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(12_002), new CancelOrderCommand(19_999)))));
            CoreOrderBatchResult canceled = TradingOrderBatchCodec.decodeResult(drainBatch(state, cancel).data());
            assertThat(canceled.items()).extracting(CoreOrderBatchResult.Item::status)
                    .containsExactly(ResponseStatus.APPLIED, ResponseStatus.REJECTED);
            assertThat(canceled.items().get(1).resultCode()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
            assertThat(state.tradingState().order(12_002).status())
                    .isEqualTo(com.surprising.aeron.service.state.CoreOrderStatus.CANCELED);
        }
    }

    @Test
    void amendPartialMatcherFailureMustFailStickyBeforeRecordingBusinessRejection() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);
            CoreMessage place = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(12_101, "partial-amend-source", 1_000)))));
            assertThat(drainBatch(state, place).status()).isEqualTo(ResponseStatus.APPLIED);

            UUID commandId = UUID.randomUUID();
            CoreMessage amend = command(CoreMessageType.AMEND_ORDER_BATCH, commandId, 3,
                    TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(
                            new AmendOrderCommand(12_101, 12_102, "partial-amend-replacement", 1_100L, 2L,
                                    CoreTimeInForce.GTC, false),
                            new AmendOrderCommand(12_101, 12_103, "must-not-run", 1_200L, 1L,
                                    CoreTimeInForce.GTC, false)))));

            assertThat(state.apply(amend).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            long sequence = state.matchingSequence(commandId);
            var partialMatcherFailure = new com.surprising.aeron.service.matching.CoreMatchingResult(
                    false, "MATCHING_INVALID_ORDER_ID", List.of(), List.of(), 0, true);

            Throwable divergence = org.assertj.core.api.Assertions.catchThrowable(
                    () -> state.completeMatching(sequence, partialMatcherFailure, 2_000, 4));

            assertThat(divergence).isInstanceOf(
                    com.surprising.aeron.service.matching.FatalMatchingDivergenceException.class);
            assertThat(state.tradingState().order(12_101).status())
                    .isEqualTo(com.surprising.aeron.service.state.CoreOrderStatus.OPEN);
            assertThat(state.tradingState().order(12_102)).isNull();
            assertThat(state.tradingState().order(12_103)).isNull();
            assertThat(state.pendingMatchingCount()).isEqualTo(1);
            assertThat(state.commandResults().get(commandId).resultCode())
                    .isEqualTo(CoreResultCode.MATCHING_PENDING);
            assertThatThrownBy(() -> state.apply(probe(UUID.randomUUID(), 4)))
                    .isSameAs(divergence);
        }
    }

    @Test
    void rejectsMixedUserCancelBatchBeforeAnyMutation() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);
            applyBalance(state, 1002, 100_000, 2);
            CoreMessage ownPlace = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(13_001, "mixed-user-own", 1_000)))), 1001);
            CoreMessage foreignPlace = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 4,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(13_002, "mixed-user-foreign", 1_000)))), 1002);
            assertThat(drainBatch(state, ownPlace).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(drainBatch(state, foreignPlace).status()).isEqualTo(ResponseStatus.APPLIED);

            TradingCoreState before = state.tradingState();
            long appliedBefore = state.appliedCommandCount();
            long stateHashBefore = state.stateHash();
            int exportEventsBefore = state.exportState().pendingCount();
            UUID commandId = UUID.randomUUID();
            CoreMessage mixed = command(CoreMessageType.CANCEL_ORDER_BATCH, commandId, 5,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(13_001), new CancelOrderCommand(13_002)))), 1001);

            CoreResponse response = state.apply(mixed);

            assertThat(response.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(response.resultCode()).isEqualTo(CoreResultCode.ORDER_OWNER_MISMATCH);
            assertThat(state.tradingState()).isSameAs(before);
            assertThat(state.appliedCommandCount()).isEqualTo(appliedBefore);
            assertThat(state.stateHash()).isEqualTo(stateHashBefore);
            assertThat(state.pendingMatchingCount()).isZero();
            assertThat(state.exportState().pendingCount()).isEqualTo(exportEventsBefore);
            assertThat(state.commandResults()).doesNotContainKey(commandId);
            assertThat(state.tradingState().order(13_001).status())
                    .isEqualTo(com.surprising.aeron.service.state.CoreOrderStatus.OPEN);
            assertThat(state.tradingState().order(13_002).status())
                    .isEqualTo(com.surprising.aeron.service.state.CoreOrderStatus.OPEN);
        }
    }

    @Test
    void rejectsProductLineMismatchBeforeAnyBatchMutation() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            TradingCoreState before = state.tradingState();
            long appliedBefore = state.appliedCommandCount();
            long stateHashBefore = state.stateHash();
            int exportEventsBefore = state.exportState().pendingCount();
            UUID commandId = UUID.randomUUID();
            CoreMessage crossLine = command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.PLACE_ORDER_BATCH,
                    commandId, 2, TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(14_001, "cross-line", 1_000)))));

            CoreResponse response = state.apply(crossLine);

            assertThat(response.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(response.resultCode()).isEqualTo(CoreResultCode.PRODUCT_LINE_MISMATCH);
            assertThat(state.tradingState()).isSameAs(before);
            assertThat(state.appliedCommandCount()).isEqualTo(appliedBefore);
            assertThat(state.stateHash()).isEqualTo(stateHashBefore);
            assertThat(state.pendingMatchingCount()).isZero();
            assertThat(state.exportState().pendingCount()).isEqualTo(exportEventsBefore);
            assertThat(state.commandResults()).doesNotContainKey(commandId);
        }
    }

    private static CoreResponse drainBatch(CoreProbeState state, CoreMessage batch) {
        CoreResponse initial = state.apply(batch);
        if (initial.resultCode() != CoreResultCode.MATCHING_PENDING) return initial;
        long sequence = state.matchingSequence(batch.header().commandId());
        return drainBatchAfterFirst(state, batch, sequence);
    }

    private static CoreResponse drainBatchAfterFirst(CoreProbeState state, CoreMessage batch, long sequence) {
        while (true) {
            var matching = awaitMatching(state, sequence);
            CoreResponse completed = state.completeMatching(state.matchingSequence(batch.header().commandId()), matching,
                    batch.header().submittedAtEpochMillis(), batch.header().sourceSequence());
            if (completed != null) return completed;
        }
    }

    private static com.surprising.aeron.service.matching.CoreMatchingResult awaitMatching(
            CoreProbeState state, long sequence) {
        com.surprising.aeron.service.matching.CoreMatchingResult matching = null;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (matching == null && System.nanoTime() < deadline) {
            matching = state.takeMatchingResult(sequence);
            if (matching == null) Thread.onSpinWait();
        }
        assertThat(matching).isNotNull();
        return matching;
    }

    private static PlaceOrderCommand place(long orderId, String clientOrderId, long reservedUnits) {
        return new PlaceOrderCommand(orderId, "BTC-USDT", 1, "BTC", "USDT", "USDT",
                CoreOrderSide.BUY, 1_000, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                ReservationKind.SPOT_ASSET, "USDT", reservedUnits, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, 1_000, false, clientOrderId, 0, 0);
    }

    private static void applySpotInstrument(CoreProbeState state) {
        UpsertInstrumentCommand instrument = new UpsertInstrumentCommand("BTC-USDT", 1,
                ContractType.SPOT.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                100_000, 50_000, 0, 0, 0, -1, 0);
        assertThat(state.apply(new CoreMessage(CoreMessageHeader.command(
                CoreMessageType.UPSERT_INSTRUMENT, UUID.randomUUID(), ProductLine.SPOT,
                CommandSource.OPERATIONS, 9, 1, 0, 1_000, 1),
                TradingCommandCodec.encodeUpsertInstrument(instrument))).status())
                .isEqualTo(ResponseStatus.APPLIED);
    }

    private static void applyBalance(CoreProbeState state, long userId, long units) {
        applyBalance(state, userId, units, 1);
    }

    private static void applyBalance(CoreProbeState state, long userId, long units, long sourceSequence) {
        assertThat(state.apply(command(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), sourceSequence,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", units)), userId))
                .status()).isEqualTo(ResponseStatus.APPLIED);
    }

    private static CoreMessage command(CoreMessageType type, UUID commandId, long sourceSequence, byte[] payload) {
        return command(type, commandId, sourceSequence, payload, 1001);
    }

    private static CoreMessage command(CoreMessageType type, UUID commandId, long sourceSequence,
                                       byte[] payload, long userId) {
        return command(ProductLine.SPOT, type, commandId, sourceSequence, payload, userId);
    }

    private static CoreMessage command(ProductLine productLine, CoreMessageType type, UUID commandId,
                                       long sourceSequence, byte[] payload) {
        return command(productLine, type, commandId, sourceSequence, payload, 1001);
    }

    private static CoreMessage command(ProductLine productLine, CoreMessageType type, UUID commandId,
                                       long sourceSequence, byte[] payload, long userId) {
        return new CoreMessage(CoreMessageHeader.command(type, commandId, productLine,
                CommandSource.GATEWAY, 77, sourceSequence, userId, 1_000, sourceSequence), payload);
    }

    private static CoreMessage probe(UUID commandId, long sequence) {
        return command(CoreMessageType.PROBE_INCREMENT, commandId, sequence,
                com.surprising.aeron.protocol.CoreProtocol.probePayload(1));
    }
}
