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
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreOrderBatchResult;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.MarkPriceRuntime;
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
    void spotPipelinedBatchSettlesSharedMakerOncePerLaneAndReclaimsEveryTerminal() throws Exception {
        String priorLanes = System.getProperty("surprising.aeron.account-lanes");
        System.setProperty("surprising.aeron.account-lanes", "4");
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 10_000);
            applyBalance(state, 1002, "BTC", 4, 2);
            var maker = linearOrder(82_000, "shared-maker", CoreOrderSide.SELL, 1_000, 4);
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(maker))), 1002));
            var orders = List.of(place(82_001, "shared-a", 1_000), place(82_002, "shared-b", 1_000),
                    place(82_003, "shared-c", 1_000), place(82_004, "shared-d", 1_000));
            var message = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 4,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)));
            assertThat(state.apply(message).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            long sequence = state.matchingSequence(message.header().commandId());
            Map<Long, Object> batches = field(state, "pendingOrderBatches");
            assertThat((boolean) field(batches.get(sequence), "pipelined")).isTrue();
            CoreResponse response = drainBatchAfterFirst(state, message, sequence);
            assertThat(TradingOrderBatchCodec.firstNonAppliedItem(response, 4)).isEqualTo(-1);
            var trading = state.tradingState();
            assertThat(trading.user(1001).balances().get("BTC").availableUnits()).isEqualTo(4);
            assertThat(trading.user(1001).balances().get("USDT").availableUnits()).isEqualTo(6_000);
            assertThat(trading.user(1002).balances().get("USDT").availableUnits()).isEqualTo(4_000);
            for (long user : new long[]{1001, 1002}) {
                assertThat(trading.user(user).reservations()).isEmpty();
                assertThat(trading.user(user).balances().values()).allSatisfy(balance ->
                        assertThat(balance.lockedUnits()).isZero());
            }
            for (long orderId = 82_000; orderId <= 82_004; orderId++) assertThat(trading.order(orderId)).isNull();
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(trading.businessStateHash());
            }
        } finally {
            if (priorLanes == null) System.clearProperty("surprising.aeron.account-lanes");
            else System.setProperty("surprising.aeron.account-lanes", priorLanes);
        }
    }

    @Test
    void spotBatchPreservesEarlierFillProceedsForLaterItemAdmission() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, "BTC", 1, 1);
            applyBalance(state, 1002, 2_000, 2);
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(83_000, "proceeds-maker", 1_000)))), 1002));
            var sell = linearOrder(83_001, "sell-first", CoreOrderSide.SELL, 1_000, 1);
            var buy = place(83_002, "buy-with-proceeds", 1_000);
            CoreResponse response = drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH,
                    UUID.randomUUID(), 4, TradingOrderBatchCodec.encodePlaceOrderBatch(
                            new PlaceOrderBatchCommand(List.of(sell, buy)))));
            assertThat(TradingOrderBatchCodec.firstNonAppliedItem(response, 2)).isEqualTo(-1);
            var trading = state.tradingState();
            assertThat(trading.order(83_000)).isNull();
            assertThat(trading.order(83_001)).isNull();
            assertThat(trading.order(83_002).remainingQuantitySteps()).isOne();
            assertThat(trading.user(1001).balances().get("USDT").lockedUnits()).isEqualTo(1_000);
            assertThat(trading.user(1001).balances().get("USDT").availableUnits()).isZero();
            assertThat(trading.user(1002).balances().get("USDT").availableUnits()).isEqualTo(1_000);
            assertThat(trading.user(1002).balances().get("BTC").availableUnits()).isOne();
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(trading.businessStateHash());
            }
        }
    }

    @Test
    void spotPipelinedFatalAfterFillPreservesFundsAndRecoversBySnapshotReplay() throws Exception {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 10_000);
            applyBalance(state, 1002, "BTC", 1, 2);
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            linearOrder(84_000, "fatal-maker", CoreOrderSide.SELL, 1_000, 1)))), 1002));
            byte[] recovery = state.snapshot();
            long committedBefore = state.committedCoreSequence();
            var message = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 4,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(84_001, "fatal-taker", 1_000),
                            linearOrder(84_002, "fatal-resting", CoreOrderSide.BUY, 900, 1)))));
            assertThat(state.apply(message).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            long sequence = state.matchingSequence(message.header().commandId());
            var matching = awaitMatching(state, sequence);
            state.failOrderBatchAfterItemForTest(() -> {
                throw new IllegalArgumentException("injected spot batch final commit failure");
            });
            Throwable divergence = org.assertj.core.api.Assertions.catchThrowable(
                    () -> completeEventually(state, sequence, matching, 2_000, 4));
            assertThat(divergence).isInstanceOf(
                    com.surprising.aeron.service.matching.FatalMatchingDivergenceException.class);
            assertThat(state.committedCoreSequence()).isEqualTo(committedBefore);
            TradingRuntimeState runtime = field(state, "runtimePlaceOrderState");
            RuntimeIdentityRegistry identities = field(state, "runtimePlaceOrderIdentities");
            int quote = identities.assetId("USDT");
            assertThat(runtime.balance(1001, quote).availableUnits()).isEqualTo(8_100);
            assertThat(runtime.balance(1001, quote).lockedUnits()).isEqualTo(900);
            assertThat(runtime.balance(1002, quote).availableUnits()).isEqualTo(1_000);
            assertThat(runtime.order(84_000)).isNull();
            assertThat(runtime.order(84_001)).isNull();
            assertThatThrownBy(() -> state.apply(message)).isSameAs(divergence);
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, recovery)) {
                assertThat(TradingOrderBatchCodec.firstNonAppliedItem(drainBatch(restored, message), 2))
                        .isEqualTo(-1);
                var trading = restored.tradingState();
                assertThat(trading.user(1001).balances().get("USDT").availableUnits()).isEqualTo(8_100);
                assertThat(trading.user(1001).balances().get("USDT").lockedUnits()).isEqualTo(900);
                assertThat(trading.user(1002).balances().get("USDT").availableUnits()).isEqualTo(1_000);
                assertThat(trading.user(1001).balances().get("BTC").availableUnits()).isOne();
            }
        }
    }

    @Test
    void deferredAllRejectedBatchStillPublishesItsTerminalBetweenAdjacentBatches() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);
            CoreMessage first = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(81_001, "ordered-first-a", 1_000),
                            place(81_002, "ordered-first-b", 1_000)))));
            CoreMessage rejected = command(CoreMessageType.CANCEL_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(99_991), new CancelOrderCommand(99_992)))));
            CoreMessage last = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 4,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(81_003, "ordered-last-a", 1_000),
                            place(81_004, "ordered-last-b", 1_000)))));
            var expected = new ArrayList<Long>();
            for (CoreMessage message : List.of(first, rejected, last)) {
                assertThat(state.apply(message).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                expected.add(state.matchingSequence(message.header().commandId()));
            }
            var actual = new ArrayList<Long>();
            var responses = new ArrayList<CoreResponse>();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (state.pendingMatchingCount() != 0 && System.nanoTime() < deadline) {
                state.commitReadyMatching(256, 2_000, 4, false, (sequence, response) -> {
                    actual.add(sequence);
                    responses.add(response);
                });
            }
            assertThat(actual).containsExactlyElementsOf(expected);
            assertThat(state.pendingMatchingCount()).isZero();
            assertThat(TradingOrderBatchCodec.decodeResult(responses.get(1).data()).items())
                    .allSatisfy(item -> {
                        assertThat(item.status()).isEqualTo(ResponseStatus.REJECTED);
                        assertThat(item.resultCode()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
                    });
            applyBalance(state, 1001, 7, 5);
            var query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.USER_STATE_HASH_QUERY,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 1001, 2_000, 5),
                    new byte[0]);
            assertThat(state.apply(query).status()).isEqualTo(ResponseStatus.OK);
            var balance = state.tradingState().users().get(1001L).balances().get("USDT");
            assertThat(balance.availableUnits() + balance.lockedUnits()).isEqualTo(100_007);
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash())
                        .isEqualTo(state.tradingState().businessStateHash());
            }
        }
    }

    @Test
    void coalescesSameMatcherCancellationsAndPreservesRejectedItemOrder() throws Exception {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);
            var before = state.tradingState().users().get(1001L).balances();
            for (int index = 0; index < 4; index++) {
                drainBatch(state, command(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 2 + index,
                        TradingCommandCodec.encodePlaceOrder(place(18_001 + index, "chunk-" + index, 1_000))));
            }
            MatcherPipelineGroup group = field(state, "matcherPipeline");
            var shardsField = MatcherPipelineGroup.class.getDeclaredField("shards");
            shardsField.setAccessible(true);
            var shards = (MatcherCommandPipeline[]) shardsField.get(group);
            long submittedBefore = shards[0].submittedPosition();
            CoreMessage cancel = command(CoreMessageType.CANCEL_ORDER_BATCH, UUID.randomUUID(), 6,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(18_001), new CancelOrderCommand(18_001),
                            new CancelOrderCommand(18_002),
                            new CancelOrderCommand(99_999),
                            new CancelOrderCommand(18_003), new CancelOrderCommand(18_004)))));
            assertThat(state.apply(cancel).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            final CoreResponse[] terminal = {null};
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (terminal[0] == null && System.nanoTime() < deadline) {
                state.commitReadyMatching(256, 2_000, 6, false, (sequence, response) -> terminal[0] = response);
            }
            assertThat(terminal[0]).isNotNull();
            var items = TradingOrderBatchCodec.decodeResult(terminal[0].data()).items();
            assertThat(items).extracting(CoreOrderBatchResult.Item::status).containsExactly(
                    ResponseStatus.APPLIED, ResponseStatus.REJECTED, ResponseStatus.APPLIED, ResponseStatus.REJECTED,
                    ResponseStatus.APPLIED, ResponseStatus.APPLIED);
            assertThat(items.get(1).resultCode()).isEqualTo(CoreResultCode.MATCHING_REJECTED);
            assertThat(items.get(3).resultCode()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
            assertThat(items).extracting(CoreOrderBatchResult.Item::index).containsExactly(0, 1, 2, 3, 4, 5);
            assertThat(shards[0].submittedPosition() - submittedBefore).isEqualTo(2);
            assertThat(state.pendingMatchingCount()).isZero();
            assertThat(state.tradingState().users().get(1001L).balances()).isEqualTo(before);
            assertThat(state.tradingState().users().get(1001L).reservations()).isEmpty();
            assertThat(state.tradingState().users().get(1001L).positions()).isEmpty();
            byte[] snapshot = state.snapshot();
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, snapshot)) {
                assertThat(restored.tradingState().users().get(1001L).balances()).isEqualTo(before);
                assertThat(restored.tradingState().businessStateHash())
                        .isEqualTo(state.tradingState().businessStateHash());
            }
        }
    }

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

            CoreResponse batchResponse = completeEventually(state, batchSequence, second, 2_001, 4);
            assertThat(batchResponse).isNotNull();
            var laterResult = awaitMatching(state, laterSequence);
            CoreResponse laterResponse = completeEventually(state, laterSequence, laterResult, 2_002, 5);
            assertThat(laterResponse).isNotNull();
            assertThat(batchResponse.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(laterResponse.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(batchResponse.appliedCommandCount()).isLessThan(laterResponse.appliedCommandCount());
            assertThat(state.commandResults().get(batchId).responseData()).containsExactly(batchResponse.data());
            assertThat(state.commandResults().get(laterId).responseData()).containsExactly(laterResponse.data());
            assertThat(state.pendingMatchingCount()).isZero();
            assertThat(TradingOrderBatchCodec.decodeResult(batchResponse.data()).items())
                    .extracting(item -> item.order().orderId())
                    .containsExactly(9_001L, 9_002L);
            assertThat(TradingOrderBatchCodec.decodeResult(laterResponse.data()).items())
                    .extracting(item -> item.order().orderId())
                    .containsExactly(9_003L);
        }
    }

    @Test
    void defersSinglePlaceCompletionUntilTheActiveBatchCompletes() {
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
            assertThat(state.commandResults()).doesNotContainKey(laterId);
            assertThat(state.commandResults()).doesNotContainKey(lastId);

            long batchSequence = state.matchingSequence(batchId);
            var batchMatching = awaitMatching(state, batchSequence);
            CoreResponse batchResponse = completeEventually(state, batchSequence, batchMatching, 2_000, 4);
            assertThat(batchResponse).isNotNull();
            assertThat(state.pendingMatching()).containsKeys(
                    state.matchingSequence(laterId), state.matchingSequence(lastId));
            assertThat(batchResponse.appliedCommandCount()).isEqualTo(batchSequence);
            assertThat(state.commandResults().get(batchId).responseData()).containsExactly(batchResponse.data());
            assertThat(state.commandResults()).doesNotContainKey(laterId);
            assertThat(state.commandResults()).doesNotContainKey(lastId);

            long laterSequence = state.matchingSequence(laterId);
            var laterMatching = awaitMatching(state, laterSequence);
            CoreResponse laterResponse = completeEventually(state, laterSequence, laterMatching, 2_002, 6);
            assertThat(laterResponse).isNotNull();
            assertThat(laterResponse.status()).isEqualTo(ResponseStatus.APPLIED);
            long lastSequence = state.matchingSequence(lastId);
            var lastMatching = awaitMatching(state, lastSequence);
            CoreResponse lastResponse = completeEventually(state, lastSequence, lastMatching, 2_003, 7);
            assertThat(lastResponse).isNotNull();
            assertThat(lastResponse.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(batchResponse.appliedCommandCount()).isLessThan(laterResponse.appliedCommandCount());
            assertThat(laterResponse.appliedCommandCount()).isLessThan(lastResponse.appliedCommandCount());
            assertThat(state.commandResults().get(laterId).responseData()).containsExactly(laterResponse.data());
            assertThat(state.commandResults().get(lastId).responseData()).containsExactly(lastResponse.data());
            assertThat(state.pendingMatchingCount()).isZero();
            assertThat(state.tradingState().orders().keySet())
                    .containsExactlyInAnyOrder(9_101L, 9_102L, 9_103L);
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
            assertThat(state.commandResults().get(commandId).responseData()).containsExactly(response.data());
            assertThat(state.commandResults().get(commandId).appliedCommandCount())
                    .isEqualTo(response.appliedCommandCount());

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
            CoreResponse exportQuery = state.apply(new CoreMessage(
                    CoreMessageHeader.query(CoreMessageType.EXPORT_BATCH_QUERY, UUID.randomUUID(),
                            ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 1001, 2_000, 3),
                    CoreExportCodec.encodeBatchQuery(256)));
            assertThat(exportQuery.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(exportQuery.resultCode()).isEqualTo(CoreResultCode.INVALID_MESSAGE);
            assertThat(response.requiredExportSequence()).isZero();
            assertThat(state.exportState().pending()).isEmpty();
            assertThat(state.tradingState().orders().keySet())
                    .containsExactlyInAnyOrderElementsOf(orders.stream()
                            .map(PlaceOrderCommand::orderId).toList());
        }
    }

    @Test
    void conservesFundsWhenABatchMatchesAnotherUser() {
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
            var items = TradingOrderBatchCodec.decodeResult(response.data()).items();
            assertThat(items).allSatisfy(item -> assertThat(item.status()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(items.getFirst().order()).isNull();
            assertThat(items.get(1).order().status()).isEqualTo("OPEN");
            assertThat(state.terminalRetention().containsOrder(10_502, 1001, "batch-crossing-buy")).isTrue();
            assertThat(state.terminalRetention().containsOrder(10_501, 1002, "batch-maker-sell")).isTrue();
            assertThat(items.getFirst().executions()).hasSize(1);
            assertThat(items.get(1).executions()).isEmpty();
            assertThat(state.tradingState().orders().keySet()).containsExactly(10_503L);
            assertThat(state.tradingState().user(1001).totalUnits("BTC")).isEqualTo(1_000);
            assertThat(state.tradingState().user(1002).totalUnits("BTC")).isEqualTo(1_000);
            assertThat(state.tradingState().user(1001).totalUnits("USDT")
                    + state.tradingState().user(1002).totalUnits("USDT")
                    + state.tradingState().treasuryState().feeBalances().getOrDefault("USDT", 0L))
                    .isEqualTo(2_000_000);
            assertThat(state.tradingState().user(1001).balances().get("USDT").lockedUnits()).isEqualTo(90_000);
            assertThat(state.tradingState().user(1001).reservations()).containsOnlyKeys(10_503L);
            assertThat(state.tradingState().user(1002).reservations()).isEmpty();
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
    void laneBatchRejectionRollsBackProvisionalClientAndFundsBeforeOrderedItemsResume() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.LINEAR_PERPETUAL)) {
            applyLinearPerpetualInstrument(state);
            applyBalance(state, ProductLine.LINEAR_PERPETUAL, 1001, 1_000_000, 1);
            var batch = command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.PLACE_ORDER_BATCH,
                    UUID.randomUUID(), 2, TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(
                            List.of(linearOrder(19_001, "same-client", CoreOrderSide.BUY, 500, 1),
                                    linearOrder(19_002, "same-client", CoreOrderSide.BUY, 500, 1)))), 1001);
            var result = TradingOrderBatchCodec.decodeResult(drainBatch(state, batch).data());
            assertThat(result.items()).extracting(CoreOrderBatchResult.Item::status)
                    .containsExactly(ResponseStatus.APPLIED, ResponseStatus.REJECTED);
            assertThat(result.items().get(1).resultCode()).isEqualTo(CoreResultCode.DUPLICATE_CLIENT_ORDER_ID);
            var user = state.tradingState().user(1001);
            assertThat(user.reservations()).containsOnlyKeys(19_001L);
            assertThat(user.balances().get("USDT").totalUnits()).isEqualTo(1_000_000);
            assertThat(state.pendingMatchingCount()).isZero();
            byte[] snapshot = state.snapshot();
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.LINEAR_PERPETUAL, snapshot)) {
                assertThat(restored.tradingState().businessStateHash())
                        .isEqualTo(state.tradingState().businessStateHash());
            }
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
            long[] matcherBeforeFatal = ((long[]) field(state, "appliedMatcherSequences")).clone();
            long[] matcherPrefixBeforeFatal = ((long[]) field(state, "appliedMatcherPrefixDigests")).clone();
            long committedBeforeFatal = state.committedCoreSequence();
            var exportBeforeFatal = state.exportState().snapshot();
            // A duplicate later item selects ordered partial-success admission. Pipelined fatal
            // handling is covered separately; it has no second per-item matcher callback.
            CoreMessage fatalBatch = command(CoreMessageType.PLACE_ORDER_BATCH, fatalId, 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(11_004, "fatal-fourth", 1_000),
                            place(11_005, "fatal-fifth", 1_000),
                            place(11_004, "fatal-duplicate", 1_000)))));
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
            assertThat(runtime.order(11_004)).isNotNull();
            assertThat(runtime.reservation(11_004)).isNotNull();
            assertThat((int) invoke(runtime, "pendingReservationCount", new Class<?>[]{long.class}, 1001L))
                    .isPositive();
            assertThat((int) invoke(runtime, "pendingReservationCount", new Class<?>[0])).isPositive();
            assertThat((long) invoke(runtime, "pendingReservedUnits",
                    new Class<?>[]{long.class, int.class}, 1001L, quoteAssetId)).isPositive();
            assertThat(state.commandResults()).doesNotContainKey(fatalId);
            assertThat(state.pendingMatching(fatalSequence)).isNotNull();
            assertThat(state.pendingMatching(fatalSequence).pendingStateHash()).isNotZero();
            assertThat(state.pendingMatchingCount()).isOne();
            assertThat(state.matchingSequence(fatalId)).isEqualTo(fatalSequence);
            assertThat(state.snapshotHasOutstandingReservation()).isTrue();
            assertThat((long[]) field(state, "appliedMatcherSequences")).containsExactly(matcherAfterFirst);
            assertThat((long[]) field(state, "appliedMatcherPrefixDigests"))
                    .containsExactly(matcherPrefixAfterFirst);
            assertThat(state.committedCoreSequence()).isEqualTo(committedBeforeFatal);
            assertThat(state.exportState().snapshot()).isEqualTo(exportBeforeFatal);
            assertThat(identities.findClientKey(1001, "fatal-fourth")).isNotNull();
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
                Map.entry("adl-position", indexSnapshot(field(state, "adlPositionIndex"))));
    }

    private static Map<String, Object> indexSnapshot(Object index) throws Exception {
        java.util.TreeMap<String, Object> snapshot = new java.util.TreeMap<>();
        for (var value : index.getClass().getDeclaredFields()) {
            // Query scratch has no indexed business state and may be lazily allocated.
            if (Modifier.isStatic(value.getModifiers()) || value.getName().equals("identities")
                    || value.getName().equals("pageScratch")) continue;
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
            assertThat(state.tradingState().order(12_001)).isNull();
            assertThat(state.tradingState().order(12_002).status())
                    .isEqualTo(com.surprising.aeron.service.state.CoreOrderStatus.OPEN);

            CoreMessage cancel = command(CoreMessageType.CANCEL_ORDER_BATCH, UUID.randomUUID(), 4,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(12_002), new CancelOrderCommand(19_999)))));
            CoreOrderBatchResult canceled = TradingOrderBatchCodec.decodeResult(drainBatch(state, cancel).data());
            assertThat(canceled.items()).extracting(CoreOrderBatchResult.Item::status)
                    .containsExactly(ResponseStatus.APPLIED, ResponseStatus.REJECTED);
            assertThat(canceled.items().get(1).resultCode()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
            assertThat(state.tradingState().order(12_002)).isNull();
            assertThat(state.tradingState().user(1001).reservations()).isEmpty();
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
    void closeDoesNotRollBackAnInterruptedBatchAfterObservedMatcherFact() throws Exception {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        applyBalance(state, 1001, 20_000);
        TradingRuntimeState runtime = field(state, "runtimePlaceOrderState");
        RuntimeIdentityRegistry identities = field(state, "runtimePlaceOrderIdentities");
        int quoteAssetId = identities.assetId("USDT");
        long availableBefore = runtime.balance(1001, quoteAssetId).availableUnits();
        long lockedBefore = runtime.balance(1001, quoteAssetId).lockedUnits();
        long revisionBefore = runtime.revision();
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

        assertThat(runtime.order(15_001)).isNotNull();
        assertThat(runtime.order(15_002)).isNotNull();
        assertThat(runtime.balance(1001, quoteAssetId).availableUnits()).isLessThan(availableBefore);
        assertThat(runtime.balance(1001, quoteAssetId).lockedUnits()).isGreaterThan(lockedBefore);
        assertThat(runtime.revision()).isGreaterThan(revisionBefore);
        assertThat(runtime.accountLane(1001).ownerThreadName()).isEqualTo(Thread.currentThread().getName());
        assertThat(identities.positionCheckpoint()).isEqualTo(identityBefore);
        assertThat(identities.findClientKey(1001, "close-first")).isNotNull();
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
                            place(15_101, "guard-first", 1_000)))));
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
    void finalLaneMaskFailureStopsWithoutRollingBackObservedMatcherFact() throws Exception {
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
            assertThat(runtime.order(15_201)).isNotNull();
            assertThat(runtime.revision()).isGreaterThan(revisionBefore);
            assertThat(state.snapshotBusinessStateHash()).isEqualTo(businessBefore);
            assertThat(state.snapshotFundsStateHash()).isEqualTo(fundsBefore);
            assertThat(state.snapshotProjectionSequence()).isEqualTo(projectionBefore);
            assertThat(state.committedCoreSequence()).isEqualTo(committedBefore);
            assertThat(state.exportState().snapshot()).isEqualTo(exportBefore);
            assertThatThrownBy(() -> state.apply(probe(UUID.randomUUID(), 3))).isSameAs(failure);
        }
    }

    @Test
    void pipelinedFatalAfterRealFillKeepsAppliedLaneStateForSnapshotLogRecovery() throws Exception {
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
                    () -> completeEventually(state, sequence, first, 3_000, 3));

            assertThat(divergence).isInstanceOf(
                    com.surprising.aeron.service.matching.FatalMatchingDivergenceException.class);
            long[] observedSequences = ((long[]) field(state, "appliedMatcherSequences")).clone();
            long[] observedPrefixes = ((long[]) field(state, "appliedMatcherPrefixDigests")).clone();
            assertThat(java.util.Arrays.stream(observedSequences).anyMatch(value -> value > 0)).isTrue();
            assertThat(java.util.Arrays.stream(observedPrefixes).anyMatch(value -> value
                    != com.surprising.aeron.service.matching.CoreMatchingResult.MatcherPrefix.initialDigest()))
                    .isTrue();
            Long takerPositionKey = identities.findPositionKey(1001, "BTC-USDT");
            Long makerPositionKey = identities.findPositionKey(1002, "BTC-USDT");
            assertThat(takerPositionKey).isNotNull();
            assertThat(makerPositionKey).isNotNull();
            assertThat(runtime.position(takerPositionKey).signedQuantitySteps()).isEqualTo(1);
            assertThat(runtime.position(makerPositionKey).signedQuantitySteps()).isEqualTo(-1);
            assertThat(identities.snapshot()).isNotEqualTo(identityBefore);
            assertThat(runtime.order(15_301)).isNull();
            assertThat(runtime.order(15_302)).isNull();
            assertThat(runtime.reservation(15_301)).isNull();
            assertThat(runtime.reservation(15_302)).isNull();
            assertThat(state.snapshotProjectionSequence()).isEqualTo(projectionBefore);
            assertThat(state.committedCoreSequence()).isEqualTo(committedBefore);
            assertThat(state.exportState().snapshot()).isEqualTo(exportBefore);
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
                        place(15_402, "fatal-close-second", 1_000),
                        place(15_401, "fatal-close-duplicate", 1_000)))));
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

    private static CoreResponse completeEventually(
            CoreProbeState state, long sequence,
            com.surprising.aeron.service.matching.CoreMatchingResult matching,
            long clusterTimestamp, long clusterPosition) {
        CoreResponse completed = null;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (completed == null && System.nanoTime() < deadline) {
            completed = state.completeMatching(sequence, matching, clusterTimestamp, clusterPosition);
            if (completed == null) Thread.onSpinWait();
        }
        if (completed == null) throw new AssertionError("account lane settlement did not complete");
        return completed;
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
