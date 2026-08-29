package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.protocol.AckExportCommand;
import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreRiskScanControlCodec;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class CoreProbeStateTest {

    @Test
    void nonMatchingTreasuryCommandExportsItsChangedAssetAndConservedPostings() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.LINEAR_PERPETUAL)) {
            UUID commandId = UUID.randomUUID();
            CoreMessage adjustment = new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.ADJUST_INSURANCE_FUND, commandId, ProductLine.LINEAR_PERPETUAL,
                    CommandSource.OPERATIONS, 9, 1, 0, 1_000, 1),
                    TradingCommandCodec.encodeAdjustInsuranceFund(
                            new AdjustInsuranceFundCommand("USDT", 25)));

            assertThat(state.apply(adjustment).status()).isEqualTo(ResponseStatus.APPLIED);

            assertThat(state.exportState().pendingCount()).isEqualTo(1);
            var pending = state.exportState().pending();
            assertThat(state.exportState().encodedPendingCount()).isEqualTo(pending.size());
            var event = pending.stream()
                    .map(message -> CoreExportCodec.decodeEvent(message.payloadUnsafe()))
                    .filter(value -> value.commandId().equals(commandId))
                    .findFirst().orElseThrow();
            assertThat(event.changedTreasuryAssets())
                    .extracting(com.surprising.aeron.protocol.CoreTreasuryAssetView::asset)
                    .containsExactly("USDT");
            assertThat(event.fundsPostings()).hasSize(2);
            assertThat(event.fundsPostings().stream()
                    .mapToLong(com.surprising.aeron.protocol.CoreFundsPostingView::units).sum()).isZero();
        }
    }

    @Test
    @Timeout(5)
    void appendsCoreFactWithoutWaitingForMaterializationAndPublishesOnlyReadyPrefix() throws Exception {
        CountDownLatch enteredMaterializer = new CountDownLatch(1);
        CountDownLatch releaseMaterializer = new CountDownLatch(1);
        try (CoreExportState exportState = new CoreExportState()) {
            CoreMessage command = command(UUID.randomUUID(), 1, 1);
            var transition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
            exportState.append(new CoreExportState.Draft(command, ResponseStatus.APPLIED, CoreResultCode.NONE,
                    1, 1, 0, 0, 0, 1, 1, transition, 1, 0, List.of(), sequence -> {
                        enteredMaterializer.countDown();
                        try {
                            if (!releaseMaterializer.await(3, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Core Fact materializer was not released");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Core Fact materializer interrupted", exception);
                        }
                        return new com.surprising.aeron.protocol.CoreExportEvent(
                                sequence, 1, 1, command.header().commandId(), command.header().messageType(),
                                ResponseStatus.APPLIED, CoreResultCode.NONE, command.header().userId(),
                                command.payloadUnsafe(), List.of(), List.of(), List.of(), List.of(), List.of(),
                                List.of(), List.of(), 0, 0, 0, transition.routeVersion(), 1, 1, 1,
                                transition, 1, List.of());
                    }));

            assertThat(enteredMaterializer.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(exportState.pendingCount()).isEqualTo(1);
            assertThat(exportState.batch(1)).isEmpty();

            releaseMaterializer.countDown();
            assertThat(exportState.pending()).hasSize(1);
            assertThat(exportState.batch(1)).hasSize(1);
        } finally {
            releaseMaterializer.countDown();
        }
    }

    @Test
    @Timeout(5)
    void acknowledgesPrimitiveTerminalIdsWithoutWaitingForFactMaterialization() throws Exception {
        CountDownLatch enteredMaterializer = new CountDownLatch(1);
        CountDownLatch releaseMaterializer = new CountDownLatch(1);
        try (CoreExportState exportState = new CoreExportState()) {
            CoreMessage command = command(UUID.randomUUID(), 1, 1);
            var transition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
            exportState.append(new CoreExportState.Draft(command, ResponseStatus.APPLIED, CoreResultCode.NONE,
                    1, 1, 0, 0, 0, 1, 1, transition, 1, 1, List.of(42L), sequence -> {
                        enteredMaterializer.countDown();
                        try {
                            if (!releaseMaterializer.await(3, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Core Fact materializer was not released");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Core Fact materializer interrupted", exception);
                        }
                        return new com.surprising.aeron.protocol.CoreExportEvent(
                                sequence, 1, 1, command.header().commandId(), command.header().messageType(),
                                ResponseStatus.APPLIED, CoreResultCode.NONE, command.header().userId(),
                                command.payloadUnsafe(), List.of(), List.of(), List.of(), List.of(), List.of(),
                                List.of(), List.of(), 0, 0, 0, transition.routeVersion(), 1, 1, 1,
                                transition, 1, List.of());
                    }));

            assertThat(enteredMaterializer.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(exportState.acknowledge(new AckExportCommand(1))).containsExactly(42L);
            assertThat(exportState.pendingCount()).isZero();
        } finally {
            releaseMaterializer.countDown();
        }
    }

    @Test
    void exposesBoundedMatcherCompletionAndLaneContextMetrics() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            CoreLaneMetrics metrics = state.laneMetrics();

            assertThat(metrics.matchingEngineCount()).isEqualTo(4);
            assertThat(metrics.accountLaneCount()).isEqualTo(4);
            assertThat(metrics.matcherDispatchCapacity()).isEqualTo(4_096);
            assertThat(metrics.matchingCompletionCapacity()).isEqualTo(4_096);
            assertThat(metrics.commandContextCapacity()).isEqualTo(4_096);
            assertThat(metrics.matcherDispatchDepth()).isZero();
            assertThat(metrics.commandContextDepth()).isZero();
            assertThat(metrics.accountLaneQueueCapacities()).containsOnly(4_096);
            assertThat(metrics.accountLaneQueueHighWaterMarks()).containsOnly(0);
            assertThat(metrics.accountLaneRejectedSubmissions()).containsOnly(0);
            assertThat(metrics.accountLaneCompletedOperations()).hasSize(16);
        }
    }

    @Test
    void exposesLaneMetricsThroughTheCommittedCoreQuerySurface() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            CoreResponse response = state.apply(query(CoreMessageType.LANE_METRICS_QUERY, 0, new byte[0]));

            assertThat(response.status()).isEqualTo(ResponseStatus.OK);
            var metrics = com.surprising.aeron.protocol.CoreLaneMetricsCodec.decode(response.data());
            assertThat(metrics.accountLaneCount()).isEqualTo(4);
            assertThat(metrics.accountLaneQueueCapacities()).containsOnly(4_096);
            assertThat(metrics.accountLaneQueueDepths()).containsOnly(0);
            assertThat(metrics.accountLaneOldestPendingSequences()).containsOnly(0);
            assertThat(metrics.accountLaneCompletedOperations()).hasSize(16);
        }
    }

    @Test
    void replicatedTimerBoundaryDoesNotBlockOnIncompleteLocalMatching() throws Exception {
        CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> matching =
                new CompletableFuture<>();
        AtomicReference<com.surprising.aeron.service.matching.CoreMatchingResult> result = new AtomicReference<>();
        Thread timer = Thread.ofVirtual().start(() -> result.set(CoreProbeState.awaitMatchingCompletion(matching)));

        timer.join(200);

        assertThat(timer.isAlive()).isFalse();
        assertThat(result.get()).isNull();
        matching.complete(new com.surprising.aeron.service.matching.CoreMatchingResult(
                true, "SUCCESS"));
    }

    @Test
    void acceptedLiquidationBatchDoesNotFailWhenItsRiskScanWasSupersededDuringMatching() throws Exception {
        try (CoreProbeState state = new CoreProbeState(ProductLine.LINEAR_PERPETUAL)) {
            var batch = new com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand(
                    List.of(), 1, 0,
                    new com.surprising.aeron.protocol.CoreRiskScanContinuation("BTC-USDT-SWAP", 7, 1001), 1);
            var accepted = new com.surprising.aeron.service.matching.CoreMatchingResult(
                    true, "SUCCESS");
            var apply = CoreProbeState.class.getDeclaredMethod("applyLiquidationBatch",
                    com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand.class,
                    com.surprising.aeron.service.matching.CoreMatchingResult.class);
            var changedOrders = CoreProbeState.class.getDeclaredField("commandChangedOrderIds");
            var changedUsers = CoreProbeState.class.getDeclaredField("commandChangedUserIds");
            apply.setAccessible(true);
            changedOrders.setAccessible(true);
            changedUsers.setAccessible(true);
            changedOrders.set(state, List.of());
            changedUsers.set(state, List.of());

            org.assertj.core.api.Assertions.assertThatCode(() -> apply.invoke(state, batch, accepted))
                    .doesNotThrowAnyException();
            assertThat(state.tradingState().riskState().scan().riskComplete()).isTrue();
        }
    }

    @Test
    void bindsRuntimeStateToTheFirstCoreCommandThread() throws InterruptedException {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            AtomicReference<CoreResponse> response = new AtomicReference<>();
            Thread coreAgent = new Thread(() -> response.set(state.apply(tradingCommand(
                    CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))));

            coreAgent.start();
            coreAgent.join();

            assertThat(response.get().status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().user(1001).totalUnits("USDT")).isEqualTo(10_000);
        }
    }

    @Test
    void materializesStableTriggerCreationOnceAndReplaysItAfterRecovery() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 2))));
        UUID commandId = UUID.randomUUID();
        var template = new com.surprising.aeron.protocol.CoreTriggerOrderStateView(501,
                ProductLine.SPOT, 1001, "tp-501", "", "BTC-USDT", CoreOrderSide.SELL,
                com.surprising.aeron.protocol.CoreTriggerOrderType.TAKE_PROFIT,
                com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL, 70_000,
                0, 0, 0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                "", commandId.toString(), 0, 0, 0, 0, 1);
        CoreMessage command = tradingCommand(CoreMessageType.PLACE_TRIGGER_ORDER, commandId, 2,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(template));

        CoreResponse applied = state.apply(command, 5_000, 2);
        CoreResponse duplicate = state.apply(command, 9_000, 3);
        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot());
        CoreResponse recoveredDuplicate = restored.apply(command, 12_000, 4);
        var persisted = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeList(applied.data()).getFirst();

        assertThat(applied.status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(recoveredDuplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(duplicate.data()).isEqualTo(applied.data());
        assertThat(recoveredDuplicate.data()).isEqualTo(applied.data());
        assertThat(persisted.createdAtEpochMillis()).isEqualTo(5_000);
        assertThat(persisted.updatedAtEpochMillis()).isEqualTo(5_000);
    }

    @Test
    void materializesStableAlgoCreationOnceAndRejectsChangedRetryPayload() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        UUID commandId = UUID.randomUUID();
        var template = new com.surprising.aeron.protocol.CoreAlgoOrderView(701, 1001, "algo-701", "BTC-USDT", 0,
                CoreOrderSide.BUY, 0, 100, 25, 10, 40, CoreMarginMode.CROSS, CorePositionSide.NET,
                false, false, CoreTimeInForce.IOC, 0, 0, "", commandId.toString(), 0, 0, 0, 0, 0, 1,
                List.of(), 0, 0, 0);
        CoreMessage command = tradingCommand(CoreMessageType.UPSERT_ALGO_ORDER, commandId, 1,
                com.surprising.aeron.protocol.CoreAlgoOrderCodec.encode(template));

        CoreResponse applied = state.apply(command, 5_000, 1);
        CoreResponse duplicate = state.apply(command, 9_000, 2);
        var changed = new com.surprising.aeron.protocol.CoreAlgoOrderView(701, 1001, "algo-701", "BTC-USDT", 0,
                CoreOrderSide.BUY, 0, 200, 25, 10, 40, CoreMarginMode.CROSS, CorePositionSide.NET,
                false, false, CoreTimeInForce.IOC, 0, 0, "", commandId.toString(), 0, 0, 0, 0, 0, 1,
                List.of(), 0, 0, 0);
        CoreResponse conflict = state.apply(tradingCommand(CoreMessageType.UPSERT_ALGO_ORDER, commandId, 2,
                com.surprising.aeron.protocol.CoreAlgoOrderCodec.encode(changed)), 10_000, 3);
        CoreMessage query = query(CoreMessageType.ALGO_ORDER_QUERY, 1001,
                com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeQuery(1001, 701, "", 0, 1));
        var persisted = com.surprising.aeron.protocol.CoreAlgoOrderCodec.decodeList(state.apply(query).data()).getFirst();

        assertThat(applied.status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(conflict.resultCode()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
        assertThat(persisted.quantitySteps()).isEqualTo(100);
        assertThat(persisted.startAtEpochMillis()).isEqualTo(5_000);
        assertThat(persisted.createdAtEpochMillis()).isEqualTo(5_000);
    }

    @Test
    void riskScanControlIsVersionedDeduplicatedAndSnapshotRecovered() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        var defaultQuery = state.apply(query(CoreMessageType.RISK_SCAN_CONTROL_QUERY, 0, new byte[0]));
        var initial = CoreRiskScanControlCodec.decodeView(defaultQuery.data());
        long initialHash = state.tradingState().businessStateHash();
        UUID commandId = UUID.randomUUID();
        String reason = "审".repeat(500);
        byte[] payload = CoreRiskScanControlCodec.encodeCommand(new UpdateRiskScanControlCommand(
                initial.version(), "Production scan", false, 250, 384, "admin-7", reason));
        CoreMessage update = tradingCommand(CoreMessageType.UPDATE_RISK_SCAN_CONTROL, commandId, 1, payload);

        CoreResponse applied = state.apply(update);
        var updated = CoreRiskScanControlCodec.decodeView(applied.data());
        CoreResponse stale = state.apply(tradingCommand(CoreMessageType.UPDATE_RISK_SCAN_CONTROL,
                UUID.randomUUID(), 2, payload));
        CoreResponse duplicate = state.apply(update);
        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot());
        var recovered = CoreRiskScanControlCodec.decodeView(
                restored.apply(query(CoreMessageType.RISK_SCAN_CONTROL_QUERY, 0, new byte[0])).data());

        assertThat(initial.version()).isEqualTo(1);
        assertThat(applied.status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.updatedBy()).isEqualTo("admin-7");
        assertThat(updated.reason()).isEqualTo(reason);
        assertThat(state.tradingState().businessStateHash()).isNotEqualTo(initialHash);
        assertThat(stale.resultCode()).isEqualTo(CoreResultCode.STALE_RISK_SCAN_CONTROL_VERSION);
        assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(CoreRiskScanControlCodec.decodeView(duplicate.data())).isEqualTo(updated);
        assertThat(recovered).isEqualTo(updated);
    }

    @Test
    void appliesCommandOnceAndReturnsOriginalDuplicateResult() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        CoreMessage command = command(UUID.randomUUID(), 1, 7);

        var applied = state.apply(command);
        var duplicate = state.apply(command);

        assertThat(applied.status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(duplicate.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(duplicate.appliedCommandCount()).isEqualTo(1);
        assertThat(duplicate.stateHash()).isEqualTo(applied.stateHash());
        assertThat(state.probeValue()).isEqualTo(7);
    }

    @Test
    void queriesCommittedCommandResultAfterResultUnknown() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        UUID commandId = UUID.randomUUID();
        CoreMessage command = command(commandId, 1, 7);

        var applied = state.apply(command);
        var result = state.apply(new CoreMessage(CoreMessageHeader.query(
                CoreMessageType.COMMAND_RESULT_QUERY, UUID.randomUUID(), ProductLine.SPOT,
                CommandSource.GATEWAY, 7, 0, 1001, 1_000, 2),
                CoreStateQueryCodec.encodeCommandResultQuery(commandId)));

        assertThat(result.status()).isEqualTo(ResponseStatus.OK);
        assertThat(result.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(result.resultCode()).isEqualTo(CoreResultCode.NONE);
        assertThat(result.appliedCommandCount()).isEqualTo(applied.appliedCommandCount());
        assertThat(result.stateHash()).isEqualTo(applied.stateHash());

        var unknown = state.apply(new CoreMessage(CoreMessageHeader.query(
                CoreMessageType.COMMAND_RESULT_QUERY, UUID.randomUUID(), ProductLine.SPOT,
                CommandSource.GATEWAY, 7, 0, 1001, 1_000, 3),
                CoreStateQueryCodec.encodeCommandResultQuery(UUID.randomUUID())));
        assertThat(unknown.status()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(unknown.resultCode()).isEqualTo(CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION);
    }

    @Test
    void sourceHighWatermarkSurvivesIdempotencyWindowEviction() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        for (int sequence = 1; sequence <= CoreProbeState.MAX_IDEMPOTENCY_RESULTS + 1; sequence++) {
            assertThat(state.apply(command(UUID.randomUUID(), sequence, 1)).status())
                    .isEqualTo(ResponseStatus.APPLIED);
        }

        var staleRetry = state.apply(command(UUID.randomUUID(), 1, 100));

        assertThat(staleRetry.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(state.appliedCommandCount()).isEqualTo(CoreProbeState.MAX_IDEMPOTENCY_RESULTS + 1L);
        assertThat(state.probeValue()).isEqualTo(CoreProbeState.MAX_IDEMPOTENCY_RESULTS + 1L);
    }

    @Test
    void restoredStateRejectsUnboundedSourceSequenceRegistry() {
        Map<CoreProbeState.SourceKey, Long> sourceSequences = new java.util.LinkedHashMap<>();
        for (long sourceId = 0; sourceId <= CoreProbeState.MAX_SOURCE_SEQUENCES; sourceId++) {
            sourceSequences.put(new CoreProbeState.SourceKey(CommandSource.GATEWAY, sourceId), 1L);
        }

        assertThatThrownBy(() -> CoreProbeState.restore(ProductLine.SPOT, 0, 0, Map.of(), sourceSequences,
                com.surprising.aeron.service.state.TradingCoreState.empty(ProductLine.SPOT),
                new CoreExportState()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotRoundTripPreservesStateHashAndDeduplication() {
        CoreProbeState original = new CoreProbeState(ProductLine.INVERSE_DELIVERY);
        CoreMessage first = command(ProductLine.INVERSE_DELIVERY, UUID.randomUUID(), 1, 11);
        CoreMessage second = command(ProductLine.INVERSE_DELIVERY, UUID.randomUUID(), 2, -3);
        original.apply(first);
        original.apply(second);

        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.INVERSE_DELIVERY, original.snapshot());

        assertThat(restored.appliedCommandCount()).isEqualTo(original.appliedCommandCount());
        assertThat(restored.probeValue()).isEqualTo(8);
        assertThat(restored.stateHash()).isEqualTo(original.stateHash());
        assertThat(restored.apply(first).status()).isEqualTo(ResponseStatus.DUPLICATE);
    }

    @Test
    void rejectsAnotherProductLineWithoutMutatingState() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);

        var result = state.apply(command(ProductLine.OPTION, UUID.randomUUID(), 1, 10));

        assertThat(result.status()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(state.appliedCommandCount()).isZero();
        assertThat(state.probeValue()).isZero();
    }

    @Test
    void appliesTradingCommandsOnceAndSnapshotsAuthoritativeState() {
        CoreProbeState original = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(original);
        UUID adjustmentId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        CoreMessage adjustment = tradingCommand(CoreMessageType.ADJUST_BALANCE, adjustmentId, 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));
        CoreMessage place = tradingCommand(CoreMessageType.PLACE_ORDER, placeId, 2,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(91, "BTC-USDT", 1, CoreOrderSide.BUY, 1_000, 2, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "client-91")));

        assertThat(original.apply(adjustment).status()).isEqualTo(ResponseStatus.APPLIED);
        var placeResponse = applyAndDrain(original, place);
        assertThat(placeResponse.status()).isEqualTo(ResponseStatus.APPLIED);
        var placeResult = com.surprising.aeron.protocol.CoreCommandResultCodec.decode(placeResponse.data());
        assertThat(placeResult.orders()).extracting(value -> value.orderId()).containsExactly(91L);
        assertThat(placeResult.orders().getFirst().clusterPosition()).isPositive();
        assertThat(placeResult.executions()).isEmpty();
        original.exportState().pending();
        var placeEvent = CoreExportCodec.decodeBatchResponse(original.apply(query(CoreMessageType.EXPORT_BATCH_QUERY, 0,
                        CoreExportCodec.encodeBatchQuery(10))).data()).events().stream()
                .map(message -> CoreExportCodec.decodeEvent(message.payload()))
                .filter(event -> event.commandId().equals(placeId))
                .findFirst().orElseThrow();
        assertThat(placeEvent.changedUsers()).extracting(value -> value.userId()).containsExactly(1001L);
        assertThat(placeEvent.changedOrders()).extracting(value -> value.orderId()).containsExactly(91L);
        var duplicatePlace = original.apply(place);
        assertThat(duplicatePlace.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(com.surprising.aeron.protocol.CoreCommandResultCodec.decode(duplicatePlace.data())
                .orders()).extracting(value -> value.orderId()).containsExactly(91L);
        assertThat(original.tradingState().user(1001).balances().get("USDT").availableUnits()).isEqualTo(7_998);

        var trigger = new com.surprising.aeron.protocol.CoreTriggerOrderStateView(501,
                ProductLine.SPOT, 1001, "tp-501", "", "BTC-USDT", CoreOrderSide.SELL,
                com.surprising.aeron.protocol.CoreTriggerOrderType.TAKE_PROFIT,
                com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL, 70_000,
                0, 0, 0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                "", "trace", 0, 0, 1_000, 1_000, 1);
        CoreMessage triggerPlace = tradingCommand(CoreMessageType.PLACE_TRIGGER_ORDER, UUID.randomUUID(), 3,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(trigger));
        assertThat(original.apply(triggerPlace).status()).isEqualTo(ResponseStatus.APPLIED);
        original.exportState().pending();
        var triggerEvent = CoreExportCodec.decodeBatchResponse(original.apply(query(CoreMessageType.EXPORT_BATCH_QUERY, 0,
                        CoreExportCodec.encodeBatchQuery(10))).data()).events().stream()
                .map(message -> CoreExportCodec.decodeEvent(message.payload()))
                .filter(event -> event.commandId().equals(triggerPlace.header().commandId()))
                .findFirst().orElseThrow();
        assertThat(triggerEvent.changedTriggerOrders()).extracting(value -> value.triggerOrderId())
                .containsExactly(501L);
        CoreMessage triggerQuery = query(CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY, 1001,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeQuery(
                        new com.surprising.aeron.protocol.CoreTriggerOrderQuery(0, "BTC-USDT", 0, 10)));
        assertThat(com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeList(original.apply(triggerQuery).data()))
                .extracting(value -> value.triggerOrderId()).containsExactly(501L);

        CoreMessage userQuery = query(CoreMessageType.USER_STATE_QUERY, 1001, new byte[0]);
        CoreMessage orderQuery = query(CoreMessageType.ORDER_STATE_QUERY, 1001,
                TradingCommandCodec.encodeOrderStateQuery(91));
        CoreMessage clientOrderQuery = query(CoreMessageType.CLIENT_ORDER_STATE_QUERY, 1001,
                CoreStateQueryCodec.encodeClientOrderStateQuery("client-91"));
        var userResult = original.apply(userQuery);
        var orderResult = original.apply(orderQuery);
        assertThat(userResult.status()).isEqualTo(ResponseStatus.OK);
        assertThat(CoreStateQueryCodec.decodeUserState(userResult.data()).balances().getFirst().lockedUnits())
                .isEqualTo(2_002);
        assertThat(orderResult.status()).isEqualTo(ResponseStatus.OK);
        assertThat(CoreStateQueryCodec.decodeOrderState(orderResult.data()).orderId()).isEqualTo(91);
        var byClientId = CoreStateQueryCodec.decodeOrderState(original.apply(clientOrderQuery).data());
        assertThat(byClientId.orderId()).isEqualTo(91);
        assertThat(byClientId.commandId()).isEqualTo(placeId);
        assertThat(byClientId.clientOrderId()).isEqualTo("client-91");
        assertThat(byClientId.makerFeeRatePpm()).isEqualTo(-10);
        assertThat(byClientId.takerFeeRatePpm()).isEqualTo(20);
        assertThat(byClientId.createdAtEpochMillis()).isPositive();
        assertThat(byClientId.clusterPosition()).isPositive();
        CoreMessage openOrdersQuery = query(CoreMessageType.USER_OPEN_ORDERS_QUERY, 1001,
                CoreStateQueryCodec.encodeOpenOrdersQuery(
                        new com.surprising.aeron.protocol.CoreOpenOrdersQuery("BTC-USDT", 0, 10)));
        var openOrders = CoreStateQueryCodec.decodeOpenOrders(original.apply(openOrdersQuery).data());
        assertThat(openOrders.orders()).extracting(order -> order.orderId()).containsExactly(91L);
        assertThat(original.apply(query(CoreMessageType.BOOK_STATE_QUERY, 0, new byte[0])).resultCode())
                .isEqualTo(CoreResultCode.INVALID_COMMAND);
        var book = CoreStateQueryCodec.decodeOrderBookView(applyBookQuery(original,
                query(CoreMessageType.BOOK_STATE_QUERY, 0,
                        CoreStateQueryCodec.encodeOrderBookQuery(
                                new com.surprising.aeron.protocol.CoreOrderBookQuery("BTC-USDT", 30)))).data());
        assertThat(book.exportSequence()).isEqualTo(4);
        assertThat(book.levels()).singleElement().satisfies(value -> {
            assertThat(value.priceTicks()).isEqualTo(1_000);
            assertThat(value.quantitySteps()).isEqualTo(2);
            assertThat(value.orderCount()).isEqualTo(1);
        });
        CoreMessage bootstrapQuery = query(CoreMessageType.ORDER_BOOK_BOOTSTRAP_QUERY, 0,
                CoreStateQueryCodec.encodeOrderBookBootstrapQuery(
                        new com.surprising.aeron.protocol.CoreOrderBookBootstrapQuery("", "", 1, 30)));
        var bootstrap = CoreStateQueryCodec.decodeOrderBookBootstrapPage(
                applyBookQuery(original, bootstrapQuery).data());
        assertThat(bootstrap.complete()).isTrue();
        assertThat(bootstrap.exportSequence()).isEqualTo(4);
        assertThat(bootstrap.levels()).isEqualTo(book.levels());

        UUID duplicateId = UUID.randomUUID();
        CoreMessage duplicateClientOrder = tradingCommand(CoreMessageType.PLACE_ORDER, duplicateId, 4,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(92, "BTC-USDT", 1, CoreOrderSide.BUY, 900, 1, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "client-91")));
        long availableBeforeDuplicate = original.tradingState().user(1001).balances().get("USDT").availableUnits();
        assertThat(original.apply(duplicateClientOrder).resultCode()).isEqualTo(CoreResultCode.DUPLICATE_CLIENT_ORDER_ID);
        assertThat(original.tradingState().order(92)).isNull();
        assertThat(original.tradingState().user(1001).balances().get("USDT").availableUnits())
                .isEqualTo(availableBeforeDuplicate);

        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, original.snapshot());
        assertThat(restored.stateHash()).isEqualTo(original.stateHash());
        assertThat(restored.tradingState()).isEqualTo(original.tradingState());
        assertThat(com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeList(restored.apply(triggerQuery).data()))
                .extracting(value -> value.triggerOrderId()).containsExactly(501L);
        assertThat(CoreStateQueryCodec.decodeOpenOrders(restored.apply(openOrdersQuery).data()).orders())
                .extracting(order -> order.orderId()).containsExactly(91L);

        CoreMessage cancel = tradingCommand(CoreMessageType.CANCEL_ORDER, UUID.randomUUID(), 5,
                TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(91)));
        var cancelResponse = applyAndDrain(restored, cancel);
        assertThat(cancelResponse.status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(com.surprising.aeron.protocol.CoreCommandResultCodec.decode(cancelResponse.data()).orders())
                .extracting(value -> value.status()).containsExactly("CANCELED");
        assertThat(restored.tradingState().user(1001).totalUnits("USDT")).isEqualTo(10_000);
        assertThat(CoreStateQueryCodec.decodeOpenOrders(restored.apply(openOrdersQuery).data()).orders()).isEmpty();
        assertThat(com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeList(restored.apply(triggerQuery).data()))
                .extracting(value -> value.triggerOrderId()).containsExactly(501L);
    }

    @Test
    void asyncMatchingCompletesOnOwnerContinuationWithoutBlockingApply() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        CoreMessage adjustment = tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));
        assertThat(state.apply(adjustment).status()).isEqualTo(ResponseStatus.APPLIED);
        UUID commandId = UUID.randomUUID();
        CoreMessage place = tradingCommand(CoreMessageType.PLACE_ORDER, commandId, 2,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(711, "BTC-USDT", 1, CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "async-711")));

        CoreResponse pending = state.apply(place);

        assertThat(pending.status()).isEqualTo(ResponseStatus.OK);
        assertThat(pending.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
        long sequence = state.matchingSequence(commandId);
        UUID secondCommandId = UUID.randomUUID();
        CoreMessage secondPlace = tradingCommand(CoreMessageType.PLACE_ORDER, secondCommandId, 3,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(712, "BTC-USDT", 1, CoreOrderSide.BUY, 900, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "async-712")));
        assertThat(state.apply(secondPlace).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
        long secondSequence = state.matchingSequence(secondCommandId);
        assertThat(secondSequence).isGreaterThan(sequence);
        assertThat(state.tradingState().order(711)).isNull();
        assertThat(state.tradingState().order(712)).isNull();
        assertThat(state.exportState().pending().stream()
                .map(event -> CoreExportCodec.decodeEvent(event.payloadUnsafe()))
                .filter(event -> event.commandId().equals(commandId) || event.commandId().equals(secondCommandId))
                .toList()).isEmpty();
        com.surprising.aeron.service.matching.CoreMatchingResult matching = null;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (matching == null && System.nanoTime() < deadline) {
            matching = state.takeMatchingResult(sequence);
            if (matching == null) Thread.onSpinWait();
        }
        assertThat(matching).isNotNull();
        CoreResponse completed = state.completeMatching(sequence, matching, 2_000, 3);
        com.surprising.aeron.service.matching.CoreMatchingResult secondMatching = null;
        deadline = System.nanoTime() + 5_000_000_000L;
        while (secondMatching == null && System.nanoTime() < deadline) {
            secondMatching = state.takeMatchingResult(secondSequence);
            if (secondMatching == null) Thread.onSpinWait();
        }
        assertThat(secondMatching).isNotNull();
        state.completeMatching(secondSequence, secondMatching, 2_001, 4);

        assertThat(completed.resultCode()).isEqualTo(CoreResultCode.NONE);
        assertThat(state.pendingMatching()).isEmpty();
        assertThat(state.tradingState().order(711).status()).isEqualTo(
                com.surprising.aeron.service.state.CoreOrderStatus.OPEN);
        assertThat(state.tradingState().order(712).status()).isEqualTo(
                com.surprising.aeron.service.state.CoreOrderStatus.OPEN);
        var firstOrderFacts = state.exportState().pending().stream()
                .map(event -> CoreExportCodec.decodeEvent(event.payloadUnsafe()))
                .filter(event -> event.commandId().equals(commandId))
                .toList();
        assertThat(firstOrderFacts).hasSize(1);
        assertThat(firstOrderFacts.getFirst().committedCoreSequence())
                .isEqualTo(firstOrderFacts.getFirst().appliedCommandCount());
        state.close();
    }

    @Test
    void exportAckDoesNotWaitForAnUnrelatedPendingMatchingWindow() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            assertThat(state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            long throughSequence = state.exportState().nextSequence() - 1;
            CoreMessage place = tradingCommand(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 2,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(713, "BTC-USDT", 1,
                            CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "ack-window-713")));
            assertThat(state.apply(place).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            long matchingSequence = state.matchingSequence(place.header().commandId());
            long committedBeforeAck = state.committedCoreSequence();
            CoreMessage ack = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ACK_EXPORT,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.RECOVERY_TOOL, 91, 1,
                    0, 2_000, 91), CoreExportCodec.encodeAck(new AckExportCommand(throughSequence)));

            CoreResponse ackResponse = state.apply(ack, 2_000, 91);

            assertThat(ackResponse.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.exportState().acknowledgedSequence()).isEqualTo(throughSequence);
            assertThat(state.firstPendingMatchingSequence()).isEqualTo(matchingSequence);
            assertThat(state.committedCoreSequence()).isEqualTo(committedBeforeAck);
            completeMatching(state, matchingSequence, place);
            assertThat(state.committedCoreSequence()).isEqualTo(state.appliedCommandCount());
        }
    }

    @Test
    void compatibilitySnapshotWaitsForPendingMatchingCompletion() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))));
            CoreMessage place = tradingCommand(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 2,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(713, "BTC-USDT", 1, CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "snapshot-attempt-713")));

            state.apply(place);
            assertThat(state.snapshot()).isNotEmpty();
            assertThat(state.pendingMatching()).isEmpty();
        }
    }

    @Test
    void snapshotFenceFinalizesSerializedCompletionsInGlobalSequenceOrder() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            assertThat(state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            long fixtureStartingCount = state.appliedCommandCount();
            assertThat(fixtureStartingCount).isEqualTo(2);
            UUID firstCommandId = UUID.randomUUID();
            CoreMessage firstPlace = tradingCommand(CoreMessageType.PLACE_ORDER, firstCommandId, 2,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(715, "BTC-USDT", 1, CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "snapshot-fence-715")));
            UUID secondCommandId = UUID.randomUUID();
            CoreMessage secondPlace = tradingCommand(CoreMessageType.PLACE_ORDER, secondCommandId, 3,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(716, "BTC-USDT", 1, CoreOrderSide.BUY, 900, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "snapshot-fence-716")));
            assertThat(state.apply(firstPlace).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            assertThat(state.apply(secondPlace).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);

            state.beginSnapshot(715, Long.MAX_VALUE);
            assertThatThrownBy(() -> state.apply(command(UUID.randomUUID(), 3, 1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("snapshot fence is active");
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!state.pendingMatching().isEmpty() && System.nanoTime() < deadline) {
                try {
                    state.captureSnapshot(2_000, 3, System.nanoTime());
                } catch (CoreProbeState.SnapshotNotReadyException expected) {
                    Thread.onSpinWait();
                }
                if (!state.pendingMatching().isEmpty()) state.beginSnapshot(715, Long.MAX_VALUE);
            }
            assertThat(state.pendingMatching()).isEmpty();
            assertThat(state.commandResults().get(firstCommandId).appliedCommandCount())
                    .isEqualTo(fixtureStartingCount + 1);
            assertThat(state.commandResults().get(secondCommandId).appliedCommandCount())
                    .isEqualTo(fixtureStartingCount + 2);
            assertThat(state.appliedCommandCount()).isEqualTo(fixtureStartingCount + 2);
            assertThat(state.tradingState().order(715).status())
                    .isEqualTo(com.surprising.aeron.service.state.CoreOrderStatus.OPEN);
            assertThat(state.tradingState().order(716).status())
                    .isEqualTo(com.surprising.aeron.service.state.CoreOrderStatus.OPEN);
        }
    }

    @Test
    void notReadyReleasesAdmissionButRetainsInFlightMatcherGuardUntilCompletion() {
        // Given
        CompletableFuture<Void> nestedMatcherStage = new CompletableFuture<>();
        CompletableFuture<com.surprising.aeron.service.matching.MatcherSnapshot> matcherSnapshot =
                nestedMatcherStage.thenApply(ignored -> null);
        AtomicInteger captureCount = new AtomicInteger();
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT,
                (snapshotId, coreSequence, businessStateHash, tradingState, activeOrders) ->
                        captureCount.incrementAndGet() == 1
                        ? matcherSnapshot
                        : CompletableFuture.failedFuture(new IllegalStateException("fresh capture fixture failure")))) {
            state.beginSnapshot(801, Long.MAX_VALUE);

            // When
            assertThatThrownBy(() -> state.captureSnapshot(1_000, 1, System.nanoTime()))
                    .isInstanceOf(CoreProbeState.SnapshotNotReadyException.class)
                    .hasMessage("snapshot not ready");

            // Then
            assertThat(matcherSnapshot).isNotCancelled().isNotDone();
            assertThat(nestedMatcherStage).isNotCancelled().isNotDone();
            assertThat(state.apply(command(UUID.randomUUID(), 1, 7)).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThatThrownBy(() -> state.beginSnapshot(802, Long.MAX_VALUE))
                    .isInstanceOf(CoreProbeState.SnapshotNotReadyException.class)
                    .hasMessage("snapshot not ready");
            assertThat(captureCount).hasValue(1);
            assertThat(matcherSnapshot).isNotCancelled().isNotDone();

            assertThat(nestedMatcherStage.completeExceptionally(new IllegalStateException("test completion")))
                    .isTrue();
            assertThat(matcherSnapshot).isCompletedExceptionally();
            state.beginSnapshot(802, Long.MAX_VALUE);
            assertThatThrownBy(() -> state.captureSnapshot(1_001, 2, System.nanoTime()))
                    .isInstanceOf(java.util.concurrent.CompletionException.class)
                    .hasRootCauseMessage("fresh capture fixture failure");
            assertThat(captureCount).hasValue(2);
        }
    }

    @Test
    void frozenSnapshotEncodingDoesNotBlockTheTradingOwnerStream() {
        AtomicReference<CoreSnapshotImage> frozenImage = new AtomicReference<>();
        CompletableFuture<SectionedCoreSnapshotCodec.SectionedSnapshot> encoded = new CompletableFuture<>();
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT, null, image -> {
            frozenImage.set(image);
            return encoded;
        })) {
            assertThat(state.apply(command(UUID.randomUUID(), 1, 3)).status()).isEqualTo(ResponseStatus.APPLIED);
            long frozenSequence = state.appliedCommandCount();
            state.beginSnapshot(811, Long.MAX_VALUE);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (frozenImage.get() == null && System.nanoTime() < deadline) {
                assertThat(state.pollSnapshotSections(1_000, 1, System.nanoTime())).isNull();
                Thread.onSpinWait();
            }
            assertThat(frozenImage.get()).isNotNull();

            assertThat(state.apply(command(UUID.randomUUID(), 2, 5)).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.appliedCommandCount()).isEqualTo(frozenSequence + 1);
            encoded.complete(SectionedCoreSnapshotCodec.encode(frozenImage.get()));
            byte[] snapshot = state.pollSnapshot(1_000, 1, System.nanoTime());
            assertThat(snapshot).isNotNull();

            try (CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, snapshot)) {
                assertThat(restored.appliedCommandCount()).isEqualTo(frozenSequence);
                assertThat(restored.probeValue()).isEqualTo(3);
            }
            assertThat(state.probeValue()).isEqualTo(8);
        }
    }

    @Test
    void interruptedSnapshotFencePublishesNothingAndAllowsExplicitRetry() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            state.beginSnapshot(901, Long.MAX_VALUE);
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> state.pollSnapshot(1_000, 1, System.nanoTime()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("snapshot fence interrupted");
            } finally {
                Thread.interrupted();
            }

            assertThat(state.snapshot(902)).isNotEmpty();
        }
    }

    @Test
    void snapshotFenceFailsClosedWhenCompletionQueueOverflows() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            var result = new com.surprising.aeron.service.matching.CoreMatchingResult(
                    true, "SUCCESS");
            for (int index = 0; index <= CoreProbeState.MAX_PENDING_MATCHING; index++) {
                state.publishMatchingCompletion(1, result);
            }
            state.beginSnapshot(903, Long.MAX_VALUE);

            Throwable fatal = catchThrowable(() -> state.pollSnapshot(1_000, 1, System.nanoTime()));

            assertThat(fatal).isInstanceOf(
                    com.surprising.aeron.service.matching.FatalMatchingDivergenceException.class)
                    .hasMessageContaining("matching completion queue is full");
            assertThatThrownBy(state::snapshot).isSameAs(fatal);
        }
    }

    @Test
    void failsClosedWithoutRetryOnMatcherDivergence() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            assertThat(state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            UUID commandId = UUID.randomUUID();
            CoreMessage place = tradingCommand(CoreMessageType.PLACE_ORDER, commandId, 2,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(714, "BTC-USDT", 1, CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "fatal-714")));
            assertThat(state.apply(place).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            long sequence = state.matchingSequence(commandId);
            var matcherFailure = new com.surprising.aeron.service.matching.CoreMatchingResult(
                    false, "EXCHANGE_CORE_FAILURE");

            Throwable fatal = catchThrowable(() -> state.completeMatching(sequence, matcherFailure, 2_000, 3));

            assertThat(fatal).isInstanceOf(
                    com.surprising.aeron.service.matching.FatalMatchingDivergenceException.class);
            assertThatThrownBy(() -> state.apply(command(UUID.randomUUID(), 3, 1)))
                    .isSameAs(fatal);
            assertThatThrownBy(state::snapshot).isSameAs(fatal);
            assertThat(state.pendingMatching()).containsOnlyKeys(sequence);
            assertThat(state.tradingState().order(714)).isNull();
        }
    }

    @Test
    void failsClosedWhenMatcherPrefixDoesNotContinueAppliedPrefix() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            assertThat(state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            UUID commandId = UUID.randomUUID();
            CoreMessage place = tradingCommand(CoreMessageType.PLACE_ORDER, commandId, 2,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(715, "BTC-USDT", 1, CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "prefix-715")));
            assertThat(state.apply(place).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            long sequence = state.matchingSequence(commandId);
            var result = state.takeMatchingResult(sequence);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (result == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
                result = state.takeMatchingResult(sequence);
            }
            assertThat(result).isNotNull();
            long tamperedBefore = result.matcherPrefix().before() ^ 1L;
            if (tamperedBefore == 0 || tamperedBefore == result.matcherPrefix().after()) {
                tamperedBefore ^= 2L;
            }
            var tampered = new com.surprising.aeron.service.matching.CoreMatchingResult(
                    result.accepted(), result.resultCode(), result.cancellations(),
                    result.successfulPrefixCount(), result.matcherStateChanged(), result.nativeCommand(),
                    new com.surprising.aeron.service.matching.CoreMatchingResult.MatcherPrefix(
                            tamperedBefore, result.matcherPrefix().after()),
                    result.nativeMatcherResult(), result.matcherEvents(), result.marketData());

            Throwable fatal = catchThrowable(() -> state.completeMatching(sequence, tampered, 2_000, 3));

            assertThat(fatal).isInstanceOf(
                    com.surprising.aeron.service.matching.FatalMatchingDivergenceException.class)
                    .hasMessageContaining("matcher result prefix does not continue the applied prefix");
            assertThatThrownBy(() -> state.apply(command(UUID.randomUUID(), 3, 1)))
                    .isSameAs(fatal);
            assertThatThrownBy(state::snapshot).isSameAs(fatal);
        }
    }

    @Test
    void asyncTriggerMatchingUsesContinuationAfterTriggerClaim() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        applySpotMark(state, 70_000);
        state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 2))));
        var trigger = new com.surprising.aeron.protocol.CoreTriggerOrderStateView(712,
                ProductLine.SPOT, 1001, "tp-712", "", "BTC-USDT", CoreOrderSide.SELL,
                com.surprising.aeron.protocol.CoreTriggerOrderType.TAKE_PROFIT,
                com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL, 70_000,
                0, 0, 0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                "", "trace", 0, 0, 1_000, 1_000, 1);
        state.apply(tradingCommand(CoreMessageType.PLACE_TRIGGER_ORDER, UUID.randomUUID(), 2,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(trigger)));
        UUID executeId = UUID.randomUUID();
        CoreMessage execute = tradingCommand(CoreMessageType.EXECUTE_TRIGGER_ORDER, executeId, 3,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeExecute(712, 7, 70_000, 2_000));

        CoreResponse response = state.apply(execute);

        assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.pendingMatching()).hasSize(1);
        long sequence = state.pendingMatching().keySet().iterator().next();
        com.surprising.aeron.service.matching.CoreMatchingResult matching = null;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (matching == null && System.nanoTime() < deadline) {
            matching = state.takeMatchingResult(sequence);
            if (matching == null) Thread.onSpinWait();
        }
        assertThat(matching).isNotNull();
        state.completeMatching(sequence, matching, 2_001, 4);
        assertThat(state.pendingMatching()).isEmpty();
        assertThat(state.tradingState().triggerOrders().get(712L).status())
                .isEqualTo(com.surprising.aeron.protocol.CoreTriggerOrderStatus.TRIGGERED);
        state.close();
    }

    @Test
    void orderPreflightUsesAuthoritativeRulesWithoutMutatingState() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 2,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))));
        long hash = state.tradingState().businessStateHash();
        PlaceOrderCommand command = new PlaceOrderCommand(99, "BTC-USDT", 1, CoreOrderSide.BUY, 1_000, 2, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "");
        CoreMessage query = query(CoreMessageType.ORDER_PREFLIGHT_QUERY, 1001,
                TradingCommandCodec.encodePlaceOrder(command));

        var response = state.apply(query);

        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        assertThat(com.surprising.aeron.protocol.CoreOrderPreflightCodec.decode(response.data()))
                .isEqualTo(new com.surprising.aeron.protocol.CoreOrderPreflightView("USDT", 2_002));
        assertThat(state.tradingState().businessStateHash()).isEqualTo(hash);
        assertThat(state.tradingState().orders()).isEmpty();
    }

    @Test
    void executesTriggerInsideCoreAndDoesNotCreateASecondLifecycleRoundTrip() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        applySpotMark(state, 70_000);
        state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 2))));
        var trigger = new com.surprising.aeron.protocol.CoreTriggerOrderStateView(503,
                ProductLine.SPOT, 1001, "tp-503", "", "BTC-USDT", CoreOrderSide.SELL,
                com.surprising.aeron.protocol.CoreTriggerOrderType.TAKE_PROFIT,
                com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL, 70_000,
                0, 0, 0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                "", "trace", 0, 0, 1_000, 1_000, 1);
        state.apply(tradingCommand(CoreMessageType.PLACE_TRIGGER_ORDER, UUID.randomUUID(), 2,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(trigger)));

        CoreMessage execute = tradingCommand(CoreMessageType.EXECUTE_TRIGGER_ORDER, UUID.randomUUID(), 3,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeExecute(503, 7, 70_000, 2_000));
        var response = applyAndDrain(state, execute);

        assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.tradingState().triggerOrders().get(503L).status())
                .isEqualTo(com.surprising.aeron.protocol.CoreTriggerOrderStatus.TRIGGERED);
        long childOrderId = state.tradingState().triggerOrders().get(503L).placedOrderId();
        assertThat(childOrderId).isPositive();
        assertThat(state.tradingState().order(childOrderId)).isNotNull();
        assertThat(state.apply(execute).status()).isEqualTo(ResponseStatus.DUPLICATE);
    }

    @Test
    void markPriceCommandSchedulesCrossingTriggerCandidatesForContinuation() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 2))));
        var trigger = new com.surprising.aeron.protocol.CoreTriggerOrderStateView(504,
                ProductLine.SPOT, 1001, "tp-504", "oco-504", "BTC-USDT", CoreOrderSide.SELL,
                com.surprising.aeron.protocol.CoreTriggerOrderType.TAKE_PROFIT,
                com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL, 70_000,
                0, 0, 0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                "", "trace", 0, 0, 1_000, 1_000, 1);
        state.apply(tradingCommand(CoreMessageType.PLACE_TRIGGER_ORDER, UUID.randomUUID(), 2,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(trigger)));

        var mark = new ApplyMarkPriceCommand("BTC-USDT", 1, 70_000, 7, 1_000);
        var response = applyAndDrain(state, tradingCommand(CoreMessageType.APPLY_MARK_PRICE, UUID.randomUUID(), 3,
                TradingCommandCodec.encodeApplyMarkPrice(mark)));

        assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.tradingState().triggerOrders().get(504L).status())
                .isEqualTo(com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING);
        CoreMessage continuation = tradingCommand(CoreMessageType.CONTINUE_RISK_SCAN, UUID.randomUUID(), 4,
                TradingCommandCodec.encodeContinueRiskScan(
                        new com.surprising.aeron.protocol.ContinueRiskScanCommand(64)));
        assertThat(applyAndDrain(state, continuation).status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.tradingState().triggerOrders().get(504L).status())
                .isEqualTo(com.surprising.aeron.protocol.CoreTriggerOrderStatus.TRIGGERED);
        assertThat(state.tradingState().triggerOrders().get(504L).triggerSequence()).isEqualTo(7);
    }

    @Test
    void markPriceOcoCancellationResumesInDescendingOrderWithoutSkippingSiblings() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 10))));
        for (long id = 1; id <= 4; id++) {
            var trigger = new com.surprising.aeron.protocol.CoreTriggerOrderStateView(id,
                    ProductLine.SPOT, 1001, "oco-" + id, "shared-oco", "BTC-USDT", CoreOrderSide.SELL,
                    com.surprising.aeron.protocol.CoreTriggerOrderType.TAKE_PROFIT,
                    com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL, 70_000,
                    0, 0, 0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1,
                    CoreMarginMode.CROSS, CorePositionSide.NET,
                    com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                    "", "oco-trace-" + id, 0, 0, 1_000, 1_000, 1);
            assertThat(state.apply(tradingCommand(CoreMessageType.PLACE_TRIGGER_ORDER, UUID.randomUUID(), id + 1,
                    com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(trigger))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
        }

        CoreMessage mark = tradingCommand(CoreMessageType.APPLY_MARK_PRICE, UUID.randomUUID(), 10,
                TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand("BTC-USDT", 1, 70_000, 7, 1_000)));
        assertThat(applyAndDrain(state, mark).status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.tradingState().triggerOrders().get(1L).status())
                .isEqualTo(com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING);
        assertThat(state.tradingState().triggerOrders().get(2L).status())
                .isEqualTo(com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING);
        assertThat(state.tradingState().triggerOrders().get(3L).status())
                .isEqualTo(com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING);
        assertThat(state.tradingState().triggerOrders().get(4L).status())
                .isEqualTo(com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING);

        long sourceSequence = 11;
        while (!state.tradingState().riskState().scan().complete()) {
            CoreMessage continuation = tradingCommand(CoreMessageType.CONTINUE_RISK_SCAN, UUID.randomUUID(),
                    sourceSequence++, TradingCommandCodec.encodeContinueRiskScan(
                            new com.surprising.aeron.protocol.ContinueRiskScanCommand(64)));
            CoreResponse continuationResponse = applyAndDrain(state, continuation);
            assertThat(continuationResponse.status())
                    .withFailMessage("continuation seq=%s result=%s scan=%s", sourceSequence,
                            continuationResponse.resultCode(), state.tradingState().riskState().scan())
                    .isEqualTo(ResponseStatus.APPLIED);
        }
        assertThat(state.tradingState().triggerOrders().get(3L).status())
                .isEqualTo(com.surprising.aeron.protocol.CoreTriggerOrderStatus.CANCELED);
        assertThat(state.tradingState().triggerOrders().get(4L).status())
                .isEqualTo(com.surprising.aeron.protocol.CoreTriggerOrderStatus.TRIGGERED);
    }

    @Test
    void markPriceTriggerWorkContinuesFromPersistedCursor() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 400))));
        for (long id = 1; id <= 260; id++) {
            var trigger = new com.surprising.aeron.protocol.CoreTriggerOrderStateView(id,
                    ProductLine.SPOT, 1001, "tp-" + id, "", "BTC-USDT", CoreOrderSide.SELL,
                    com.surprising.aeron.protocol.CoreTriggerOrderType.TAKE_PROFIT,
                    com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL, 70_000,
                    0, 0, 0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1,
                    CoreMarginMode.CROSS, CorePositionSide.NET,
                    com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                    "", "trace-" + id, 0, 0, 1_000, 1_000, 1);
            assertThat(state.apply(tradingCommand(CoreMessageType.PLACE_TRIGGER_ORDER, UUID.randomUUID(), id + 1,
                    com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(trigger))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
        }
        long throughSequence = state.exportState().nextSequence() - 1;
        assertThat(state.apply(new CoreMessage(CoreMessageHeader.command(CoreMessageType.ACK_EXPORT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 9, 2, 0, 2_000, 399),
                CoreExportCodec.encodeAck(new AckExportCommand(throughSequence)))).status())
                .isEqualTo(ResponseStatus.APPLIED);
        var mark = new ApplyMarkPriceCommand("BTC-USDT", 1, 70_000, 7, 1_000);
        CoreResponse markResponse = applyAndDrain(state, tradingCommand(CoreMessageType.APPLY_MARK_PRICE,
                UUID.randomUUID(), 400, TradingCommandCodec.encodeApplyMarkPrice(mark)));
        assertThat(markResponse.status()).withFailMessage("mark status=%s result=%s", markResponse.status(), markResponse.resultCode())
                .isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.tradingState().triggerOrders().values().stream()
                .filter(value -> value.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.TRIGGERED)
                .count()).isZero();

        long sourceSequence = 401;
        while (!state.tradingState().riskState().scan().complete()) {
            var continuation = tradingCommand(CoreMessageType.CONTINUE_RISK_SCAN, UUID.randomUUID(), sourceSequence++,
                    TradingCommandCodec.encodeContinueRiskScan(
                            new com.surprising.aeron.protocol.ContinueRiskScanCommand(64)));
            assertThat(applyAndDrain(state, continuation).status()).isEqualTo(ResponseStatus.APPLIED);
        }
        assertThat(state.tradingState().triggerOrders().values().stream()
                .filter(value -> value.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.TRIGGERED)
                .count()).isEqualTo(260);
        assertThat(state.tradingState().riskState().scan().complete()).isTrue();
    }

    @Test
    void expiryQueryUsesIncrementalIndexAndRemovesExpiredTrigger() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 2))));
        var trigger = new com.surprising.aeron.protocol.CoreTriggerOrderStateView(505,
                ProductLine.SPOT, 1001, "tp-505", "", "BTC-USDT", CoreOrderSide.SELL,
                com.surprising.aeron.protocol.CoreTriggerOrderType.TAKE_PROFIT,
                com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL, 70_000,
                0, 0, 0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                "", "trace", 1_500, 0, 1_000, 1_000, 1);
        state.apply(tradingCommand(CoreMessageType.PLACE_TRIGGER_ORDER, UUID.randomUUID(), 2,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(trigger)));

        var expiryQuery = query(CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY, 0,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeQuery(
                        new com.surprising.aeron.protocol.CoreTriggerOrderQuery(0, "", 0, 10,
                                com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 2_000)));
        assertThat(com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeList(state.apply(expiryQuery).data()))
                .extracting(value -> value.triggerOrderId()).containsExactly(505L);

        state.apply(tradingCommand(CoreMessageType.EXPIRE_TRIGGER_ORDER, UUID.randomUUID(), 3,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeLifecycle(505, 2_000)));
        assertThat(com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeList(state.apply(expiryQuery).data()))
                .isEmpty();
    }

    @Test
    void openInterestQueryReturnsAuthoritativePositionTotals() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);

        var response = state.apply(query(CoreMessageType.OPEN_INTEREST_QUERY, 0, new byte[0]));

        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        assertThat(com.surprising.aeron.protocol.CoreOpenInterestCodec.decode(response.data())).isEmpty();
    }

    @Test
    void liquidationWorkQueryReturnsOnlyCurrentBoundedPlansAndScanReadiness() {
        var current = new com.surprising.aeron.service.state.CoreLiquidationState(1, 1001, "BTC-USDT",
                CoreMarginMode.CROSS, CorePositionSide.NET, 1, 7, 10, 10, 0, 0, 0, 0,
                com.surprising.aeron.service.state.CoreLiquidationState.Status.PLANNED);
        var stale = new com.surprising.aeron.service.state.CoreLiquidationState(2, 1002, "ETH-USDT",
                CoreMarginMode.ISOLATED, CorePositionSide.LONG, 1, 8, 5, 5, 0, 0, 0, 0,
                com.surprising.aeron.service.state.CoreLiquidationState.Status.PLANNED);
        var risk = new com.surprising.aeron.service.state.CoreRiskState(
                Map.of("BTC-USDT", new com.surprising.aeron.service.state.CoreMarkPriceState(
                                "BTC-USDT", 1, 81, 7, 1),
                        "ETH-USDT", new com.surprising.aeron.service.state.CoreMarkPriceState(
                                "ETH-USDT", 1, 120, 9, 1)),
                Map.of(), Map.of(1L, current, 2L, stale),
                Map.of("BTC-USDT", new com.surprising.aeron.service.state.CoreRiskState.RiskScan(
                        "BTC-USDT", 7, 7, 1001, false)), 3);
        var trading = new com.surprising.aeron.service.state.TradingCoreState(ProductLine.SPOT, 1,
                Map.of(), Map.of(), Map.of(), risk,
                com.surprising.aeron.service.state.CoreTreasuryState.empty());
        CoreProbeState state = CoreProbeState.restore(ProductLine.SPOT, 0, 0,
                Map.of(), Map.of(), trading, new CoreExportState());

        var response = state.apply(query(CoreMessageType.LIQUIDATION_WORK_QUERY, 0,
                com.surprising.aeron.protocol.CoreLiquidationWorkCodec.encodeQuery(ProductLine.SPOT,
                        com.surprising.aeron.protocol.CoreLiquidationWorkView.Purpose.EXECUTION,
                        0, 1, 1_048_576)));
        var work = com.surprising.aeron.protocol.CoreLiquidationWorkCodec.decodeWork(response.data());

        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        assertThat(work.riskScanPending()).isTrue();
        assertThat(work.actions()).singleElement().satisfies(action -> {
            assertThat(action.liquidationId()).isEqualTo(1);
            assertThat(action.markPriceTicks()).isEqualTo(81);
            assertThat(action.triggerPriceSequence()).isEqualTo(7);
        });
    }

    @Test
    void recordsRejectedTradingCommandWithoutChangingBusinessState() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        long businessHash = state.tradingState().businessStateHash();
        CoreMessage command = tradingCommand(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 1,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(1, "BTC-USDT", 1, CoreOrderSide.BUY, 600, 1, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "")));

        var rejected = state.apply(command);
        var duplicate = state.apply(command);

        assertThat(rejected.status()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
        assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(duplicate.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(duplicate.resultCode()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
        assertThat(state.appliedCommandCount()).isEqualTo(2);
        assertThat(state.tradingState().businessStateHash()).isEqualTo(businessHash);

        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot());
        var restoredDuplicate = restored.apply(command);
        assertThat(restoredDuplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(restoredDuplicate.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(restoredDuplicate.resultCode()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
    }

    @Test
    void exportBacklogIsOrderedAckedAndRestoredWithSnapshot() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        CoreMessage first = command(UUID.randomUUID(), 1, 7);
        CoreMessage second = command(UUID.randomUUID(), 2, 3);
        state.apply(first);
        state.apply(second);
        state.exportState().pending();

        var batchResult = state.apply(query(CoreMessageType.EXPORT_BATCH_QUERY, 0,
                CoreExportCodec.encodeBatchQuery(10)));
        var batch = CoreExportCodec.decodeBatchResponse(batchResult.data()).events();

        assertThat(batch).hasSize(2);
        assertThat(batch).extracting(message -> CoreExportCodec.decodeEvent(message.payload()).exportSequence())
                .containsExactly(1L, 2L);
        assertThat(CoreExportCodec.decodeEvent(batch.getFirst().payload()).commandId())
                .isEqualTo(first.header().commandId());

        CoreMessage ack = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ACK_EXPORT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 81, 1, 0, 2_000, 81),
                CoreExportCodec.encodeAck(new AckExportCommand(1)));
        assertThat(state.apply(ack).status()).isEqualTo(ResponseStatus.APPLIED);

        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot());
        var status = CoreExportCodec.decodeStatus(restored.apply(query(
                CoreMessageType.EXPORT_STATUS_QUERY, 0, new byte[0])).data());
        var remaining = CoreExportCodec.decodeBatchResponse(restored.apply(query(
                CoreMessageType.EXPORT_BATCH_QUERY, 0, CoreExportCodec.encodeBatchQuery(10))).data()).events();

        assertThat(status.acknowledgedSequence()).isEqualTo(1);
        assertThat(status.nextSequence()).isEqualTo(3);
        assertThat(status.pendingCount()).isEqualTo(1);
        assertThat(status.pendingBytes()).isPositive();
        assertThat(status.acceptingCommands()).isTrue();
        assertThat(remaining).hasSize(1);
        assertThat(CoreExportCodec.decodeEvent(remaining.getFirst().payload()).exportSequence()).isEqualTo(2);
    }

    @Test
    void rejectsFactProducingOrderBeforeFinancialMutationWhenReplicatedOutboxCapacityIsReserved() {
        // Given
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        assertThat(state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                .status()).isEqualTo(ResponseStatus.APPLIED);
        fillExportBacklogCapacity(state.exportState());

        var beforeUser = state.tradingState().user(1001);
        var beforeBalances = beforeUser.balances();
        var beforeReservations = beforeUser.reservations();
        var beforeOrders = state.tradingState().orders();
        long beforeBusinessStateHash = state.tradingState().businessStateHash();
        long beforeStateHash = state.stateHash();
        long beforeAppliedCommandCount = state.appliedCommandCount();
        var beforeSourceCursors = state.lastSourceSequences();
        long beforeSourceCursorDigest = state.sourceSequenceDigest();
        var beforeExportStatus = state.exportState().status();
        UUID rejectedCommandId = UUID.randomUUID();
        CoreMessage command = tradingCommand(CoreMessageType.PLACE_ORDER, rejectedCommandId, 2,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(9_001, "BTC-USDT", 1, CoreOrderSide.BUY, 600, 1, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "")));

        // When
        CoreResponse rejected = state.apply(command);

        // Then
        assertThat(rejected.status()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.EXPORT_BACKLOG_FULL);
        assertThat(rejected.appliedCommandCount()).isEqualTo(beforeAppliedCommandCount);
        assertThat(rejected.stateHash()).isEqualTo(beforeStateHash);
        assertThat(state.tradingState().user(1001).balances()).isEqualTo(beforeBalances);
        assertThat(state.tradingState().user(1001).reservations()).isEqualTo(beforeReservations);
        assertThat(state.tradingState().orders()).isEqualTo(beforeOrders).isEmpty();
        assertThat(state.tradingState().businessStateHash()).isEqualTo(beforeBusinessStateHash);
        assertThat(state.stateHash()).isEqualTo(beforeStateHash);
        assertThat(state.appliedCommandCount()).isEqualTo(beforeAppliedCommandCount);
        assertThat(state.lastSourceSequences()).isEqualTo(beforeSourceCursors);
        assertThat(state.sourceSequenceDigest()).isEqualTo(beforeSourceCursorDigest);
        assertThat(state.exportState().status()).isEqualTo(beforeExportStatus);
        assertThat(state.commandResults()).doesNotContainKey(rejectedCommandId);
    }

    @Test
    void snapshotChecksumRejectsCorruption() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        state.apply(command(UUID.randomUUID(), 1, 7));
        byte[] snapshot = state.snapshot();
        snapshot[20] ^= 1;

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> CoreProbeState.fromSnapshot(ProductLine.SPOT, snapshot))
                .isInstanceOf(com.surprising.aeron.protocol.ProtocolException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void rejectsUnsupportedSnapshotVersion() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        byte[] snapshot = state.snapshot();
        snapshot[4] = 2;
        snapshot[5] = 0;

        assertThatThrownBy(() -> CoreProbeState.fromSnapshot(ProductLine.SPOT, snapshot))
                .isInstanceOf(com.surprising.aeron.protocol.ProtocolException.class)
                .hasMessageContaining("legacy core snapshot is disabled");
    }

    @Test
    void snapshotManifestReportsAuthoritativeMetadata() {
        CoreProbeState state = new CoreProbeState(ProductLine.OPTION);
        state.apply(command(ProductLine.OPTION, UUID.randomUUID(), 1, 3));

        CoreSnapshotManifest manifest = CoreProbeState.inspectSnapshot(ProductLine.OPTION, state.snapshot());

        assertThat(manifest.productLine()).isEqualTo(ProductLine.OPTION);
        assertThat(manifest.schemaVersion()).isEqualTo(16);
        assertThat(manifest.appliedCommandCount()).isEqualTo(1);
        assertThat(manifest.businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
        assertThat(manifest.engineStateHash()).isNotZero();
        assertThat(manifest.coreShardId()).isEqualTo("default");
        assertThat(manifest.routeVersion()).isEqualTo(3);
        assertThat(manifest.forkGitSha()).isEqualTo(
                com.surprising.aeron.service.matching.MatcherSnapshot.FORK_GIT_SHA);
        assertThat(manifest.artifactSha256()).isEqualTo(
                com.surprising.aeron.service.matching.MatcherSnapshot.ARTIFACT_SHA256);
        assertThat(manifest.exportStatus().pendingCount()).isEqualTo(1);
        assertThat(manifest.checksum()).isPositive();
    }

    @Test
    void rollingBusinessHashMatchesAuthoritativeStateAfterMutationsAndRestore() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        for (int sequence = 1; sequence <= 8; sequence++) {
            assertThat(state.apply(command(UUID.randomUUID(), sequence, sequence)).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.apply(query(CoreMessageType.BUSINESS_STATE_HASH_QUERY, 0, new byte[0])).stateHash())
                    .isEqualTo(state.tradingState().businessStateHash());
        }

        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot());
        assertThat(restored.apply(query(CoreMessageType.BUSINESS_STATE_HASH_QUERY, 0, new byte[0])).stateHash())
                .isEqualTo(restored.tradingState().businessStateHash());
    }

    @Test
    void rejectsAckAheadWithExplicitResultAndAllowsRetry() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        state.apply(command(UUID.randomUUID(), 1, 7));
        UUID commandId = UUID.randomUUID();
        CoreMessage ackAhead = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ACK_EXPORT,
                commandId, ProductLine.SPOT, CommandSource.OPERATIONS, 81, 1, 0, 2_000, 81),
                CoreExportCodec.encodeAck(new AckExportCommand(2)));

        var rejected = state.apply(ackAhead);
        var duplicate = state.apply(ackAhead);

        assertThat(rejected.status()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.EXPORT_ACK_AHEAD);
        assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(duplicate.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(duplicate.resultCode()).isEqualTo(CoreResultCode.EXPORT_ACK_AHEAD);
    }

    @Test
    void exportAckCompactsTerminalStateWithIdentityTombstone() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        assertThat(state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 2,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                .status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(applyAndDrain(state, tradingCommand(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 3,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(901, "BTC-USDT", 1, CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "client-901")))).status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(applyAndDrain(state, tradingCommand(CoreMessageType.CANCEL_ORDER, UUID.randomUUID(), 4,
                TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(901)))).status())
                .isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.tradingState().user(1001).reservations()).containsKey(901L);

        long throughSequence = state.exportState().nextSequence() - 1;
        long businessHashBeforeAck = state.tradingState().businessStateHash();
        long revisionBeforeAck = state.tradingState().revision();
        CoreMessage ack = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ACK_EXPORT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 9, 2, 0, 1_000, 5),
                CoreExportCodec.encodeAck(new AckExportCommand(throughSequence)));

        assertThat(state.apply(ack).status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.tradingState().businessStateHash()).isEqualTo(businessHashBeforeAck);
        assertThat(state.tradingState().revision()).isEqualTo(revisionBeforeAck);
        assertThat(state.tradingState().user(1001).reservations()).doesNotContainKey(901L);
        assertThat(state.tradingState().order(901)).isNull();
        assertThat(state.tradingState().order(1001, "client-901")).isNull();
        assertThat(state.terminalRetentionCandidateCount()).isZero();
        assertThat(state.terminalRetentionTombstoneCount()).isEqualTo(1);

        CoreMessage reusedOrderId = tradingCommand(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 5,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(901, "BTC-USDT", 1, CoreOrderSide.BUY, 900, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "client-902")));
        assertThat(state.apply(reusedOrderId).resultCode()).isEqualTo(CoreResultCode.DUPLICATE_ORDER_ID);
        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot());
        assertThat(restored.tradingState().user(1001).reservations()).doesNotContainKey(901L);
        assertThat(restored.terminalRetentionTombstoneCount()).isEqualTo(1);
    }

    @Test
    void acknowledgedTerminalOrderCannotBeResurrectedByLateFactMaterialization() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            assertThat(state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 2,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(applyAndDrain(state, tradingCommand(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 3,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(903, "BTC-USDT", 1,
                            CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                            false, "client-903")))).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(applyAndDrain(state, tradingCommand(CoreMessageType.CANCEL_ORDER, UUID.randomUUID(), 4,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(903)))).status())
                    .isEqualTo(ResponseStatus.APPLIED);

            var terminalState = state.tradingState();
            var retention = new TerminalStateRetention();
            retention.observeAcknowledgedOrders(terminalState, 4, List.of(903L));
            var eligible = retention.eligible(terminalState, 4, TerminalStateRetention.MAX_PRUNE_PER_ACK);

            assertThat(eligible.orderIds()).containsExactly(903L);
            retention.complete(eligible, 4);
            retention.observe(terminalState, 3, List.of(903L), List.of(), List.of());
            assertThat(retention.candidateCount()).isZero();
            assertThat(retention.tombstoneCount()).isEqualTo(1);
            assertThat(retention.containsOrder(903, 1001, "client-903")).isTrue();
        }
    }

    @Test
    void cancelSettlementDoesNotSubmitCrossThreadLaneCommands() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            assertThat(state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 2,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(applyAndDrain(state, tradingCommand(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 3,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(902, "BTC-USDT", 1,
                            CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                            false, "client-902")))).status()).isEqualTo(ResponseStatus.APPLIED);
            long settlementsBefore = accountLaneSettlementOperations(state.laneMetrics());

            assertThat(applyAndDrain(state, tradingCommand(CoreMessageType.CANCEL_ORDER, UUID.randomUUID(), 4,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(902)))).status())
                    .isEqualTo(ResponseStatus.APPLIED);

            long matcherApplyOwnerMutationAndCommitOperations =
                    accountLaneSettlementOperations(state.laneMetrics()) - settlementsBefore;
            assertThat(matcherApplyOwnerMutationAndCommitOperations).isZero();
            assertThat(state.tradingState().order(902).status().name()).isEqualTo("CANCELED");
            assertThat(state.tradingState().user(1001).totalUnits("USDT")).isEqualTo(10_000);
        }
    }

    private static long accountLaneSettlementOperations(CoreLaneMetrics metrics) {
        long total = 0;
        long[] completed = metrics.accountLaneCompletedOperations();
        for (int laneId = 0; laneId < metrics.accountLaneCount(); laneId++) {
            total += completed[laneId * CoreLaneMetrics.OPERATION_TYPE_COUNT + 1];
        }
        return total;
    }

    private static CoreMessage command(UUID commandId, long sourceSequence, long delta) {
        return command(ProductLine.SPOT, commandId, sourceSequence, delta);
    }

    private static void fillExportBacklogCapacity(CoreExportState exportState) {
        byte[] payload = new byte[CoreExportCodec.MAX_COMMAND_PAYLOAD / 2];
        for (long sequence = 1; sequence <= 6; sequence++) {
            long factSequence = sequence;
            CoreMessage command = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 91, sequence,
                    0, 1_000, sequence), payload);
            var transition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
            exportState.append(new CoreExportState.Draft(command, ResponseStatus.APPLIED, CoreResultCode.NONE,
                    sequence, 0, 0, 0, 0, 1, 1, transition, sequence, 0, List.of(),
                    exportSequence -> new com.surprising.aeron.protocol.CoreExportEvent(
                            exportSequence, factSequence, 0, command.header().commandId(),
                            command.header().messageType(), ResponseStatus.APPLIED, CoreResultCode.NONE,
                            command.header().userId(), command.payloadUnsafe(), List.of(), List.of(), List.of(),
                            List.of(), List.of(), List.of(), List.of(), 0, 0, 0, transition.routeVersion(),
                            1, 1, factSequence, transition, factSequence, List.of())));
        }
        assertThat(exportState.hasCapacityFor()).isFalse();
    }

    private static CoreResponse applyAndDrain(CoreProbeState state, CoreMessage message) {
        int pendingBefore = state.pendingMatching().size();
        CoreResponse response = state.apply(message);
        if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
            return completeMatching(state, state.matchingSequence(message.header().commandId()), message);
        }
        while (state.pendingMatching().size() > pendingBefore) {
            long sequence = state.pendingMatching().keySet().stream().skip(pendingBefore).findFirst().orElseThrow();
            completeMatching(state, sequence, state.pendingMatching(sequence).command());
        }
        return response;
    }

    private static CoreResponse completeMatching(CoreProbeState state, long sequence, CoreMessage message) {
        com.surprising.aeron.service.matching.CoreMatchingResult result = null;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (result == null && System.nanoTime() < deadline) {
            result = state.takeMatchingResult(sequence);
            if (result == null) Thread.onSpinWait();
        }
        assertThat(result).as("matching result for " + message.header().messageType()).isNotNull();
        CoreResponse completed = state.completeMatching(sequence, result, message.header().submittedAtEpochMillis(),
                message.header().sourceSequence());
        assertThat(completed).isNotNull();
        return completed;
    }

    private static CoreResponse applyBookQuery(CoreProbeState state, CoreMessage message) {
        CoreResponse pending = state.apply(message);
        assertThat(pending.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
        long queryId = state.querySequence(message.header().commandId());
        CoreResponse result = null;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (result == null && System.nanoTime() < deadline) {
            result = state.takeQueryResult(queryId);
            if (result == null) Thread.onSpinWait();
        }
        return result;
    }

    private static CoreMessage command(
            ProductLine productLine,
            UUID commandId,
            long sourceSequence,
            long delta) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, commandId,
                productLine, CommandSource.GATEWAY, 7, sourceSequence, 1001, 1_000, sourceSequence),
                CoreProtocol.probePayload(delta));
    }

    private static CoreMessage tradingCommand(
            CoreMessageType messageType,
            UUID commandId,
            long sourceSequence,
            byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(messageType, commandId,
                ProductLine.SPOT, CommandSource.GATEWAY, 7, sourceSequence, 1001,
                1_000, sourceSequence), payload);
    }

    private static void applySpotInstrument(CoreProbeState state) {
        UpsertInstrumentCommand instrument = new UpsertInstrumentCommand("BTC-USDT", 1,
                ContractType.SPOT.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                100_000, 50_000, -10, 20, 0, -1, 0);
        CoreMessage command = new CoreMessage(CoreMessageHeader.command(CoreMessageType.UPSERT_INSTRUMENT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 9, 1, 1,
                1_000, 1), TradingCommandCodec.encodeUpsertInstrument(instrument));
        assertThat(state.apply(command).status()).isEqualTo(ResponseStatus.APPLIED);
    }

    private static void applySpotMark(CoreProbeState state, long markPriceTicks) {
        CoreMessage command = new CoreMessage(CoreMessageHeader.command(CoreMessageType.APPLY_MARK_PRICE,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.KAFKA_INPUT_BRIDGE, 10, 1, 1,
                1_000, 2), TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand("BTC-USDT", 1, markPriceTicks, 7, 1_000)));
        assertThat(state.apply(command).status()).isEqualTo(ResponseStatus.APPLIED);
    }

    private static CoreMessage query(CoreMessageType messageType, long userId, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.query(messageType, UUID.randomUUID(),
                ProductLine.SPOT, CommandSource.GATEWAY, 7, 0, userId, 1_000, 100), payload);
    }
}
