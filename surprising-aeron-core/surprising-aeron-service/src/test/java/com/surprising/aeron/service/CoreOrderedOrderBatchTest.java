package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
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
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
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
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.MarkPriceRuntime;
import com.surprising.aeron.service.state.ActiveOrderIndex;
import com.surprising.aeron.service.state.OpenInterestIndex;
import com.surprising.aeron.service.state.PositionUserIndex;
import com.surprising.aeron.service.state.TriggerOrderIndex;
import com.surprising.aeron.service.state.AlgoOrderIndex;
import com.surprising.aeron.service.state.LiquidationIndex;
import com.surprising.aeron.service.state.CancelAllAfterIndex;
import com.surprising.aeron.service.state.AdlPositionIndex;
import com.surprising.aeron.service.state.RiskSnapshotIndex;
import com.surprising.aeron.service.state.RuntimeCommitJournal;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class CoreOrderedOrderBatchTest {

    @Test
    void isolatesOverlappingBatchesUntilTheActiveBatchCompletes() throws Exception {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);
            UUID batchId = UUID.randomUUID();
            CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, batchId, 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(9_001, "batch-9001", 1_000),
                            place(9_002, "batch-9002", 1_000)))));
            UUID laterId = UUID.randomUUID();
            CoreMessage later = command(CoreMessageType.PLACE_ORDER_BATCH, laterId, 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(9_003, "later-9003", 1_000)))));

            assertThat(state.apply(batch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            assertThat(state.apply(later).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            long batchSequence = state.matchingSequence(batchId);
            long laterSequence = state.matchingSequence(laterId);
            var first = awaitMatching(state, batchSequence);
            assertThat(state.completeMatching(batchSequence, first, 2_000, 3)).isNull();
            var second = awaitMatching(state, batchSequence);

            var contextsField = CoreProbeState.class.getDeclaredField("laneCommandContexts");
            contextsField.setAccessible(true);
            LaneCommandContextRing contexts = (LaneCommandContextRing) contextsField.get(state);
            assertThat(contexts.required(laterSequence).hasMatchingCompletion()).isFalse();

            CoreResponse batchResponse = state.completeMatching(batchSequence, second, 2_001, 4);
            assertThat(batchResponse).isNotNull();
            var laterResult = awaitMatching(state, laterSequence);
            CoreResponse laterResponse = state.completeMatching(laterSequence, laterResult, 2_002, 5);
            assertThat(laterResponse).isNotNull();
            state.exportState().pending();

            var events = CoreExportCodec.decodeBatchResponse(state.apply(new CoreMessage(
                    CoreMessageHeader.query(CoreMessageType.EXPORT_BATCH_QUERY, UUID.randomUUID(),
                            ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 1001, 2_003, 6),
                    CoreExportCodec.encodeBatchQuery(256))).data()).events().stream()
                    .map(message -> CoreExportCodec.decodeEvent(message.payload()))
                    .filter(event -> event.commandId().equals(batchId) || event.commandId().equals(laterId))
                    .toList();
            assertThat(events).hasSize(2);
            assertThat(events).extracting(event -> event.matcherTransition().sequenceAfter())
                    .containsExactly(2L, 3L);
            assertThat(events.getFirst().matcherTransition().sequenceBefore()).isZero();
            assertThat(events.getFirst().matcherTransition().prefixAfter())
                    .isNotEqualTo(events.getFirst().matcherTransition().prefixBefore());
            assertThat(events.get(1).matcherTransition().sequenceBefore())
                    .isEqualTo(events.getFirst().matcherTransition().sequenceAfter());
            assertThat(events.get(1).matcherTransition().prefixBefore())
                    .isEqualTo(events.getFirst().matcherTransition().prefixAfter());
            assertThat(events.get(1).matcherTransition().prefixAfter())
                    .isNotEqualTo(events.get(1).matcherTransition().prefixBefore());
            assertThat(events.get(0).changedOrders()).extracting(order -> order.orderId())
                    .containsExactly(9_001L, 9_002L);
            assertThat(events.get(1).changedOrders()).extracting(order -> order.orderId())
                    .containsExactly(9_003L);
            assertThat(events.get(0).changedUsers().getFirst().reservations()).extracting(value -> value.orderId())
                    .containsExactly(9_001L, 9_002L);
            assertThat(events.get(1).changedUsers().getFirst().reservations()).extracting(value -> value.orderId())
                    .containsExactly(9_003L);
            assertThat(events).allSatisfy(event -> assertThat(event.changedOrders()).allSatisfy(order -> {
                assertThat(order.commandId()).isEqualTo(event.commandId());
                assertThat(order.createdAtEpochMillis()).isPositive();
                assertThat(order.updatedAtEpochMillis()).isPositive();
                assertThat(order.clusterPosition()).isPositive();
            }));
            assertThat(TradingOrderBatchCodec.decodeResult(batchResponse.data()).items())
                    .extracting(CoreOrderBatchResult.Item::order)
                    .containsExactlyElementsOf(events.get(0).changedOrders());
            assertThat(TradingOrderBatchCodec.decodeResult(laterResponse.data()).items())
                    .extracting(CoreOrderBatchResult.Item::order)
                    .containsExactlyElementsOf(events.get(1).changedOrders());
        }
    }

    @Test
    void defersSinglePlacePreparationAndExportUntilTheActiveBatchCompletes() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);
            UUID batchId = UUID.randomUUID();
            CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, batchId, 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(9_101, "batch-9101", 1_000)))));
            UUID laterId = UUID.randomUUID();
            CoreMessage later = command(CoreMessageType.PLACE_ORDER, laterId, 3,
                    TradingCommandCodec.encodePlaceOrder(place(9_102, "later-9102", 1_000)));
            UUID lastId = UUID.randomUUID();
            CoreMessage last = command(CoreMessageType.PLACE_ORDER, lastId, 4,
                    TradingCommandCodec.encodePlaceOrder(place(9_103, "last-9103", 1_000)));

            assertThat(state.apply(batch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            CoreResponse deferred = state.apply(later);
            CoreResponse lastDeferred = state.apply(last);
            CoreResponse duplicate = state.apply(later);
            assertThat(state.apply(command(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 4,
                    TradingCommandCodec.encodePlaceOrder(place(9_104, "stale-9104", 1_000)))).resultCode())
                    .isEqualTo(CoreResultCode.STALE_SOURCE_SEQUENCE);
            assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(duplicate.appliedCommandCount()).isEqualTo(deferred.appliedCommandCount());
            assertThat(duplicate.requiredExportSequence()).isEqualTo(deferred.requiredExportSequence());
            assertThat(duplicate.stateHash()).isEqualTo(deferred.stateHash());
            assertThat(state.pendingMatching()).containsKeys(
                    state.matchingSequence(laterId), state.matchingSequence(lastId));
            var exportsBeforeBatchCompletion = CoreExportCodec.decodeBatchResponse(state.apply(new CoreMessage(
                    CoreMessageHeader.query(CoreMessageType.EXPORT_BATCH_QUERY, UUID.randomUUID(),
                            ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 1001, 1_999, 4),
                    CoreExportCodec.encodeBatchQuery(256))).data()).events().stream()
                    .map(message -> CoreExportCodec.decodeEvent(message.payload()))
                    .filter(event -> event.commandId().equals(laterId) || event.commandId().equals(lastId))
                    .toList();
            assertThat(exportsBeforeBatchCompletion).isEmpty();

            long batchSequence = state.matchingSequence(batchId);
            var batchMatching = awaitMatching(state, batchSequence);
            CoreResponse batchResponse = state.completeMatching(batchSequence, batchMatching, 2_000, 4);
            assertThat(batchResponse).isNotNull();
            assertThat(state.pendingMatching()).containsKeys(
                    state.matchingSequence(laterId), state.matchingSequence(lastId));
            assertThat(batchResponse.appliedCommandCount()).isEqualTo(batchSequence);
            state.exportState().pending();
            var eventsBeforeCompletions = CoreExportCodec.decodeBatchResponse(state.apply(new CoreMessage(
                    CoreMessageHeader.query(CoreMessageType.EXPORT_BATCH_QUERY, UUID.randomUUID(),
                            ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 1001, 2_001, 5),
                    CoreExportCodec.encodeBatchQuery(256))).data()).events().stream()
                    .map(message -> CoreExportCodec.decodeEvent(message.payload()))
                    .filter(event -> event.commandId().equals(batchId) || event.commandId().equals(laterId)
                            || event.commandId().equals(lastId))
                    .toList();
            var appliedBeforeCompletions = eventsBeforeCompletions.stream()
                    .map(event -> event.appliedCommandCount()).toList();
            assertThat(appliedBeforeCompletions).containsExactly(batchSequence);

            long laterSequence = state.matchingSequence(laterId);
            var laterMatching = awaitMatching(state, laterSequence);
            CoreResponse laterResponse = state.completeMatching(laterSequence, laterMatching, 2_002, 6);
            assertThat(laterResponse).isNotNull();
            assertThat(laterResponse.status()).isEqualTo(ResponseStatus.APPLIED);
            long lastSequence = state.matchingSequence(lastId);
            var lastMatching = awaitMatching(state, lastSequence);
            CoreResponse lastResponse = state.completeMatching(lastSequence, lastMatching, 2_003, 7);
            assertThat(lastResponse).isNotNull();
            assertThat(lastResponse.status()).isEqualTo(ResponseStatus.APPLIED);
            state.exportState().pending();

            var events = CoreExportCodec.decodeBatchResponse(state.apply(new CoreMessage(
                    CoreMessageHeader.query(CoreMessageType.EXPORT_BATCH_QUERY, UUID.randomUUID(),
                            ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 1001, 2_004, 8),
                    CoreExportCodec.encodeBatchQuery(256))).data()).events().stream()
                    .map(message -> CoreExportCodec.decodeEvent(message.payload()))
                    .filter(event -> event.commandId().equals(batchId) || event.commandId().equals(laterId)
                            || event.commandId().equals(lastId))
                    .toList();
            var batchEvent = events.stream().filter(event -> event.commandId().equals(batchId)).findFirst().orElseThrow();
            var laterEvents = events.stream().filter(event -> event.commandId().equals(laterId)).toList();
            var lastEvents = events.stream().filter(event -> event.commandId().equals(lastId)).toList();
            assertThat(batchEvent.changedOrders()).extracting(order -> order.orderId()).containsExactly(9_101L);
            assertThat(batchEvent.changedUsers().getFirst().reservations()).extracting(value -> value.orderId())
                    .containsExactly(9_101L);
            assertThat(laterEvents).hasSize(1);
            assertThat(lastEvents).hasSize(1);
            assertThat(batchEvent.exportSequence()).isLessThan(laterEvents.getFirst().exportSequence());
            assertThat(batchEvent.businessStateHash()).isNotEqualTo(laterEvents.getFirst().businessStateHash());
            assertThat(laterEvents.getFirst().changedOrders()).extracting(order -> order.orderId())
                    .containsExactly(9_102L);
            assertThat(lastEvents.getFirst().changedOrders()).extracting(order -> order.orderId())
                    .containsExactly(9_103L);
            var appliedCounts = events.stream().map(event -> event.appliedCommandCount()).toList();
            assertThat(java.util.stream.IntStream.range(1, appliedCounts.size())
                    .allMatch(index -> appliedCounts.get(index - 1) < appliedCounts.get(index))).isTrue();
            assertThat(deferred.requiredExportSequence()).isZero();
            assertThat(lastDeferred.requiredExportSequence()).isZero();
        }
    }

    @Test
    void processesMaximumBatchesInInputOrder() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, Math.multiplyExact(PlaceOrderBatchCommand.MAX_ORDERS, 1_000L));
            UUID commandId = UUID.randomUUID();
            List<PlaceOrderCommand> orders = new ArrayList<>();
            for (int index = 0; index < PlaceOrderBatchCommand.MAX_ORDERS; index++) {
                orders.add(place(10_000 + index, "batch-place-" + index, 1_000));
            }
            CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, commandId, 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)));

            state.captureCommittedPatchesForTest();
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
            var patches = state.drainCapturedCommitPatchesForTest();
            assertThat(patches).hasSize(1);
            var patch = patches.getFirst();
            assertThat(patch.coreSequence()).isEqualTo(response.appliedCommandCount());
            assertThat(patch.accountLaneGroups().stream().flatMap(group -> group.orders().stream())
                    .map(change -> change.after() == null
                            ? change.before().orderId() : change.after().orderId()).toList())
                    .containsExactlyElementsOf(orders.stream().map(PlaceOrderCommand::orderId).toList());
            assertThat(patch.businessStateHash()).isEqualTo(state.snapshotBusinessStateHash());
            assertThat(patch.fundsStateHash()).isEqualTo(state.snapshotFundsStateHash());
            assertThat(patch.projectionSequence()).isEqualTo(state.snapshotProjectionSequence());

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
            assertThat(state.tradingState()).isEqualTo(stateAfterBatch);
            state.exportState().pending();

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
    void exportsConservedFundsWhenABatchMatchesAnotherUser() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 2_000_000);
            applyBalance(state, 1002, "BTC", 2_000, 2);
            PlaceOrderCommand sell = spotOrder(10_501, "batch-maker-sell", CoreOrderSide.SELL,
                    1_000, 1_000, "BTC", 1_000, 200, 500);
            CoreMessage makerBatch = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(sell))), 1002);
            assertThat(drainBatch(state, makerBatch).status()).isEqualTo(ResponseStatus.APPLIED);

            PlaceOrderCommand crossingBuy = spotOrder(10_502, "batch-crossing-buy", CoreOrderSide.BUY,
                    1_000, 1_000, "USDT", 1_000_500, 200, 500);
            PlaceOrderCommand restingBuy = spotOrder(10_503, "batch-resting-buy", CoreOrderSide.BUY,
                    900, 100, "USDT", 90_045, 200, 500);
            UUID takerBatchId = UUID.randomUUID();
            CoreMessage takerBatch = command(CoreMessageType.PLACE_ORDER_BATCH, takerBatchId, 4,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(
                            new PlaceOrderBatchCommand(List.of(crossingBuy, restingBuy))), 1001);

            CoreResponse response = drainBatch(state, takerBatch);

            assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
            state.exportState().pending();
            var event = CoreExportCodec.decodeBatchResponse(state.apply(new CoreMessage(
                    CoreMessageHeader.query(CoreMessageType.EXPORT_BATCH_QUERY, UUID.randomUUID(),
                            ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 1001, 2_000, 5),
                    CoreExportCodec.encodeBatchQuery(256))).data()).events().stream()
                    .map(message -> CoreExportCodec.decodeEvent(message.payload()))
                    .filter(value -> value.commandId().equals(takerBatchId))
                    .findFirst().orElseThrow();
            assertThat(event.fundsPostings()).isNotEmpty();
            assertThat(event.fundsPostings().stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            com.surprising.aeron.protocol.CoreFundsPostingView::asset,
                            java.util.stream.Collectors.summingLong(
                                    com.surprising.aeron.protocol.CoreFundsPostingView::units))))
                    .allSatisfy((asset, units) -> assertThat(units).as(asset).isZero());
        }
    }

    @Test
    void perpetualBatchAdmissionIncludesEarlierItemsAndConservesReservedFunds() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.LINEAR_PERPETUAL)) {
            UpsertInstrumentCommand instrument = new UpsertInstrumentCommand("BTC-USDT", 1,
                    ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                    1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0,
                    10_000_000, 1_500, 0, 1_500,
                    List.of(new CoreRiskLimitBracket(1, 0, 1_500,
                            10_000_000, 100_000, 50_000)));
            assertThat(state.apply(command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.UPSERT_INSTRUMENT,
                    UUID.randomUUID(), 1, TradingCommandCodec.encodeUpsertInstrument(instrument))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.apply(command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.APPLY_MARK_PRICE,
                    UUID.randomUUID(), 2, TradingCommandCodec.encodeApplyMarkPrice(
                            new ApplyMarkPriceCommand("BTC-USDT", 1, 1_000, 1, 1_000)))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.apply(command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.ADJUST_BALANCE,
                    UUID.randomUUID(), 3, TradingCommandCodec.encodeBalanceAdjustment(
                            new BalanceAdjustmentCommand("USDT", 1_000_000)))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            PlaceOrderCommand first = new PlaceOrderCommand(10_601, "BTC-USDT", 1,
                    CoreOrderSide.BUY, 1_000, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "risk-first");
            PlaceOrderCommand exceedsBatchLimit = new PlaceOrderCommand(10_602, "BTC-USDT", 1,
                    CoreOrderSide.BUY, 1_000, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "risk-second");
            CoreMessage batch = command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.PLACE_ORDER_BATCH,
                    UUID.randomUUID(), 4, TradingOrderBatchCodec.encodePlaceOrderBatch(
                            new PlaceOrderBatchCommand(List.of(first, exceedsBatchLimit))));

            CoreOrderBatchResult result = TradingOrderBatchCodec.decodeResult(drainBatch(state, batch).data());

            assertThat(result.items()).extracting(CoreOrderBatchResult.Item::status)
                    .containsExactly(ResponseStatus.APPLIED, ResponseStatus.REJECTED);
            assertThat(result.items().get(1).resultCode())
                    .isEqualTo(CoreResultCode.POSITION_NOTIONAL_LIMIT_EXCEEDED);
            var balance = state.tradingState().user(1001).balances().get("USDT");
            assertThat(Math.addExact(balance.availableUnits(), balance.lockedUnits())).isEqualTo(1_000_000);
            assertThat(state.tradingState().user(1001).reservations()).containsOnlyKeys(10_601L);
        }
    }

    @Test
    void keepsPriorItemsButFailsStickyAfterMatcherDivergence() throws Exception {
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
            TradingRuntimeState runtime = field(state, "runtimePlaceOrderState");
            RuntimeIdentityRegistry identities = field(state, "runtimePlaceOrderIdentities");
            int quoteAssetId = identities.assetId("USDT");
            var balanceBeforeFatal = runtime.balance(1001, quoteAssetId);
            long availableBeforeFatal = balanceBeforeFatal.availableUnits();
            long lockedBeforeFatal = balanceBeforeFatal.lockedUnits();
            long revisionBeforeFatal = runtime.revision();
            var laneBeforeFatal = runtime.accountLane(1001);
            var allLanesBeforeFatal = java.util.stream.IntStream.range(0, runtime.topology().accountLaneCount())
                    .mapToObj(runtime::accountLaneById).toList();
            long businessHashBeforeFatal = state.snapshotBusinessStateHash();
            long fundsHashBeforeFatal = state.snapshotFundsStateHash();
            long projectionBeforeFatal = state.snapshotProjectionSequence();
            int resultsBeforeFatal = state.commandResults().size();
            long appliedBeforeFatal = state.appliedCommandCount();
            long positionIdentityBeforeFatal = identities.positionCheckpoint();
            long[] matcherBeforeFatal = ((long[]) field(state, "appliedMatcherSequences")).clone();
            long[] matcherPrefixBeforeFatal = ((long[]) field(state, "appliedMatcherPrefixDigests")).clone();
            ActiveOrderIndex activeOrders = field(state, "activeOrderIndex");
            OpenInterestIndex openInterest = field(state, "openInterestIndex");
            PositionUserIndex positionUsers = field(state, "positionUserIndex");
            var activeOrderIdsBeforeFatal = List.copyOf(activeOrders.ids());
            var openInterestBeforeFatal = Map.copyOf(openInterest.totals());
            var positionUsersBeforeFatal = List.copyOf(positionUsers.users("BTC-USDT"));
            var treasuryBeforeFatal = runtime.treasury().assetLedgerEntryCount();
            var treasuryValuesBeforeFatal = treasurySnapshot(runtime);
            var indexesBeforeFatal = allIndexSnapshots(state);
            var identitiesBeforeFatal = identities.snapshot();
            long committedBeforeFatal = state.committedCoreSequence();
            var exportBeforeFatal = state.exportState().snapshot();
            var exportMetricsBeforeFatal = state.exportState().metrics();
            RuntimeCommitJournal journal = field(state, "runtimeProjectionJournal");
            var journalBeforeFatal = journal.metrics();
            state.captureCommittedPatchesForTest();
            CoreMessage fatalBatch = command(CoreMessageType.PLACE_ORDER_BATCH, fatalId, 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(11_004, "fatal-fourth", 1_000),
                            place(11_005, "fatal-fifth", 1_000)))));
            assertThat(state.apply(fatalBatch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            long fatalSequence = state.matchingSequence(fatalId);
            var acceptedFirst = awaitMatching(state, fatalSequence);
            assertThat(state.completeMatching(fatalSequence, acceptedFirst, 2_900, 4)).isNull();
            assertThat(runtime.order(11_004)).isNotNull();
            LaneCommandContextRing contexts = field(state, "laneCommandContexts");
            LaneCommandContextRing.Context claimedContext = contexts.required(fatalSequence);
            long[] matcherAfterFirst = ((long[]) field(state, "appliedMatcherSequences")).clone();
            long[] matcherPrefixAfterFirst = ((long[]) field(state, "appliedMatcherPrefixDigests")).clone();
            assertThat(matcherAfterFirst).isNotEqualTo(matcherBeforeFatal);
            assertThat(matcherPrefixAfterFirst).isNotEqualTo(matcherPrefixBeforeFatal);
            long exportReservedEventsAfterFirst = state.exportState().metrics().reservedEvents();
            long exportReservedBytesAfterFirst = state.exportState().metrics().reservedBytes();
            long journalReservedEntriesAfterFirst = journal.metrics().reservedEntries();
            long journalReservedBytesAfterFirst = journal.metrics().reservedBytes();
            assertThat((int) invoke(runtime, "pendingReservationCount", new Class<?>[]{long.class}, 1001L))
                    .isPositive();
            assertThat((long) invoke(runtime, "pendingReservedUnits",
                    new Class<?>[]{long.class, int.class}, 1001L, quoteAssetId)).isPositive();
            var fatal = new com.surprising.aeron.service.matching.CoreMatchingResult(
                    false, "EXCHANGE_CORE_FAILURE");
            Throwable divergence = org.assertj.core.api.Assertions.catchThrowable(
                    () -> state.completeMatching(fatalSequence, fatal, 3_000, 5));
            assertThat(divergence).isInstanceOf(
                    com.surprising.aeron.service.matching.FatalMatchingDivergenceException.class);
            assertThatThrownBy(() -> state.apply(probe(UUID.randomUUID(), 4)))
                    .isSameAs(divergence);
            assertThat(state.takeMatchingResult(fatalSequence)).isNull();
            assertThat(claimedContext.hasMatchingCompletion()).isFalse();
            assertThat(runtime.order(11_004)).isNull();
            assertThat(runtime.reservation(11_004)).isNull();
            assertThat(state.tradingState().order(11_005)).isNull();
            assertThat((int) invoke(runtime, "pendingReservationCount", new Class<?>[]{long.class}, 1001L))
                    .isZero();
            assertThat((int) invoke(runtime, "pendingReservationCount", new Class<?>[0])).isZero();
            assertThat((long) invoke(runtime, "pendingReservedUnits",
                    new Class<?>[]{long.class, int.class}, 1001L, quoteAssetId)).isZero();
            assertThat(runtime.balance(1001, quoteAssetId).availableUnits()).isEqualTo(availableBeforeFatal);
            assertThat(runtime.balance(1001, quoteAssetId).lockedUnits()).isEqualTo(lockedBeforeFatal);
            assertThat(runtime.revision()).isEqualTo(revisionBeforeFatal);
            assertThat(runtime.accountLane(1001)).isEqualTo(laneBeforeFatal);
            assertThat(java.util.stream.IntStream.range(0, runtime.topology().accountLaneCount())
                    .mapToObj(runtime::accountLaneById).toList()).isEqualTo(allLanesBeforeFatal);
            assertThat(state.snapshotBusinessStateHash()).isEqualTo(businessHashBeforeFatal);
            assertThat(state.snapshotFundsStateHash()).isEqualTo(fundsHashBeforeFatal);
            assertThat(state.snapshotProjectionSequence()).isEqualTo(projectionBeforeFatal);
            assertThat(state.commandResults()).hasSize(resultsBeforeFatal).doesNotContainKey(fatalId);
            assertThat(state.pendingMatching(fatalSequence)).isNotNull();
            assertThat(state.pendingMatching(fatalSequence).pendingStateHash()).isNotZero();
            assertThat(state.appliedCommandCount()).isEqualTo(appliedBeforeFatal + 1);
            assertThat(state.pendingMatchingCount()).isOne();
            assertThat(state.matchingSequence(fatalId)).isEqualTo(fatalSequence);
            assertThat(state.snapshotHasOutstandingReservation()).isTrue();
            assertThat((long[]) field(state, "appliedMatcherSequences")).containsExactly(matcherAfterFirst);
            assertThat((long[]) field(state, "appliedMatcherPrefixDigests"))
                    .containsExactly(matcherPrefixAfterFirst);
            assertThat(identities.positionCheckpoint()).isEqualTo(positionIdentityBeforeFatal);
            assertThat(activeOrders.ids()).containsExactlyElementsOf(activeOrderIdsBeforeFatal);
            assertThat(openInterest.totals()).containsExactlyInAnyOrderEntriesOf(openInterestBeforeFatal);
            assertThat(positionUsers.users("BTC-USDT")).containsExactlyElementsOf(positionUsersBeforeFatal);
            assertThat(runtime.treasury().assetLedgerEntryCount()).isEqualTo(treasuryBeforeFatal);
            assertThat(treasurySnapshot(runtime)).isEqualTo(treasuryValuesBeforeFatal);
            assertThat(allIndexSnapshots(state)).isEqualTo(indexesBeforeFatal);
            assertThat(state.committedCoreSequence()).isEqualTo(committedBeforeFatal);
            assertThat(state.drainCapturedCommitPatchesForTest()).isEmpty();
            assertThat(state.exportState().snapshot()).isEqualTo(exportBeforeFatal);
            assertThat(state.exportState().metrics().currentBacklog())
                    .isEqualTo(exportMetricsBeforeFatal.currentBacklog());
            assertThat(state.exportState().metrics().reservedEvents()).isEqualTo(exportReservedEventsAfterFirst);
            assertThat(state.exportState().metrics().reservedBytes()).isEqualTo(exportReservedBytesAfterFirst);
            assertThat(journal.metrics().currentBacklog()).isEqualTo(journalBeforeFatal.currentBacklog());
            assertThat(journal.metrics().currentBacklogBytes()).isEqualTo(journalBeforeFatal.currentBacklogBytes());
            assertThat(journal.metrics().reservedEntries()).isEqualTo(journalReservedEntriesAfterFirst);
            assertThat(journal.metrics().reservedBytes()).isEqualTo(journalReservedBytesAfterFirst);
            assertThat(identities.findClientKey(1001, "fatal-fourth")).isNull();
            assertThat(identities.findClientKey(1001, "fatal-fifth")).isNull();
            assertThat(identities.snapshot()).isEqualTo(identitiesBeforeFatal);
            var reused = identities.prepareClientKey(1001, "fatal-fourth");
            assertThat(reused.allocated()).isTrue();
            assertThat(reused.key()).isPositive();
            identities.rollbackPreparedClientKey(1001, "fatal-fourth", reused);
            assertThat(identities.snapshot()).isEqualTo(identitiesBeforeFatal);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static Map<String, Object> treasurySnapshot(TradingRuntimeState runtime) {
        var treasury = runtime.treasury();
        return Map.ofEntries(
                Map.entry("fees", treasury.feeBalances()),
                Map.entry("insurance", treasury.insuranceBalances()),
                Map.entry("deficits", treasury.insuranceDeficits()),
                Map.entry("liquidationFees", treasury.liquidationFeeBalances()),
                Map.entry("fundingResiduals", treasury.fundingResidualBalances()),
                Map.entry("roundingResiduals", treasury.roundingResidualBalances()),
                Map.entry("clearingPnl", treasury.clearingPnlBalances()),
                Map.entry("fundingSettlements", treasury.fundingSettlements()),
                Map.entry("fundingProgress", treasury.fundingProgresses()),
                Map.entry("lifecycleSettlements", treasury.lifecycleSettlements()),
                Map.entry("lifecycleProgress", treasury.lifecycleProgresses()));
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static Map<String, Map<String, Object>> allIndexSnapshots(CoreProbeState state) throws Exception {
        return Map.ofEntries(
                Map.entry("position-user", indexSnapshot(field(state, "positionUserIndex"))),
                Map.entry("open-interest", indexSnapshot(field(state, "openInterestIndex"))),
                Map.entry("trigger", indexSnapshot(field(state, "triggerOrderIndex"))),
                Map.entry("algo", indexSnapshot(field(state, "algoOrderIndex"))),
                Map.entry("liquidation", indexSnapshot(field(state, "liquidationIndex"))),
                Map.entry("timer", indexSnapshot(field(state, "cancelAllAfterIndex"))),
                Map.entry("active-order", indexSnapshot(field(state, "activeOrderIndex"))),
                Map.entry("adl-position", indexSnapshot(field(state, "adlPositionIndex"))),
                Map.entry("risk-snapshot", indexSnapshot(field(state, "riskSnapshotIndex"))));
    }

    private static Map<String, Object> indexSnapshot(Object index) throws Exception {
        java.util.TreeMap<String, Object> snapshot = new java.util.TreeMap<>();
        for (var value : index.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(value.getModifiers()) || value.getName().equals("identities")) continue;
            value.setAccessible(true);
            Object current = value.get(index);
            if (current instanceof Map<?, ?> map) current = new java.util.LinkedHashMap<>(map);
            else if (current instanceof java.util.Set<?> set) current = new java.util.LinkedHashSet<>(set);
            else if (current instanceof java.util.List<?> list) current = List.copyOf(list);
            snapshot.put(value.getName(), current);
        }
        return Map.copyOf(snapshot);
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
                    false, "MATCHING_INVALID_ORDER_ID", List.of(), 0, true);

            Throwable divergence = org.assertj.core.api.Assertions.catchThrowable(
                    () -> state.completeMatching(sequence, partialMatcherFailure, 2_000, 4));

            assertThat(divergence).isInstanceOf(
                    com.surprising.aeron.service.matching.FatalMatchingDivergenceException.class);
            assertThat(state.tradingState().order(12_101).status())
                    .isEqualTo(com.surprising.aeron.service.state.CoreOrderStatus.OPEN);
            assertThat(state.tradingState().order(12_102)).isNull();
            assertThat(state.tradingState().order(12_103)).isNull();
            assertThat(state.pendingMatchingCount()).isEqualTo(1);
            assertThat(state.commandResults()).doesNotContainKey(commandId);
            assertThat(state.pendingMatching(sequence)).isNotNull();
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
            assertThat(state.tradingState()).isEqualTo(before);
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
    void closeRollsBackAnInterruptedBatchAfterItsFirstMatcherCompletion() throws Exception {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        applyBalance(state, 1001, 20_000);
        TradingRuntimeState runtime = field(state, "runtimePlaceOrderState");
        RuntimeIdentityRegistry identities = field(state, "runtimePlaceOrderIdentities");
        int quoteAssetId = identities.assetId("USDT");
        long availableBefore = runtime.balance(1001, quoteAssetId).availableUnits();
        long lockedBefore = runtime.balance(1001, quoteAssetId).lockedUnits();
        long revisionBefore = runtime.revision();
        var laneBefore = runtime.accountLane(1001);
        long businessHashBefore = state.snapshotBusinessStateHash();
        long fundsHashBefore = state.snapshotFundsStateHash();
        long identityBefore = identities.positionCheckpoint();
        UUID commandId = UUID.randomUUID();
        CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, commandId, 2,
                TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                        place(15_001, "close-first", 1_000),
                        place(15_002, "close-second", 1_000)))));

        assertThat(state.apply(batch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
        long sequence = state.matchingSequence(commandId);
        assertThat(state.completeMatching(sequence, awaitMatching(state, sequence), 2_000, 3)).isNull();
        assertThat(runtime.order(15_001)).isNotNull();
        assertThat(state.snapshotHasOutstandingReservation()).isTrue();

        state.close();

        assertThat(runtime.order(15_001)).isNull();
        assertThat(runtime.order(15_002)).isNull();
        assertThat(runtime.balance(1001, quoteAssetId).availableUnits()).isEqualTo(availableBefore);
        assertThat(runtime.balance(1001, quoteAssetId).lockedUnits()).isEqualTo(lockedBefore);
        assertThat(runtime.revision()).isEqualTo(revisionBefore);
        assertThat(runtime.accountLane(1001)).isEqualTo(laneBefore);
        assertThat(identities.positionCheckpoint()).isEqualTo(identityBefore);
        assertThat(identities.findClientKey(1001, "close-first")).isNull();
        assertThat(identities.findClientKey(1001, "close-second")).isNull();
        assertThat(state.snapshotBusinessStateHash()).isEqualTo(businessHashBefore);
        assertThat(state.snapshotFundsStateHash()).isEqualTo(fundsHashBefore);
        assertThat(state.snapshotHasOutstandingReservation()).isFalse();
    }

    @Test
    void rejectsUnsupportedRuntimeDomainBeforeItsFirstMutationDuringBatch() throws Exception {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        try {
            applySpotInstrument(state);
            applyBalance(state, 1001, 20_000);
            TradingRuntimeState runtime = field(state, "runtimePlaceOrderState");
            RuntimeIdentityRegistry identities = field(state, "runtimePlaceOrderIdentities");
            int symbolId = identities.symbolId("BTC-USDT");
            CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(15_101, "guard-first", 1_000),
                            place(15_102, "guard-second", 1_000)))));
            assertThat(state.apply(batch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);

            assertThatThrownBy(() -> runtime.putMarkPrice(new MarkPriceRuntime(
                    symbolId, 1, 50_000, 2, 2)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("order batch cannot mutate mark-price state");

            assertThat(runtime.markPrice(symbolId)).isNull();
        } finally {
            state.close();
        }
    }

    @Test
    void rejectsFinalLaneMaskBeforeProjectionHashIndexOrPublication() throws Exception {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 20_000);
            TradingRuntimeState runtime = field(state, "runtimePlaceOrderState");
            var lanesBefore = List.of(runtime.accountLanes());
            var indexesBefore = allIndexSnapshots(state);
            long revisionBefore = runtime.revision();
            long businessBefore = state.snapshotBusinessStateHash();
            long fundsBefore = state.snapshotFundsStateHash();
            long projectionBefore = state.snapshotProjectionSequence();
            long committedBefore = state.committedCoreSequence();
            var exportBefore = state.exportState().snapshot();
            state.captureCommittedPatchesForTest();
            UUID commandId = UUID.randomUUID();
            CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, commandId, 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(15_201, "lane-mask-fault", 1_000)))));

            assertThat(state.apply(batch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            state.failOrderBatchLaneMaskPreflightForTest(0);
            long sequence = state.matchingSequence(commandId);
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> state.completeMatching(
                    sequence, awaitMatching(state, sequence), 2_000, 3));

            assertThat(failure).isInstanceOf(
                    com.surprising.aeron.service.matching.FatalMatchingDivergenceException.class);
            assertThat(runtime.order(15_201)).isNull();
            assertThat(runtime.revision()).isEqualTo(revisionBefore);
            assertThat(List.of(runtime.accountLanes())).isEqualTo(lanesBefore);
            assertThat(state.snapshotBusinessStateHash()).isEqualTo(businessBefore);
            assertThat(state.snapshotFundsStateHash()).isEqualTo(fundsBefore);
            assertThat(state.snapshotProjectionSequence()).isEqualTo(projectionBefore);
            assertThat(state.committedCoreSequence()).isEqualTo(committedBefore);
            assertThat(allIndexSnapshots(state)).isEqualTo(indexesBefore);
            assertThat(state.exportState().snapshot()).isEqualTo(exportBefore);
            assertThat(state.drainCapturedCommitPatchesForTest()).isEmpty();
            assertThatThrownBy(() -> state.apply(probe(UUID.randomUUID(), 3))).isSameAs(failure);
        }
    }

    @Test
    void pipelinedFatalAfterRealFillRollsBackPositionsButKeepsObservedMatcherPrefix() throws Exception {
        try (CoreProbeState state = new CoreProbeState(ProductLine.LINEAR_PERPETUAL)) {
            applyLinearPerpetualInstrument(state);
            applyBalance(state, ProductLine.LINEAR_PERPETUAL, 1001, 1_000_000, 1);
            applyBalance(state, ProductLine.LINEAR_PERPETUAL, 1002, 1_000_000, 2);
            CoreMessage maker = command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.PLACE_ORDER_BATCH,
                    UUID.randomUUID(), 3, TradingOrderBatchCodec.encodePlaceOrderBatch(
                            new PlaceOrderBatchCommand(List.of(linearOrder(
                                    15_301, "position-maker", CoreOrderSide.SELL, 1_000, 1)))), 1002);
            assertThat(drainBatch(state, maker).status()).isEqualTo(ResponseStatus.APPLIED);

            TradingRuntimeState runtime = field(state, "runtimePlaceOrderState");
            RuntimeIdentityRegistry identities = field(state, "runtimePlaceOrderIdentities");
            var identityBefore = identities.snapshot();
            var lanesBefore = List.of(runtime.accountLanes());
            var indexesBefore = allIndexSnapshots(state);
            var treasuryBefore = treasurySnapshot(runtime);
            long businessBefore = state.snapshotBusinessStateHash();
            long fundsBefore = state.snapshotFundsStateHash();
            long projectionBefore = state.snapshotProjectionSequence();
            long committedBefore = state.committedCoreSequence();
            var exportBefore = state.exportState().snapshot();
            state.captureCommittedPatchesForTest();
            UUID fatalId = UUID.randomUUID();
            CoreMessage fatalBatch = command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.PLACE_ORDER_BATCH,
                    fatalId, 4, TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            linearOrder(15_302, "position-taker", CoreOrderSide.BUY, 1_000, 1),
                            linearOrder(15_303, "position-resting", CoreOrderSide.BUY, 900, 1)))), 1001);

            assertThat(state.apply(fatalBatch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            long sequence = state.matchingSequence(fatalId);
            var first = awaitMatching(state, sequence);
            assertThat(first.matcherEvents()).as(first.resultCode()).isNotEmpty();
            state.failOrderBatchAfterItemForTest(() -> {
                Long takerPositionKey = identities.findPositionKey(1001, "BTC-USDT");
                Long makerPositionKey = identities.findPositionKey(1002, "BTC-USDT");
                assertThat(takerPositionKey).isNotNull();
                assertThat(makerPositionKey).isNotNull();
                assertThat(runtime.position(takerPositionKey).signedQuantitySteps()).isEqualTo(1);
                assertThat(runtime.position(makerPositionKey).signedQuantitySteps()).isEqualTo(-1);
                throw new IllegalArgumentException("injected later pipelined item failure");
            });
            Throwable divergence = org.assertj.core.api.Assertions.catchThrowable(
                    () -> state.completeMatching(sequence, first, 3_000, 3));

            assertThat(divergence).isInstanceOf(
                    com.surprising.aeron.service.matching.FatalMatchingDivergenceException.class);
            long[] observedSequences = ((long[]) field(state, "appliedMatcherSequences")).clone();
            long[] observedPrefixes = ((long[]) field(state, "appliedMatcherPrefixDigests")).clone();
            assertThat(java.util.Arrays.stream(observedSequences).anyMatch(value -> value > 0)).isTrue();
            assertThat(java.util.Arrays.stream(observedPrefixes).anyMatch(value -> value
                    != com.surprising.aeron.service.matching.CoreMatchingResult.MatcherPrefix.initialDigest()))
                    .isTrue();
            assertThat(identities.findPositionKey(1001, "BTC-USDT")).isNull();
            assertThat(identities.snapshot()).isEqualTo(identityBefore);
            assertThat(runtime.order(15_301)).isNotNull();
            assertThat(runtime.order(15_301).status())
                    .isEqualTo(com.surprising.aeron.service.state.CoreOrderStatus.OPEN);
            assertThat(runtime.order(15_302)).isNull();
            assertThat(runtime.order(15_303)).isNull();
            assertThat(List.of(runtime.accountLanes())).isEqualTo(lanesBefore);
            assertThat(allIndexSnapshots(state)).isEqualTo(indexesBefore);
            assertThat(treasurySnapshot(runtime)).isEqualTo(treasuryBefore);
            assertThat(state.snapshotBusinessStateHash()).isEqualTo(businessBefore);
            assertThat(state.snapshotFundsStateHash()).isEqualTo(fundsBefore);
            assertThat(state.snapshotProjectionSequence()).isEqualTo(projectionBefore);
            assertThat(state.committedCoreSequence()).isEqualTo(committedBefore);
            assertThat(state.exportState().snapshot()).isEqualTo(exportBefore);
            assertThat(state.drainCapturedCommitPatchesForTest()).isEmpty();
            assertThat(state.takeMatchingResult(sequence)).isNull();
            assertThatThrownBy(() -> state.apply(command(ProductLine.LINEAR_PERPETUAL,
                    CoreMessageType.PROBE_INCREMENT, UUID.randomUUID(), 5,
                    com.surprising.aeron.protocol.CoreProtocol.probePayload(1), 1001))).isSameAs(divergence);
        }
    }

    @Test
    void fatalTeardownReleasesAdmissionWithoutErasingMatcherForensics() throws Exception {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        applyBalance(state, 1001, 20_000);
        RuntimeCommitJournal journal = field(state, "runtimeProjectionJournal");
        UUID commandId = UUID.randomUUID();
        CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, commandId, 2,
                TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                        place(15_401, "fatal-close-first", 1_000),
                        place(15_402, "fatal-close-second", 1_000)))));
        assertThat(state.apply(batch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
        long sequence = state.matchingSequence(commandId);
        assertThat(state.completeMatching(sequence, awaitMatching(state, sequence), 2_000, 3)).isNull();
        long[] matcherSequences = ((long[]) field(state, "appliedMatcherSequences")).clone();
        long[] matcherPrefixes = ((long[]) field(state, "appliedMatcherPrefixDigests")).clone();
        Throwable divergence = org.assertj.core.api.Assertions.catchThrowable(() -> state.completeMatching(
                sequence, new com.surprising.aeron.service.matching.CoreMatchingResult(
                        false, "EXCHANGE_CORE_FAILURE"), 2_001, 4));
        assertThat(divergence).isInstanceOf(
                com.surprising.aeron.service.matching.FatalMatchingDivergenceException.class);

        state.close();

        assertThat(state.pendingMatchingCount()).isZero();
        assertThat(state.snapshotHasOutstandingReservation()).isFalse();
        assertThat(journal.metrics().reservedEntries()).isZero();
        assertThat(journal.metrics().reservedBytes()).isZero();
        assertThat(state.exportState().metrics().reservedEvents()).isZero();
        assertThat(state.exportState().metrics().reservedBytes()).isZero();
        assertThat((long[]) field(state, "appliedMatcherSequences")).containsExactly(matcherSequences);
        assertThat((long[]) field(state, "appliedMatcherPrefixDigests")).containsExactly(matcherPrefixes);
        assertThat(state.takeMatchingResult(sequence)).isNull();
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
            assertThat(state.tradingState()).isEqualTo(before);
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
        return new PlaceOrderCommand(orderId, "BTC-USDT", 1, CoreOrderSide.BUY, 1_000, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, clientOrderId);
    }

    private static PlaceOrderCommand spotOrder(long orderId, String clientOrderId, CoreOrderSide side,
                                                long priceTicks, long quantitySteps, String reservationAsset,
                                                long reservedUnits, long makerFeeRatePpm, long takerFeeRatePpm) {
        return new PlaceOrderCommand(orderId, "BTC-USDT", 1, side, priceTicks, quantitySteps, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, clientOrderId);
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

    private static void applyLinearPerpetualInstrument(CoreProbeState state) {
        UpsertInstrumentCommand instrument = new UpsertInstrumentCommand("BTC-USDT", 1,
                ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                100_000, 50_000, 0, 0, 0, -1, 0,
                10_000_000, 1_500, 0, 1_500,
                List.of(new CoreRiskLimitBracket(1, 0, 1_500,
                        10_000_000, 100_000, 50_000)));
        assertThat(state.apply(new CoreMessage(CoreMessageHeader.command(CoreMessageType.UPSERT_INSTRUMENT,
                UUID.randomUUID(), ProductLine.LINEAR_PERPETUAL, CommandSource.OPERATIONS,
                9, 1, 0, 1_000, 1), TradingCommandCodec.encodeUpsertInstrument(instrument))).status())
                .isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.apply(new CoreMessage(CoreMessageHeader.command(CoreMessageType.APPLY_MARK_PRICE,
                UUID.randomUUID(), ProductLine.LINEAR_PERPETUAL, CommandSource.KAFKA_INPUT_BRIDGE,
                89, 1, 0, 1_000, 2), TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand("BTC-USDT", 1, 1_000, 1, 1_000)))).status())
                .isEqualTo(ResponseStatus.APPLIED);
    }

    private static PlaceOrderCommand linearOrder(long orderId, String clientOrderId,
                                                  CoreOrderSide side, long priceTicks,
                                                  long quantitySteps) {
        return new PlaceOrderCommand(orderId, "BTC-USDT", 1, side, priceTicks, quantitySteps,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, clientOrderId);
    }

    private static void applyBalance(CoreProbeState state, long userId, long units) {
        applyBalance(state, userId, units, 1);
    }

    private static void applyBalance(CoreProbeState state, long userId, long units, long sourceSequence) {
        applyBalance(state, userId, "USDT", units, sourceSequence);
    }

    private static void applyBalance(CoreProbeState state, long userId, String asset, long units,
                                     long sourceSequence) {
        assertThat(state.apply(command(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), sourceSequence,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, units)), userId))
                .status()).isEqualTo(ResponseStatus.APPLIED);
    }

    private static void applyBalance(CoreProbeState state, ProductLine productLine,
                                     long userId, long units, long sourceSequence) {
        assertThat(state.apply(command(productLine, CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(),
                sourceSequence, TradingCommandCodec.encodeBalanceAdjustment(
                        new BalanceAdjustmentCommand("USDT", units)), userId)).status())
                .isEqualTo(ResponseStatus.APPLIED);
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
