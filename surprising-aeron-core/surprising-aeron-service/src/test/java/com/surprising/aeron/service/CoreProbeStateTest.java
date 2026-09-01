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
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class CoreProbeStateTest {

    @Test
    void directCoreProbeAdmissionReleasesUnusedJournalSliceAfterFactPublication() throws Exception {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            var journal = commitJournal(state);
            long publishedBefore = journal.publishedSequence();
            long exportBefore = state.exportState().nextSequence();

            CoreResponse response = state.apply(command(UUID.randomUUID(), 1, 7));

            assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(journal.publishedSequence()).isEqualTo(publishedBefore);
            assertThat(state.exportState().nextSequence()).isEqualTo(exportBefore + 1);
            assertThat(journal.metrics().reservedEntries()).isZero();
            assertThat(journal.metrics().reservedBytes()).isZero();
            assertThat(state.exportState().metrics().reservedEvents()).isZero();
            assertThat(state.exportState().metrics().reservedBytes()).isZero();
        }
    }

    @Test
    void matchingCoreProbeAdmissionConsumesPrepareSliceAndReleasesTerminalRemainder() throws Exception {
        try (CoreProbeState state = fundedSpotState()) {
            var journal = commitJournal(state);
            CoreMessage place = placeOrder(UUID.randomUUID(), 3, 9_001, "matching-demand-9001");

            CoreResponse pending = state.apply(place);

            assertThat(pending.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            PendingMatching pendingMatching = state.pendingMatching(state.matchingSequence(place.header().commandId()));
            assertThat(pendingMatching).isNotNull();
            assertThat(journal.metrics().reservedEntries()).isEqualTo(1);
            assertThat(state.exportState().metrics().reservedEvents()).isEqualTo(1);
            assertThat(pendingMatching.capacityReservation().remainingPatches()).isEqualTo(1);
            assertThat(pendingMatching.capacityReservation().remainingFacts()).isEqualTo(1);

            assertThat(completeMatching(state, state.matchingSequence(place.header().commandId()), place).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            assertThat(journal.metrics().reservedEntries()).isZero();
            assertThat(journal.metrics().reservedBytes()).isZero();
            assertThat(state.exportState().metrics().reservedEvents()).isZero();
            assertThat(state.exportState().metrics().reservedBytes()).isZero();
        }
    }

    @Test
    void deferredCoreProbeAdmissionStaysReservedUntilDeferredMatchingCompletes() throws Exception {
        try (CoreProbeState state = fundedSpotState()) {
            CoreMessage batch = tradingCommand(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3,
                    com.surprising.aeron.protocol.TradingOrderBatchCodec.encodePlaceOrderBatch(
                            new com.surprising.aeron.protocol.PlaceOrderBatchCommand(List.of(
                                    new PlaceOrderCommand(9_101, "BTC-USDT", 1, CoreOrderSide.BUY, 600, 1,
                                            false, CoreMarginMode.CROSS, CorePositionSide.NET,
                                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, false,
                                            "batch-demand-9101")))));
            CoreMessage deferred = placeOrder(UUID.randomUUID(), 4, 9_102, "deferred-demand-9102");

            assertThat(state.apply(batch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            CoreResponse deferredResponse = state.apply(deferred);

            assertThat(deferredResponse.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            PendingMatching deferredPending = state.pendingMatching(
                    state.matchingSequence(deferred.header().commandId()));
            assertThat(deferredPending).isNotNull();
            assertThat(deferredPending.capacityReservation().remainingPatches()).isEqualTo(1);
            assertThat(deferredPending.capacityReservation().remainingFacts()).isEqualTo(1);
            assertThat(commitJournal(state).metrics().reservedEntries()).isPositive();
            assertThat(state.exportState().metrics().reservedEvents()).isPositive();

            while (!state.pendingMatching().isEmpty()) {
                long sequence = state.pendingMatching().keySet().iterator().next();
                completeMatching(state, sequence, state.pendingMatching(sequence).command());
            }
            assertThat(commitJournal(state).metrics().reservedEntries()).isZero();
            assertThat(state.exportState().metrics().reservedEvents()).isZero();
        }
    }

    @Test
    void queuedCoreProbeAdmissionRetainsSharedSlicesUntilTriggeredMatchingCompletes() throws Exception {
        try (CoreProbeState state = fundedSpotState()) {
            var trigger = new com.surprising.aeron.protocol.CoreTriggerOrderStateView(9_201,
                    ProductLine.SPOT, 1001, "queued-demand-9201", "", "BTC-USDT", CoreOrderSide.SELL,
                    com.surprising.aeron.protocol.CoreTriggerOrderType.TAKE_PROFIT,
                    com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL, 70_000,
                    0, 0, 0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1,
                    CoreMarginMode.CROSS, CorePositionSide.NET,
                    com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                    "", "queued-demand", 0, 0, 1_000, 1_000, 1);
            assertThat(state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 3,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 1))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.apply(tradingCommand(CoreMessageType.PLACE_TRIGGER_ORDER, UUID.randomUUID(), 4,
                    com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(trigger))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            CoreMessage mark = tradingCommand(CoreMessageType.APPLY_MARK_PRICE, UUID.randomUUID(), 5,
                    TradingCommandCodec.encodeApplyMarkPrice(
                            new ApplyMarkPriceCommand("BTC-USDT", 1, 70_000, 7, 1_000)));

            assertThat(state.apply(mark).status()).isEqualTo(ResponseStatus.APPLIED);
            CoreMessage continuation = tradingCommand(CoreMessageType.CONTINUE_RISK_SCAN, UUID.randomUUID(), 6,
                    TradingCommandCodec.encodeContinueRiskScan(
                            new com.surprising.aeron.protocol.ContinueRiskScanCommand(64)));

            assertThat(state.apply(continuation).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.pendingMatching()).isNotEmpty();
            PendingMatching queuedPending = state.pendingMatching().values().iterator().next();
            assertThat(queuedPending.capacityReservation().remainingPatches()).isEqualTo(2);
            assertThat(queuedPending.capacityReservation().remainingFacts()).isEqualTo(2);
            assertThat(commitJournal(state).metrics().reservedEntries()).isPositive();
            assertThat(state.exportState().metrics().reservedEvents()).isPositive();

            while (!state.pendingMatching().isEmpty()) {
                long sequence = state.pendingMatching().keySet().iterator().next();
                completeMatching(state, sequence, state.pendingMatching(sequence).command());
            }
            assertThat(commitJournal(state).metrics().reservedEntries()).isZero();
            assertThat(commitJournal(state).metrics().reservedBytes()).isZero();
            assertThat(state.exportState().metrics().reservedEvents()).isZero();
            assertThat(state.exportState().metrics().reservedBytes()).isZero();
        }
    }

    @Test
    void unusedCombinedAdmissionReleasesJournalAndExportBytes() {
        var initial = com.surprising.aeron.service.state.TradingCoreState.empty(ProductLine.SPOT);
        try (var journal = new com.surprising.aeron.service.state.RuntimeCommitJournal(
                ProductLine.SPOT, initial, initial.businessStateHash(),
                com.surprising.aeron.service.state.RollingFundsStateHash.compute(initial));
             var exportState = new CoreExportState()) {
            var reservation = CoreAdmissionReservation.reserve(journal, exportState,
                    CoreAdmissionReservation.AdmissionDemand.matching(tradingCommand(
                            CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 1,
                            TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(91, "BTC-USDT", 1,
                                    CoreOrderSide.BUY, 70_000, 1, false, CoreMarginMode.CROSS,
                                    CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                                    false, "budget")))));
            assertThat(journal.metrics().reservedEntries()).isEqualTo(1);
            assertThat(journal.metrics().reservedBytes()).isPositive();
            assertThat(exportState.metrics().reservedEvents()).isEqualTo(1);
            assertThat(exportState.metrics().reservedBytes()).isPositive();

            reservation.retainHolders(1);
            assertThat(reservation.holders()).isEqualTo(2);
            reservation.releaseUnused();
            assertThat(journal.metrics().reservedEntries()).isEqualTo(1);
            assertThat(exportState.metrics().reservedEvents()).isEqualTo(1);
            reservation.releaseUnused();

            assertThat(reservation.holders()).isZero();
            assertThat(reservation.remainingFactNodes()).isZero();
            assertThat(reservation.remainingFactItems()).isZero();
            assertThat(reservation.remainingFactBytes()).isZero();
            assertThat(journal.metrics().reservedEntries()).isZero();
            assertThat(journal.metrics().reservedBytes()).isZero();
            assertThat(exportState.metrics().reservedEvents()).isZero();
            assertThat(exportState.metrics().reservedBytes()).isZero();
        }
    }

    @Test
    void factBudgetRejectsOneMoreNodeItemAndByteWithoutDrift() {
        var nodes = new CoreAdmissionReservation.FactBudget(1, 4, 64);
        nodes.reservePatch();
        assertThatThrownBy(nodes::reservePatch).isInstanceOf(IllegalStateException.class);
        assertThat(nodes.remainingNodes()).isZero();
        assertThat(nodes.remainingItems()).isEqualTo(4);
        assertThat(nodes.remainingBytes()).isEqualTo(64);

        CoreMessage command = command(UUID.randomUUID(), 1, 1);
        var identities = new com.surprising.aeron.service.state.RuntimeIdentityRegistry();
        var patch = conservedFundsPatch(identities, command);
        var items = new CoreAdmissionReservation.FactBudget(1, patch.coreFactItemCount() - 1,
                patch.estimatedCoreFactBytes());
        var itemPermit = items.reservePatch();
        assertThatThrownBy(() -> itemPermit.consume(patch)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pre-mutation fact bound");
        assertThat(items.remainingItems()).isEqualTo(patch.coreFactItemCount() - 1);
        assertThat(items.remainingBytes()).isEqualTo(patch.estimatedCoreFactBytes());

        var bytes = new CoreAdmissionReservation.FactBudget(1, patch.coreFactItemCount(),
                patch.estimatedCoreFactBytes() - 1);
        var bytePermit = bytes.reservePatch();
        assertThatThrownBy(() -> bytePermit.consume(patch)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pre-mutation fact bound");
        assertThat(bytes.remainingItems()).isEqualTo(patch.coreFactItemCount());
        assertThat(bytes.remainingBytes()).isEqualTo(patch.estimatedCoreFactBytes() - 1);
    }

    @Test
    void factPermitRejectsReuseReorderForeignOwnerAndOrdinalGap() {
        var identities = new com.surprising.aeron.service.state.RuntimeIdentityRegistry();
        int symbolId = identities.symbolId("BTC-USDT");
        var open = new com.surprising.aeron.service.state.OrderRuntime(81, 17, symbolId, 2);
        var closed = new com.surprising.aeron.service.state.OrderRuntime(81, 17, symbolId, 2, true);
        var first = orderPatch(identities, null, open, 0, 1);
        var second = orderPatch(identities, open, closed, 1, 2);

        var orderedBudget = new CoreAdmissionReservation.FactBudget(2, 16, 32_768);
        var firstPermit = orderedBudget.reservePatch();
        var secondPermit = orderedBudget.reservePatch();
        assertThatThrownBy(() -> secondPermit.consume(second))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("gap or reorder");
        firstPermit.consume(first);
        assertThatThrownBy(() -> firstPermit.consume(first))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already resolved");
        secondPermit.consume(second);

        var foreign = factPermit(second);
        var firstChain = new CoreExportState.PatchChain(first, null, firstPermit);
        assertThatThrownBy(() -> new CoreExportState.PatchChain(second, firstChain, foreign))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("foreign");

        var gapBudget = new CoreAdmissionReservation.FactBudget(2, 16, 32_768);
        var returned = gapBudget.reservePatch();
        var gap = gapBudget.reservePatch();
        returned.returnUnused();
        assertThatThrownBy(() -> gap.consume(second))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("gap");

        var staleBudget = new CoreAdmissionReservation.FactBudget(1, 8, 16_384);
        var stale = staleBudget.reservePatch();
        staleBudget.release();
        assertThatThrownBy(() -> stale.consume(first))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("released");
    }

    @Test
    void ordinaryMatchingReservesItsBoundedDemandInsteadOfTheGlobalPatchMaximum() {
        CoreMessage command = tradingCommand(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 1,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(91, "BTC-USDT", 1,
                        CoreOrderSide.BUY, 70_000, 1, false, CoreMarginMode.CROSS,
                        CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                        false, "bounded-patch")));

        var demand = CoreAdmissionReservation.AdmissionDemand.matching(command);

        assertThat(demand.patchBytes()).isEqualTo(demand.factByteUpperBound());
        assertThat(demand.patchBytes())
                .isLessThan(com.surprising.aeron.service.state.RuntimeCommitJournal.maxReservedPatchBytes());
    }

    @Test
    void linearPerpetualFundingRiskLiquidationAndAdlReceiveExactThreePatchBudget() {
        List<CoreMessage> commands = List.of(
                tradingCommand(ProductLine.LINEAR_PERPETUAL, CoreMessageType.APPLY_FUNDING,
                        UUID.randomUUID(), 1, TradingCommandCodec.encodeApplyFunding(
                                new com.surprising.aeron.protocol.ApplyFundingCommand(
                                        1, "BTC-USDT", 1, 10, 0, 64))),
                tradingCommand(ProductLine.LINEAR_PERPETUAL, CoreMessageType.CONTINUE_RISK_SCAN,
                        UUID.randomUUID(), 2, TradingCommandCodec.encodeContinueRiskScan(
                                new com.surprising.aeron.protocol.ContinueRiskScanCommand(64))),
                tradingCommand(ProductLine.LINEAR_PERPETUAL, CoreMessageType.RESOLVE_LIQUIDATION,
                        UUID.randomUUID(), 3, TradingCommandCodec.encodeResolveLiquidation(
                                new com.surprising.aeron.protocol.ResolveLiquidationCommand(1,
                                        com.surprising.aeron.protocol.ResolveLiquidationCommand.Resolution.ADL, 0))),
                tradingCommand(ProductLine.LINEAR_PERPETUAL, CoreMessageType.EXECUTE_ADL,
                        UUID.randomUUID(), 4, TradingCommandCodec.encodeExecuteAdl(
                                new com.surprising.aeron.protocol.ExecuteAdlCommand(1, 1001, "BTC-USDT",
                                        CoreMarginMode.CROSS, CorePositionSide.NET, 10, 70_000,
                                        1, 1, 1))));
        for (CoreMessage command : commands) {
            var demand = CoreAdmissionReservation.AdmissionDemand.direct(command, 256);
            assertThat(demand.factChainNodes()).isEqualTo(3);
            var budget = new CoreAdmissionReservation.FactBudget(
                    demand.factChainNodes(), demand.factItems(), demand.factByteUpperBound());
            for (int node = 0; node < 3; node++) budget.reservePatch();
            assertThat(budget.remainingNodes()).isZero();
            budget.release();
            assertThat(budget.remainingItems()).isZero();
            assertThat(budget.remainingBytes()).isZero();
        }
    }

    @Test
    @Timeout(5)
    void exportAdmissionReservationIsConsumedExactlyOnceAndBatched() {
        try (CoreExportState exportState = new CoreExportState()) {
            var reservation = exportState.reserveAdmission(2);
            var transition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
            CoreMessage first = command(UUID.randomUUID(), 1, 1);
            CoreMessage second = command(UUID.randomUUID(), 2, 1);
            exportState.append(reservation, draft(first, 1, 1, 0, transition, List.of()));
            exportState.append(reservation, draft(second, 2, 2, 1, transition, List.of()));

            assertThat(reservation.remainingEvents()).isZero();
            assertThatThrownBy(() -> exportState.append(reservation,
                    draft(command(UUID.randomUUID(), 3, 1), 3, 3, 2, transition, List.of())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("consumed export admission");
            assertThat(exportState.pending()).hasSize(2);
            assertThat(exportState.metrics().reservedEvents()).isZero();
            assertThat(exportState.metrics().materializationBacklog()).isZero();
            assertThat(exportState.metrics().batchItems()).isEqualTo(2);
            assertThat(exportState.acknowledge(new AckExportCommand(2))).isEmpty();
            assertThat(exportState.metrics().currentBacklog()).isZero();
            assertThat(exportState.metrics().acknowledgedMaterializationItems()).isZero();
            assertThat(exportState.metrics().acknowledgedMaterializationBytes()).isZero();
        }
    }

    @Test
    void exportAdmissionReservationSharesAggregateBytesAcrossFactSlices() {
        try (CoreExportState exportState = new CoreExportState()) {
            var transition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
            CoreMessage heavyCommand = tradingCommand(CoreMessageType.PROBE_INCREMENT, UUID.randomUUID(), 1,
                    new byte[] {1});
            int lightFactBytes = CoreProtocol.HEADER_LENGTH + 4_097;
            int heavyFactBytes = lightFactBytes + 2_048;
            var reservation = exportState.reserveAdmission(2, lightFactBytes + heavyFactBytes);
            var heavyFact = draft(heavyCommand, 1, 1, 0, transition, List.of(1L));

            assertThat(exportState.append(reservation, heavyFact)).isEqualTo(1);
            assertThat(reservation.remainingEvents()).isEqualTo(1);
            assertThat(exportState.metrics().reservedEvents()).isEqualTo(1);
            assertThat(exportState.metrics().reservedBytes()).isEqualTo(lightFactBytes);

            exportState.release(reservation);
            assertThat(exportState.metrics().reservedEvents()).isZero();
            assertThat(exportState.metrics().reservedBytes()).isZero();
        }
    }

    @Test
    void exportByteRingRejectsBeforeSequenceOrPendingStateDrifts() {
        String property = "surprising.aeron.export-pending-bytes";
        String previous = System.getProperty(property);
        System.setProperty(property, Long.toString(CoreExportState.maxReservedEventBytes()));
        CoreExportState.AdmissionReservation reservation = null;
        CoreExportState exportState = null;
        try {
            exportState = new CoreExportState();
            CoreExportState activeExportState = exportState;
            reservation = exportState.reserveAdmission(1, CoreExportState.maxReservedEventBytes());
            CoreExportState.Metrics before = exportState.metrics();

            assertThatThrownBy(() -> activeExportState.reserveAdmission(1, CoreProtocol.HEADER_LENGTH))
                    .isInstanceOf(com.surprising.aeron.service.state.CoreStateRejectedException.class)
                    .hasMessageContaining("hard limit");

            assertThat(exportState.pendingCount()).isZero();
            assertThat(exportState.nextSequence()).isOne();
            assertThat(exportState.metrics().reservedBytes()).isEqualTo(before.reservedBytes());
            assertThat(exportState.metrics().reservedEvents()).isEqualTo(before.reservedEvents());
            assertThat(exportState.metrics().rejectionCount()).isEqualTo(before.rejectionCount() + 1);
            exportState.release(reservation);
            reservation = null;
        } finally {
            if (reservation != null && exportState != null) exportState.release(reservation);
            if (exportState != null) exportState.close();
            restoreProperty(property, previous);
        }
    }

    @Test
    void missingOrZeroFactMetadataIsRejectedBeforeExportStateDrifts() {
        try (CoreExportState exportState = new CoreExportState()) {
            CoreMessage command = command(UUID.randomUUID(), 1, 1);
            var transition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
            CoreExportState.Metrics before = exportState.metrics();

            assertThatThrownBy(() -> new CoreExportState.Draft(command, ResponseStatus.APPLIED,
                    CoreResultCode.NONE, 1, 1, 0, 0, 0, 1, 1, transition, 1, 1, 0, new long[0],
                    null, CoreCommandDelta.empty(),
                    com.surprising.aeron.service.state.RuntimeFundsDelta.empty(), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("invalid Core Fact draft");
            assertThatThrownBy(() -> new com.surprising.aeron.service.state.RuntimeCommitPatch.CoreFactMetadata(
                    command.header().commandId(), com.surprising.aeron.protocol.CommandFingerprint.fromBytes(
                    new byte[com.surprising.aeron.protocol.CommandFingerprint.LENGTH]),
                    command.header().messageType().wireCode(), command.header().userId(), ResponseStatus.APPLIED,
                    CoreResultCode.NONE, 1, 1, 1, 1, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Core Fact fingerprint must not be zero");

            assertThat(exportState.pendingCount()).isZero();
            assertThat(exportState.nextSequence()).isOne();
            assertThat(exportState.metrics()).isEqualTo(before);
        }
    }

    @Test
    void injectedOwnerCommitFailuresLeaveAllCommittedSurfacesBehindTheFence() throws Exception {
        for (String phase : List.of("preflight", "indexes", "business-hash", "funds-hash")) {
            try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
                long businessHash = ((com.surprising.aeron.service.state.RollingBusinessStateHash)
                        field(state, "rollingBusinessStateHash")).value();
                long fundsHash = ((com.surprising.aeron.service.state.RollingFundsStateHash)
                        field(state, "rollingFundsStateHash")).value();
                var journal = (com.surprising.aeron.service.state.RuntimeCommitJournal)
                        field(state, "runtimeProjectionJournal");
                var runtimeState = (com.surprising.aeron.service.state.TradingRuntimeState)
                        field(state, "runtimePlaceOrderState");
                var activeOrders = (com.surprising.aeron.service.state.ActiveOrderIndex)
                        field(state, "activeOrderIndex");
                var activeOrderPage = activeOrders.page(0, "BTC-USDT", Long.MAX_VALUE, 10);
                long committedLaneSequence = runtimeState.accountLane(7).committedSequence();
                CoreProbeState.setCommitFaultInjectorForTest(current -> {
                    if (phase.equals(current)) throw new IllegalStateException("injected " + phase + " failure");
                });

                CoreMessage adjustment = tradingCommand(CoreMessageType.ADJUST_BALANCE,
                        UUID.randomUUID(), 1,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 25)));
                assertThatThrownBy(() -> state.apply(adjustment))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("injected " + phase + " failure");

                assertThat(((com.surprising.aeron.service.state.RuntimeFundsDelta)
                        field(state, "commandFundsDelta")).postingCount()).isZero();
                assertThat(((com.surprising.aeron.service.state.RollingBusinessStateHash)
                        field(state, "rollingBusinessStateHash")).value()).isEqualTo(businessHash);
                assertThat(((com.surprising.aeron.service.state.RollingFundsStateHash)
                        field(state, "rollingFundsStateHash")).value()).isEqualTo(fundsHash);
                assertThat(journal.publishedSequence()).isZero();
                assertThat((long) field(state, "appliedCommandCount")).isZero();
                assertThat(activeOrders.page(0, "BTC-USDT", Long.MAX_VALUE, 10)).isEqualTo(activeOrderPage);
                assertThat(runtimeState.accountLane(7).committedSequence()).isEqualTo(committedLaneSequence);
                assertThat(runtimeState.hasChangedBalance(1001, 0)).isTrue();
                assertThat((boolean) field(field(runtimeState, "activePatchBuilder"), "sealed")).isFalse();
                assertThatThrownBy(() -> state.apply(query(CoreMessageType.BUSINESS_STATE_HASH_QUERY,
                        0, new byte[0]))).hasMessageContaining("owner commit publication failed");
            } finally {
                CoreProbeState.setCommitFaultInjectorForTest(null);
            }
        }
    }

    @Test
    void hashCommitFailuresPreserveOriginalAndRollbackCommittedSurfaces() throws Exception {
        for (boolean failBusiness : List.of(true, false)) {
            try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
                var business = (com.surprising.aeron.service.state.RollingBusinessStateHash)
                        field(state, "rollingBusinessStateHash");
                var funds = (com.surprising.aeron.service.state.RollingFundsStateHash)
                        field(state, "rollingFundsStateHash");
                long businessHash = business.value();
                long fundsHash = funds.value();
                var journal = (com.surprising.aeron.service.state.RuntimeCommitJournal)
                        field(state, "runtimeProjectionJournal");
                var runtimeState = (com.surprising.aeron.service.state.TradingRuntimeState)
                        field(state, "runtimePlaceOrderState");
                var activeOrders = (com.surprising.aeron.service.state.ActiveOrderIndex)
                        field(state, "activeOrderIndex");
                var activeOrderPage = activeOrders.page(0, "BTC-USDT", Long.MAX_VALUE, 10);
                long committedLaneSequence = runtimeState.accountLane(7).committedSequence();
                failHashCommit(failBusiness ? business : funds);

                CoreMessage adjustment = tradingCommand(CoreMessageType.ADJUST_BALANCE,
                        UUID.randomUUID(), 1,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 25)));
                Throwable failure = catchThrowable(() -> state.apply(adjustment));

                assertThat(failure).isInstanceOf(IllegalStateException.class)
                        .hasMessage("injected mid-stage " + (failBusiness ? "business" : "funds")
                                + " hash apply failure");
                assertThat(((com.surprising.aeron.service.state.RuntimeFundsDelta)
                        field(state, "commandFundsDelta")).postingCount()).isZero();
                assertThat(business.value()).isEqualTo(businessHash);
                assertThat(funds.value()).isEqualTo(fundsHash);
                assertThat(journal.publishedSequence()).isZero();
                assertThat((long) field(state, "appliedCommandCount")).isZero();
                assertThat(activeOrders.page(0, "BTC-USDT", Long.MAX_VALUE, 10)).isEqualTo(activeOrderPage);
                assertThat(runtimeState.accountLane(7).committedSequence()).isEqualTo(committedLaneSequence);
                assertThat(runtimeState.hasChangedBalance(1001, 0)).isTrue();
                assertThat((boolean) field(field(runtimeState, "activePatchBuilder"), "sealed")).isFalse();
            }
        }
    }

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
        try (CoreExportState exportState = new CoreExportState(event -> {
            enteredMaterializer.countDown();
            awaitMaterializer(releaseMaterializer);
            return CoreExportCodec.encodeEvent(event);
        })) {
            CoreMessage command = command(UUID.randomUUID(), 1, 1);
            var transition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
            exportState.append(draft(command, 1, 1, 0, transition, List.of()));

            assertThat(enteredMaterializer.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(exportState.pendingCount()).isEqualTo(1);
            assertThat(exportState.batch(1)).isEmpty();
            assertThat(exportState.acknowledge(new AckExportCommand(1))).isEmpty();

            releaseMaterializer.countDown();
            assertThat(exportState.pendingCount()).isZero();
        } finally {
            releaseMaterializer.countDown();
        }
    }

    @Test
    @Timeout(5)
    void closeDrainsAnInFlightFactWithoutInterruptingItsAssembler() throws Exception {
        CountDownLatch enteredMaterializer = new CountDownLatch(1);
        CountDownLatch releaseMaterializer = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> closeFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        CoreExportState exportState = new CoreExportState(event -> {
            enteredMaterializer.countDown();
            awaitMaterializer(releaseMaterializer);
            return CoreExportCodec.encodeEvent(event);
        });
        Thread closer = null;
        try {
            var transition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
            exportState.append(draft(command(UUID.randomUUID(), 1, 1), 1, 1, 0,
                    transition, List.of()));
            assertThat(enteredMaterializer.await(1, TimeUnit.SECONDS)).isTrue();

            closer = Thread.ofPlatform().start(() -> {
                closeStarted.countDown();
                try {
                    exportState.close();
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                }
            });
            assertThat(closeStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(closer.isAlive()).isTrue();
            releaseMaterializer.countDown();
            closer.join(TimeUnit.SECONDS.toMillis(2));

            assertThat(closer.isAlive()).isFalse();
            assertThat(closeFailure.get()).isNull();
            assertThat(exportState.metrics().materializationBacklog()).isZero();
            assertThat(exportState.metrics().acknowledgedMaterializationItems()).isZero();
            assertThat(exportState.metrics().acknowledgedMaterializationBytes()).isZero();
        } finally {
            releaseMaterializer.countDown();
            if (closer != null && closer.isAlive()) closer.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    @Timeout(5)
    void unexpectedMaterializerInterruptPoisonsAdmissionAndClose() throws Exception {
        CoreExportState exportState = new CoreExportState();
        ((Thread) field(exportState, "materializer")).interrupt();
        Throwable admissionFailure = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (admissionFailure == null && System.nanoTime() < deadline) {
            admissionFailure = catchThrowable(exportState::hasCapacityFor);
            if (admissionFailure == null) Thread.onSpinWait();
        }

        assertThat(admissionFailure).isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasRootCauseInstanceOf(InterruptedException.class);
        assertThat(exportState.pendingCount()).isZero();
        assertThat(exportState.nextSequence()).isOne();
        assertThat(exportState.metrics().errorCount()).isOne();
        assertThatThrownBy(exportState::close)
                .isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasRootCauseInstanceOf(InterruptedException.class);
    }

    @Test
    @Timeout(5)
    void acknowledgesTerminalIdsWithoutWaitingForFactMaterialization() throws Exception {
        CountDownLatch enteredMaterializer = new CountDownLatch(1);
        CountDownLatch releaseMaterializer = new CountDownLatch(1);
        var encodedEvent = new java.util.concurrent.atomic.AtomicReference<com.surprising.aeron.protocol.CoreExportEvent>();
        try (CoreExportState exportState = new CoreExportState(event -> {
            enteredMaterializer.countDown();
            awaitMaterializer(releaseMaterializer);
            encodedEvent.set(event);
            return CoreExportCodec.encodeEvent(event);
        })) {
            CoreMessage command = command(UUID.randomUUID(), 1, 1);
            var transition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
            var identities = new com.surprising.aeron.service.state.RuntimeIdentityRegistry();
            var patch = conservedFundsPatch(identities, command);
            var chain = new CoreExportState.PatchChain(patch, null, factPermit(patch));
            var metadata = new com.surprising.aeron.service.state.RuntimeCommitPatch.CoreFactMetadata(
                    command.header().commandId(), com.surprising.aeron.protocol.CommandFingerprint.of(command),
                    command.header().messageType().wireCode(), command.header().userId(), ResponseStatus.APPLIED,
                    CoreResultCode.NONE, 1, 1, 1, 1, false);
            exportState.append(new CoreExportState.Draft(command, ResponseStatus.APPLIED, CoreResultCode.NONE,
                    1, 1, 0, 0, 1, 1, 1, transition, 1, 1,
                    Math.addExact(1, chain.itemCount()), new long[]{42}, chain, CoreCommandDelta.empty(),
                    patch.fundsDelta(), metadata));

            assertThat(enteredMaterializer.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(exportState.acknowledge(new AckExportCommand(1))).containsExactly(42L);
            assertThat(exportState.pendingCount()).isZero();
            assertThat(exportState.metrics().acknowledgedMaterializationItems()).isOne();
            assertThat(exportState.metrics().acknowledgedMaterializationBytes()).isPositive();
            releaseMaterializer.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (exportState.metrics().acknowledgedMaterializationBytes() != 0
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(exportState.metrics().acknowledgedMaterializationItems()).isZero();
            assertThat(exportState.metrics().acknowledgedMaterializationBytes()).isZero();
            assertThat(encodedEvent.get().fundsPostings()).hasSize(2);
            assertThat(encodedEvent.get().fundsPostings()).extracting(item -> item.units())
                    .containsExactlyInAnyOrder(-10L, 10L);
            assertThat(exportState.metrics().reservedEvents()).isZero();
            assertThat(exportState.metrics().reservedBytes()).isZero();
        } finally {
            releaseMaterializer.countDown();
        }
    }

    @Test
    @Timeout(5)
    void materializesFundsWhenCommandDeltaOutlivesItsFactPatchIdentitySlice() {
        var encodedEvent = new java.util.concurrent.atomic.AtomicReference<
                com.surprising.aeron.protocol.CoreExportEvent>();
        try (CoreExportState exportState = new CoreExportState(event -> {
            encodedEvent.set(event);
            return CoreExportCodec.encodeEvent(event);
        })) {
            CoreMessage command = command(UUID.randomUUID(), 1, 1);
            var transition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
            var identities = new com.surprising.aeron.service.state.RuntimeIdentityRegistry();
            var patch = conservedFundsPatch(identities, command);
            var metadata = new com.surprising.aeron.service.state.RuntimeCommitPatch.CoreFactMetadata(
                    command.header().commandId(), com.surprising.aeron.protocol.CommandFingerprint.of(command),
                    command.header().messageType().wireCode(), command.header().userId(), ResponseStatus.APPLIED,
                    CoreResultCode.NONE, 1, 1, 1, 1, false);

            exportState.append(new CoreExportState.Draft(command, ResponseStatus.APPLIED, CoreResultCode.NONE,
                    1, 1, 0, 0, 1, 1, 1, transition, 1, 1,
                    patch.fundsPostings().size(), new long[0], null, CoreCommandDelta.empty(),
                    patch.fundsDelta(), identities, metadata));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (encodedEvent.get() == null && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(encodedEvent.get()).isNotNull();
            assertThat(encodedEvent.get().fundsPostings()).extracting(item -> item.asset())
                    .containsOnly("USDT");
            assertThat(encodedEvent.get().fundsPostings()).extracting(item -> item.units())
                    .containsExactlyInAnyOrder(-10L, 10L);
        }
    }

    @Test
    @Timeout(5)
    void restoredExportUsesEncodedTerminalIdsInsteadOfChangedOrders() {
        try (CoreExportState exportState = new CoreExportState()) {
            CoreMessage command = command(UUID.randomUUID(), 1, 1);
            var transition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
            exportState.append(draft(command, 1, 1, 0, transition, List.of(42L)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (exportState.encodedPendingCount() != 1 && System.nanoTime() < deadline) Thread.onSpinWait();
            List<CoreMessage> encoded = exportState.pending();

            try (CoreExportState restored = CoreExportState.restore(ProductLine.SPOT, 0, 2, encoded)) {
                restored.activate();
                assertThat(restored.pendingDigest()).isEqualTo(exportState.pendingDigest());
                assertThat(restored.acknowledge(new AckExportCommand(1))).containsExactly(42L);
            }
        }
    }

    @Test
    @Timeout(5)
    void acknowledgedFactMaterializationFailureRemainsFatal() throws Exception {
        CountDownLatch enteredMaterializer = new CountDownLatch(1);
        CountDownLatch releaseMaterializer = new CountDownLatch(1);
        CoreExportState exportState = new CoreExportState(event -> {
            enteredMaterializer.countDown();
            awaitMaterializer(releaseMaterializer);
            throw new IllegalStateException("encoded Core Fact failed");
        });
        try {
            CoreMessage command = command(UUID.randomUUID(), 1, 1);
            var transition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
            exportState.append(draft(command, 1, 1, 0, transition, List.of()));

            assertThat(enteredMaterializer.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(exportState.acknowledge(new AckExportCommand(1))).isEmpty();
            releaseMaterializer.countDown();

            Throwable failure = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (failure == null && System.nanoTime() < deadline) {
                failure = catchThrowable(exportState::hasCapacityFor);
                if (failure == null) Thread.onSpinWait();
            }
            assertThat(failure).isInstanceOf(java.util.concurrent.CompletionException.class)
                    .hasMessage("Core Fact materialization failed")
                    .hasRootCauseMessage("encoded Core Fact failed");
        } finally {
            releaseMaterializer.countDown();
            assertThatThrownBy(exportState::close)
                    .isInstanceOf(java.util.concurrent.CompletionException.class)
                    .hasRootCauseMessage("encoded Core Fact failed");
        }
    }

    @Test
    @Timeout(5)
    void multiPatchDeleteAfterCreateIsMergedByTheOffOwnerAssembler() {
        var transition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
        var identities = new com.surprising.aeron.service.state.RuntimeIdentityRegistry();
        int symbolId = identities.symbolId("BTC-USDT");
        var order = new com.surprising.aeron.service.state.OrderRuntime(71, 17, symbolId, 2);
        var created = orderPatch(identities, null, order, 0, 1);
        var deleted = orderPatch(identities, order, null, 1, 2);
        var permits = factPermits(List.of(created, deleted));
        var chain = new CoreExportState.PatchChain(deleted,
                new CoreExportState.PatchChain(created, null, permits.get(0)), permits.get(1));
        CoreMessage command = command(UUID.randomUUID(), 2, 1);
        try (CoreExportState exportState = new CoreExportState()) {
            exportState.append(draft(command, 2, 2, 0, transition, List.of(71L), chain, identities));
            var event = CoreExportCodec.decodeEvent(exportState.pending().getFirst().payloadUnsafe());
            assertThat(event.changedOrders()).isEmpty();
            assertThat(event.tombstones().orderIds()).containsExactly(71L);
        }
    }

    @Test
    void longFactPatchChainTraversesOldestFirstWithoutRecursion() {
        var identities = new com.surprising.aeron.service.state.RuntimeIdentityRegistry();
        int symbolId = identities.symbolId("BTC-USDT");
        var open = new com.surprising.aeron.service.state.OrderRuntime(72, 17, symbolId, 2);
        var canceled = new com.surprising.aeron.service.state.OrderRuntime(72, 17, symbolId, 2, true);
        CoreExportState.PatchChain chain = null;
        int nodes = 1_024;
        var patches = new java.util.ArrayList<com.surprising.aeron.service.state.RuntimeCommitPatch>(nodes);
        for (int sequence = 1; sequence <= nodes; sequence++) {
            var before = sequence == 1 ? null : sequence % 2 == 0 ? open : canceled;
            var after = sequence % 2 == 0 ? canceled : open;
            var patch = orderPatch(identities, before, after, sequence - 1L, sequence);
            patches.add(patch);
        }
        var permits = factPermits(patches);
        for (int index = 0; index < nodes; index++) {
            chain = new CoreExportState.PatchChain(patches.get(index), chain, permits.get(index));
        }
        java.util.ArrayList<Long> sequences = new java.util.ArrayList<>(nodes);
        chain.acceptOldestFirst(patch -> sequences.add(patch.coreSequence()));
        assertThat(sequences).hasSize(nodes);
        assertThat(sequences.getFirst()).isOne();
        assertThat(sequences.getLast()).isEqualTo(nodes);
    }

    @Test
    @Timeout(5)
    void exportAssemblerBuildsTheOrderViewOnItsOwnThread() {
        var captured = new java.util.concurrent.atomic.AtomicReference<com.surprising.aeron.protocol.CoreExportEvent>();
        var assemblerThread = new java.util.concurrent.atomic.AtomicReference<String>();
        var conversions = new java.util.concurrent.atomic.AtomicInteger();
        try (CoreExportState exportState = new CoreExportState(event -> {
            assemblerThread.set(Thread.currentThread().getName());
            captured.set(event);
            return CoreExportCodec.encodeEvent(event);
        }, order -> {
            conversions.incrementAndGet();
            return com.surprising.aeron.service.state.RuntimeCommitPatch.exportOrderView(order);
        })) {
            var transition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
            var identities = new com.surprising.aeron.service.state.RuntimeIdentityRegistry();
            int symbolId = identities.symbolId("BTC-USDT");
            var order = new com.surprising.aeron.service.state.OrderRuntime(73, 17, symbolId, 2);
            var terminal = new com.surprising.aeron.service.state.OrderRuntime(73, 17, symbolId, 2, true);
            var created = orderPatch(identities, null, order, 0, 1);
            var canceled = orderPatch(identities, order, terminal, 1, 2);
            var permits = factPermits(List.of(created, canceled));
            var chain = new CoreExportState.PatchChain(canceled,
                    new CoreExportState.PatchChain(created, null, permits.get(0)), permits.get(1));
            CoreMessage command = command(UUID.randomUUID(), 2, 1);
            exportState.append(draft(command, 2, 2, 0, transition, List.of(), chain, identities));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (captured.get() == null && System.nanoTime() < deadline) Thread.onSpinWait();

            assertThat(captured.get().changedOrders().getFirst().orderId()).isEqualTo(73);
            assertThat(assemblerThread.get()).isEqualTo("core-fact-materializer");
            assertThat(conversions).hasValue(1);
        }
    }

    @Test
    void sealedFactIdentitySliceKeepsV10BytesStableAfterRegistryMutationAndRollback() {
        var identities = new com.surprising.aeron.service.state.RuntimeIdentityRegistry();
        int symbolId = identities.symbolId("BTC-USDT");
        var order = new com.surprising.aeron.service.state.OrderRuntime(74, 17, symbolId, 2);
        var patch = orderPatch(identities, null, order, 0, 1);
        CoreMessage command = command(UUID.randomUUID(), 1, 1);
        var transition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
        byte[] before;
        try (CoreExportState exportState = new CoreExportState()) {
            exportState.append(draft(command, 1, 1, 0, transition, List.of(),
                    new CoreExportState.PatchChain(patch, null, factPermit(patch)), identities));
            before = exportState.pending().getFirst().payloadUnsafe().clone();
        }

        identities.symbolId("ETH-USDT");
        var prepared = identities.prepareClientKey(17, "rolled-back-client");
        identities.rollbackPreparedClientKey(17, "rolled-back-client", prepared);

        try (CoreExportState exportState = new CoreExportState()) {
            exportState.append(draft(command, 1, 1, 0, transition, List.of(),
                    new CoreExportState.PatchChain(patch, null, factPermit(patch)), identities));
            assertThat(exportState.pending().getFirst().payloadUnsafe()).isEqualTo(before);
        }
    }

    @Test
    void terminalOrderIsConvertedOnceAcrossProjectionIndexRetentionAndExport() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            assertThat(state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 2,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(applyAndDrain(state, tradingCommand(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 3,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(975, "BTC-USDT", 1,
                            CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                            false, "client-975")))).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(applyAndDrain(state, tradingCommand(CoreMessageType.CANCEL_ORDER, UUID.randomUUID(), 4,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(975)))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            var event = CoreExportCodec.decodeEvent(state.exportState().pending().getLast()
                    .payloadUnsafe());
            assertThat(event.terminalIds().orderIds()).contains(975L);
        }
    }

    @Test
    void exposesBoundedMatcherCompletionAndLaneContextMetrics() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            CoreLaneMetrics metrics = state.laneMetrics();

            assertThat(metrics.matchingEngineCount()).isEqualTo(1);
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
        AtomicReference<CoreProbeState> stateRef = new AtomicReference<>();
        AtomicReference<CoreResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch commandComplete = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        Thread coreAgent = new Thread(() -> {
            CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
            stateRef.set(state);
            try {
                response.set(state.apply(tradingCommand(
                        CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                        TradingCommandCodec.encodeBalanceAdjustment(
                                new BalanceAdjustmentCommand("USDT", 10_000)))));
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                commandComplete.countDown();
                awaitMaterializer(releaseClose);
                state.close();
            }
        });

        coreAgent.start();
        assertThat(commandComplete.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(failure.get()).isNull();
            CoreProbeState state = stateRef.get();
            assertThat(response.get().status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().user(1001).totalUnits("USDT")).isEqualTo(10_000);
        } finally {
            releaseClose.countDown();
            coreAgent.join();
        }
        assertThat(failure.get()).isNull();
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

        assertThatThrownBy(() -> CoreProbeStateRestoreTestSupport.restore(
                ProductLine.SPOT, 0, 0, Map.of(), sourceSequences,
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

        long freezeCount = original.snapshotProjectionFreezeCount();
        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.INVERSE_DELIVERY, original.snapshot());

        assertThat(restored.appliedCommandCount()).isEqualTo(original.appliedCommandCount());
        assertThat(restored.probeValue()).isEqualTo(8);
        assertThat(restored.stateHash()).isEqualTo(original.stateHash());
        assertThat(restored.snapshotProjectionSequence()).isEqualTo(original.snapshotProjectionSequence());
        assertThat(original.snapshotProjectionFreezeCount()).isEqualTo(freezeCount);
        assertThat(original.snapshotHasOutstandingReservation()).isFalse();
        assertThat(restored.snapshotHasOutstandingReservation()).isFalse();
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

            assertThat(state.apply(place).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            assertThat(state.snapshot()).isNotEmpty();
            assertThat(state.pendingMatching()).isEmpty();
        }
    }

    @Test
    void noTradePlaceCompletionPublishesOrderWithClientIndexBeforeSnapshotFreeze() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            applySpotInstrument(state);
            state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))));
            CoreMessage place = tradingCommand(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 2,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(713, "BTC-USDT", 1,
                            CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false,
                            "snapshot-attempt-713")));

            state.captureCommittedPatchesForTest();
            assertThat(state.apply(place).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            assertThat(state.commitReadyMatching(1, 0, 0, true, (sequence, response) -> { })).isEqualTo(1);
            var placePatch = state.capturedCommitPatchesForTest().getLast();
            var clientOrderChanges = placePatch.accountLaneGroups().stream()
                    .flatMap(group -> group.clientOrders().stream())
                    .toList();
            assertThat(clientOrderChanges).isNotEmpty();
            assertThat(clientOrderChanges.stream().anyMatch(change -> change.key().userId() == 1001
                            && change.beforeOrderId() == null
                            && Long.valueOf(713).equals(change.afterOrderId())))
                    .isTrue();
            assertThat(placePatch.accountLaneGroups().stream()
                    .flatMap(group -> group.orders().stream())
                    .anyMatch(change -> change.orderId() == 713
                            && change.businessAfter() != null
                            && change.businessAfter().userId() == 1001
                            && change.businessAfter().clientOrderId().equals("snapshot-attempt-713")))
                    .isTrue();
            assertThat(state.snapshot()).isNotEmpty();
            assertThat(state.pendingMatching()).isEmpty();
        }
    }

    @Test
    void snapshotPropagatesProjectionFailureInsteadOfWaitingForever() throws Exception {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        var journal = commitJournal(state);
        var failReplica = journal.getClass().getDeclaredMethod(
                "failReplicaAfterMutationsForTest", long.class, int.class);
        failReplica.setAccessible(true);
        failReplica.invoke(journal, journal.publishedSequence() + 1, 1);
        try {
            assertThat(state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);

            assertThatThrownBy(state::snapshot)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("runtime commit journal failed");
        } finally {
            assertThatThrownBy(state::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("runtime commit journal did not drain on close");
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
    void closeReleasesRealQueuedTriggerSharedAdmissionAcrossEveryHolder() throws Exception {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 2))));
        for (long triggerId : List.of(713L, 714L)) {
            var trigger = new com.surprising.aeron.protocol.CoreTriggerOrderStateView(triggerId,
                    ProductLine.SPOT, 1001, "tp-" + triggerId, "", "BTC-USDT", CoreOrderSide.SELL,
                    com.surprising.aeron.protocol.CoreTriggerOrderType.TAKE_PROFIT,
                    com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL, 70_000,
                    0, 0, 0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1,
                    CoreMarginMode.CROSS, CorePositionSide.NET,
                    com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                    "", "trace-close-" + triggerId, 0, 0, 1_000, 1_000, 1);
            CoreResponse placed = state.apply(tradingCommand(CoreMessageType.PLACE_TRIGGER_ORDER,
                    UUID.randomUUID(), triggerId - 710,
                    com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(trigger)));
            assertThat(placed.status()).withFailMessage("trigger %s result=%s", triggerId, placed.resultCode())
                    .isEqualTo(ResponseStatus.APPLIED);
        }
        CoreResponse markResponse = state.apply(tradingCommand(CoreMessageType.APPLY_MARK_PRICE, UUID.randomUUID(), 5,
                TradingCommandCodec.encodeApplyMarkPrice(new com.surprising.aeron.protocol.ApplyMarkPriceCommand(
                        "BTC-USDT", 1, 70_000, 8, 1_000))));
        assertThat(markResponse.status()).withFailMessage("mark result=%s", markResponse.resultCode())
                .isEqualTo(ResponseStatus.APPLIED);
        long continuationSequence = 6;
        while (state.pendingMatching().isEmpty()
                && !state.tradingState().riskState().scan().complete()) {
            assertThat(state.apply(tradingCommand(CoreMessageType.CONTINUE_RISK_SCAN, UUID.randomUUID(),
                    continuationSequence++, TradingCommandCodec.encodeContinueRiskScan(
                            new com.surprising.aeron.protocol.ContinueRiskScanCommand(64)))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
        }
        assertThat(state.pendingMatching())
                .withFailMessage("risk=%s triggers=%s", state.tradingState().riskState(),
                        state.tradingState().triggerOrders())
                .hasSize(2);
        CoreAdmissionReservation reservation = state.pendingMatching().values().iterator().next()
                .capacityReservation();
        assertThat(state.pendingMatching().values())
                .allMatch(pending -> pending.capacityReservation() == reservation);
        assertThat(reservation.holders()).isEqualTo(2);
        var journal = commitJournal(state);
        var fundsBeforeClose = state.tradingState().user(1001).balances();
        int idempotencyEntriesBeforeClose = state.commandResults().size();

        state.close();

        assertThat(reservation.holders()).isZero();
        assertThat(reservation.remainingPatches()).isZero();
        assertThat(reservation.remainingFacts()).isZero();
        assertThat(reservation.remainingFactNodes()).isZero();
        assertThat(reservation.remainingFactItems()).isZero();
        assertThat(reservation.remainingFactBytes()).isZero();
        assertThat(state.pendingMatching()).isEmpty();
        assertThat((Map<?, ?>) field(state, "factPatchChains")).isEmpty();
        assertThat(field(state, "currentAdmission")).isNull();
        assertThat(field(state, "activeFactCommand")).isNull();
        assertThat(field(state, "activeFactFingerprint")).isNull();
        assertThat(journal.metrics().reservedEntries()).isZero();
        assertThat(journal.metrics().reservedBytes()).isZero();
        assertThat(state.exportState().metrics().reservedEvents()).isZero();
        assertThat(state.exportState().metrics().reservedBytes()).isZero();
        assertThat(state.commandResults()).hasSize(idempotencyEntriesBeforeClose);
        assertThat(state.tradingState().user(1001).balances()).isEqualTo(fundsBeforeClose);
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
        CoreProbeState state = CoreProbeStateRestoreTestSupport.restore(ProductLine.SPOT, 0, 0,
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
    void exportBacklogCapacityIsReservedBeforeMutation() throws Exception {
        // Given
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        CoreExportState.AdmissionReservation blocker = null;
        try {
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
        var journal = (com.surprising.aeron.service.state.RuntimeCommitJournal)
                field(state, "runtimeProjectionJournal");
        long beforePublishedSequence = journal.publishedSequence();
        UUID rejectedCommandId = UUID.randomUUID();
        CoreMessage command = tradingCommand(CoreMessageType.PLACE_ORDER, rejectedCommandId, 2,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(9_001, "BTC-USDT", 1, CoreOrderSide.BUY, 600, 1, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "")));
        var demand = CoreAdmissionReservation.AdmissionDemand.matching(command);
        var exportStatus = state.exportState().status();
        long remainingBytes = exportStatus.maxPendingBytes() - exportStatus.pendingBytes();
        blocker = state.exportState().reserveAdmission(1,
                Math.subtractExact(Math.addExact(remainingBytes, 1), demand.factBytes()));
        assertThatThrownBy(() -> state.exportState().reserveAdmission(demand.factCount(), demand.factBytes()))
                .isInstanceOf(com.surprising.aeron.service.state.CoreStateRejectedException.class)
                .hasMessageContaining("export backlog reached hard limit");

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
        assertThat(journal.publishedSequence()).isEqualTo(beforePublishedSequence);
        assertThat(journal.metrics().reservedEntries()).isZero();
        assertThat(state.pendingMatching()).isEmpty();
        assertThat(state.commandResults()).doesNotContainKey(rejectedCommandId);
        } finally {
            if (blocker != null) state.exportState().release(blocker);
            state.close();
        }
    }

    @Test
    void commandDerivedRiskLiquidationAndAdlBoundsRejectBeforeEveryOwnerSurfaceMutation() throws Exception {
        try (CoreProbeState state = new CoreProbeState(ProductLine.LINEAR_PERPETUAL)) {
            assertThat(state.apply(tradingCommand(ProductLine.LINEAR_PERPETUAL,
                    CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);

            var actions = new java.util.ArrayList<com.surprising.aeron.protocol.ExecuteLiquidationBatchAction>(
                    com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand.MAX_ACTIONS);
            for (int index = 1; index <= com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand.MAX_ACTIONS;
                 index++) {
                actions.add(new com.surprising.aeron.protocol.ExecuteLiquidationBatchAction(
                        index, index, "BTC-USDT", 1, 1, 70_000, 0));
            }
            var oversizedLiquidation = new com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand(
                    actions, com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand.MAX_CANCEL_ORDERS,
                    0, new com.surprising.aeron.protocol.CoreRiskScanContinuation("BTC-USDT", 1, 0),
                    com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand.MAX_RISK_SCAN_USERS);
            UUID liquidationId = UUID.randomUUID();
            OwnerSurface beforeLiquidation = ownerSurface(state);
            CoreResponse liquidation = state.apply(tradingCommand(ProductLine.LINEAR_PERPETUAL,
                    CoreMessageType.EXECUTE_LIQUIDATION_BATCH,
                    liquidationId, 2, TradingCommandCodec.encodeExecuteLiquidationBatch(oversizedLiquidation)));
            assertThat(liquidation.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(liquidation.resultCode()).isEqualTo(CoreResultCode.EXPORT_BACKLOG_FULL);
            assertOwnerSurfaceUnchanged(state, beforeLiquidation, liquidationId);

            byte[] invalidRisk = TradingCommandCodec.encodeContinueRiskScan(
                    new com.surprising.aeron.protocol.ContinueRiskScanCommand(4_096));
            java.nio.ByteBuffer.wrap(invalidRisk).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4_097);
            UUID riskId = UUID.randomUUID();
            OwnerSurface beforeRisk = ownerSurface(state);
            CoreResponse risk = state.apply(tradingCommand(ProductLine.LINEAR_PERPETUAL,
                    CoreMessageType.CONTINUE_RISK_SCAN,
                    riskId, 3, invalidRisk));
            assertThat(risk.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(risk.resultCode()).isEqualTo(CoreResultCode.INVALID_COMMAND);
            assertOwnerSurfaceUnchanged(state, beforeRisk, riskId);

            UUID adlId = UUID.randomUUID();
            OwnerSurface beforeAdl = ownerSurface(state);
            CoreResponse adl = state.apply(tradingCommand(ProductLine.LINEAR_PERPETUAL,
                    CoreMessageType.EXECUTE_ADL,
                    adlId, 4, new byte[] {1, 2, 3}));
            assertThat(adl.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(adl.resultCode()).isEqualTo(CoreResultCode.INVALID_COMMAND);
            assertOwnerSurfaceUnchanged(state, beforeAdl, adlId);

            assertThat(commitJournal(state).metrics().reservedEntries()).isZero();
            assertThat(commitJournal(state).metrics().reservedBytes()).isZero();
            assertThat(state.exportState().metrics().reservedEvents()).isZero();
            assertThat(state.exportState().metrics().reservedBytes()).isZero();
        }
    }

    @Test
    void realLinearPerpetualFundingRiskLiquidationAndAdlUnderestimateRollBackExactOwnerSnapshot()
            throws Exception {
        var reducer = new com.surprising.aeron.service.state.TradingCoreReducer();
        var fundingState = linearStateWithPosition(reducer, 1001, 10, 100, 1_000, 100);
        fundingState = linearStateWithPosition(reducer, fundingState, 2002, -10, 100, 1_000, 100);
        fundingState = reducer.applyMarkPrice(fundingState,
                new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1, 1_700_000_000_000L));

        var riskState = linearStateWithPosition(reducer, 1001, 10, 100, 100, 100);

        var liquidated = reducer.applyMarkPrice(riskState,
                new ApplyMarkPriceCommand("BTC-USDT", 1, 1, 1, 1_700_000_000_000L));
        liquidated = reducer.executeLiquidation(liquidated,
                new com.surprising.aeron.protocol.ExecuteLiquidationCommand(1, 1, 1, 0));
        long deficit = liquidated.riskState().liquidations().get(1L).deficitUnits();
        var resolutionState = reducer.adjustInsuranceFund(liquidated,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand("USDT", deficit));

        var adlBase = linearStateWithPosition(reducer, riskState, 2002, -10, 200, 1_000, 100);
        adlBase = reducer.applyMarkPrice(adlBase,
                new ApplyMarkPriceCommand("BTC-USDT", 1, 1, 1, 1_700_000_000_000L));
        adlBase = reducer.executeLiquidation(adlBase,
                new com.surprising.aeron.protocol.ExecuteLiquidationCommand(1, 1, 1, 0));
        long initialAdlDeficit = adlBase.riskState().liquidations().get(1L).deficitUnits();
        long insuranceCovered = Math.max(1, initialAdlDeficit / 2);
        if (insuranceCovered >= initialAdlDeficit) insuranceCovered = initialAdlDeficit - 1;
        adlBase = reducer.adjustInsuranceFund(adlBase,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand("USDT", initialAdlDeficit));
        adlBase = reducer.resolveLiquidation(adlBase,
                new com.surprising.aeron.protocol.ResolveLiquidationCommand(1,
                        com.surprising.aeron.protocol.ResolveLiquidationCommand.Resolution.INSURANCE,
                        insuranceCovered));
        long adlDeficit = Math.subtractExact(initialAdlDeficit, insuranceCovered);

        List<UnderestimateScenario> scenarios = List.of(
                new UnderestimateScenario(fundingState, tradingCommand(ProductLine.LINEAR_PERPETUAL,
                        CoreMessageType.APPLY_FUNDING, UUID.randomUUID(), 1,
                        TradingCommandCodec.encodeApplyFunding(new com.surprising.aeron.protocol.ApplyFundingCommand(
                                91, "BTC-USDT", 1, 10_000, 0, 64)))),
                new UnderestimateScenario(riskState, tradingCommand(ProductLine.LINEAR_PERPETUAL,
                        CoreMessageType.APPLY_MARK_PRICE, UUID.randomUUID(), 1,
                        TradingCommandCodec.encodeApplyMarkPrice(
                                new ApplyMarkPriceCommand("BTC-USDT", 1, 1, 1, 1_700_000_000_000L)))),
                new UnderestimateScenario(resolutionState, tradingCommand(ProductLine.LINEAR_PERPETUAL,
                        CoreMessageType.RESOLVE_LIQUIDATION, UUID.randomUUID(), 1,
                        TradingCommandCodec.encodeResolveLiquidation(
                                new com.surprising.aeron.protocol.ResolveLiquidationCommand(1,
                                        com.surprising.aeron.protocol.ResolveLiquidationCommand.Resolution.INSURANCE,
                                        deficit)))),
                new UnderestimateScenario(adlBase, tradingCommand(ProductLine.LINEAR_PERPETUAL,
                        CoreMessageType.EXECUTE_ADL, UUID.randomUUID(), 1,
                        TradingCommandCodec.encodeExecuteAdl(new com.surprising.aeron.protocol.ExecuteAdlCommand(
                                1, 2002, "BTC-USDT", CoreMarginMode.CROSS, CorePositionSide.NET,
                                -10, 200, 1, 5, adlDeficit)))));

        CoreAdmissionReservation.setFactEstimateFaultInjectorForTest((message, estimate) ->
                new CoreAdmissionReservation.FactCostEstimate(
                        estimate.nodes(), estimate.nodes(), CoreProtocol.HEADER_LENGTH));
        try {
            for (UnderestimateScenario scenario : scenarios) {
                try (CoreProbeState state = restoredLinearState(scenario.state())) {
                    OwnerSurface beforeOwner = ownerSurface(state);
                    byte[] beforeSnapshot = state.snapshot();

                    CoreResponse rejected = state.apply(scenario.command());

                    assertThat(rejected.status()).isEqualTo(ResponseStatus.REJECTED);
                    assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.INVALID_COMMAND);
                    assertOwnerSurfaceUnchanged(state, beforeOwner, scenario.command().header().commandId());
                    assertSnapshotStateEquivalent(ProductLine.LINEAR_PERPETUAL,
                            beforeSnapshot, state.snapshot());
                    assertThat(commitJournal(state).metrics().reservedEntries()).isZero();
                    assertThat(commitJournal(state).metrics().reservedBytes()).isZero();
                    assertThat(state.exportState().metrics().reservedEvents()).isZero();
                    assertThat(state.exportState().metrics().reservedBytes()).isZero();
                }
            }
        } finally {
            CoreAdmissionReservation.setFactEstimateFaultInjectorForTest(null);
        }
    }

    @Test
    void realFundingEstimateOneByteOverAdmissionCapacityRejectsBeforeMutation() throws Exception {
        var reducer = new com.surprising.aeron.service.state.TradingCoreReducer();
        var seeded = linearStateWithPosition(reducer, 1001, 10, 100, 1_000, 100);
        CoreMessage funding = tradingCommand(ProductLine.LINEAR_PERPETUAL, CoreMessageType.APPLY_FUNDING,
                UUID.randomUUID(), 1, TradingCommandCodec.encodeApplyFunding(
                        new com.surprising.aeron.protocol.ApplyFundingCommand(
                                92, "BTC-USDT", 1, 10_000, 0, 64)));
        var estimate = CoreAdmissionReservation.FactCostEstimate.from(funding, 3, 256);
        CoreAdmissionReservation.setFactAdmissionByteLimitForTest(estimate.bytes() - 1);
        try (CoreProbeState state = restoredLinearState(seeded)) {
            OwnerSurface before = ownerSurface(state);
            long snapshotStarted = System.nanoTime();
            byte[] beforeSnapshot = state.snapshot();
            assertThat(System.nanoTime() - snapshotStarted)
                    .as("snapshot owner fence must not perform repeated canonical full-state hashing")
                    .isLessThan(TimeUnit.SECONDS.toNanos(5));

            CoreResponse rejected = state.apply(funding);

            assertThat(rejected.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.EXPORT_BACKLOG_FULL);
            assertOwnerSurfaceUnchanged(state, before, funding.header().commandId());
            assertSnapshotStateEquivalent(ProductLine.LINEAR_PERPETUAL,
                    beforeSnapshot, state.snapshot());
        } finally {
            CoreAdmissionReservation.setFactAdmissionByteLimitForTest(0);
        }
    }

    @Test
    void observedLiquidationFactKeepsMatcherEvidenceAndPoisonsAfterEstimatorUnderflow() throws Exception {
        var reducer = new com.surprising.aeron.service.state.TradingCoreReducer();
        var seeded = linearStateWithPosition(reducer, 1001, 10, 100, 100, 100);
        seeded = reducer.updateRiskScanControl(seeded,
                new com.surprising.aeron.protocol.UpdateRiskScanControlCommand(
                        seeded.riskState().scanControl().version(), "test-liquidation", true,
                        0, 64, "test", "exercise observed matcher fact rollback"),
                1_700_000_000_000L);
        seeded = reducer.applyMarkPrice(seeded,
                new ApplyMarkPriceCommand("BTC-USDT", 1, 1, 1, 1_700_000_000_000L));
        assertThat(seeded.riskState().liquidations()).containsKey(1L);
        CoreMessage liquidation = tradingCommand(ProductLine.LINEAR_PERPETUAL,
                CoreMessageType.EXECUTE_LIQUIDATION, UUID.randomUUID(), 1,
                TradingCommandCodec.encodeExecuteLiquidation(
                        new com.surprising.aeron.protocol.ExecuteLiquidationCommand(1, 1, 1, 0)));
        CoreAdmissionReservation.setFactEstimateFaultInjectorForTest((message, estimate) ->
                new CoreAdmissionReservation.FactCostEstimate(
                        estimate.nodes(), estimate.nodes(), CoreProtocol.HEADER_LENGTH));
        try (CoreProbeState state = restoredLinearState(seeded)) {
            assertThat(state.apply(liquidation).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            long sequence = state.pendingMatching().keySet().iterator().next();
            var matching = awaitMatching(state, sequence);
            int shardIndex = matching.nativeCommand().matcherShardId() + 1;
            var laneContexts = (LaneCommandContextRing) field(state, "laneCommandContexts");
            var submissionToken = laneContexts.required(sequence).matchingSubmissionToken();
            assertThat(submissionToken.active()).isTrue();
            OwnerSurface beforeOwner = ownerSurface(state);
            var beforeRuntimeState = state.tradingState();

            Throwable failure = catchThrowable(() -> state.completeMatching(
                    sequence, matching, liquidation.header().submittedAtEpochMillis(),
                    liquidation.header().sourceSequence()));

            assertThat(failure).isInstanceOf(
                    com.surprising.aeron.service.matching.FatalMatchingDivergenceException.class);
            assertThat(state.tradingState()).isEqualTo(beforeRuntimeState);
            assertThat(ownerSurface(state)).isEqualTo(beforeOwner);
            assertThat(((long[]) field(state, "appliedMatcherSequences"))[shardIndex])
                    .isEqualTo(matching.nativeCommand().matcherSequence());
            assertThat(((long[]) field(state, "appliedMatcherPrefixDigests"))[shardIndex])
                    .isEqualTo(matching.matcherPrefix().after());
            assertThat(laneContexts.required(sequence).matchingSubmissionToken().tokenId())
                    .isEqualTo(submissionToken.tokenId());
            assertThat(laneContexts.required(sequence).matchingSubmissionToken().active()).isFalse();
            assertThat(commitJournal(state).metrics().reservedEntries()).isPositive();
            assertThat(state.exportState().metrics().currentBacklog()).isZero();
            assertThatThrownBy(() -> state.apply(query(CoreMessageType.STATE_HASH_QUERY, 0, new byte[0])))
                    .isSameAs(failure);
            assertThatThrownBy(state::snapshot).isSameAs(failure);
        } finally {
            CoreAdmissionReservation.setFactEstimateFaultInjectorForTest(null);
        }
    }

    @Test
    @Timeout(10)
    void fullCommitJournalRejectsMatchingBeforeMatcherSubmissionOrOwnerMutation() throws Exception {
        String capacityProperty = "surprising.aeron.commit-journal-capacity";
        String exportProperty = "surprising.aeron.export-materialization-capacity";
        String previousCapacity = System.getProperty(capacityProperty);
        String previousExportCapacity = System.getProperty(exportProperty);
        System.setProperty(capacityProperty, "1024");
        System.setProperty(exportProperty, "2048");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CoreProbeState state = null;
        try {
            state = fundedSpotState();
            var journal = commitJournal(state);
            var stableProjection = state.tradingState();
            journal.blockProjectorForTest(entered, release);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            fillCommitJournal(journal, stableProjection);
            OwnerSurface before = ownerSurface(state, stableProjection);
            CoreMessage rejectedCommand = placeOrder(UUID.randomUUID(), 3, 9_301, "journal-full-9301");

            CoreResponse rejected = state.apply(rejectedCommand, 9_301, 9_301);

            assertThat(rejected.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.MATCHING_BACKPRESSURE);
            assertOwnerSurfaceUnchanged(state, stableProjection, before,
                    rejectedCommand.header().commandId());
            assertThat(journal.metrics().currentBacklog()).isEqualTo(1_024);
            assertThat(journal.metrics().reservedEntries()).isZero();
        } finally {
            release.countDown();
            if (state != null) {
                var journal = commitJournal(state);
                journal.await(journal.publishedSequence(), System.nanoTime() + TimeUnit.SECONDS.toNanos(5), true);
                state.close();
            }
            restoreProperty(capacityProperty, previousCapacity);
            restoreProperty(exportProperty, previousExportCapacity);
        }
    }

    @Test
    void fullExportReservationRejectsMatchingAndRollsBackTheJournalReservationAtomically() throws Exception {
        String exportProperty = "surprising.aeron.export-materialization-capacity";
        String previousExportCapacity = System.getProperty(exportProperty);
        System.setProperty(exportProperty, "4");
        CoreExportState.AdmissionReservation reservation = null;
        CoreProbeState state = null;
        try {
            state = fundedSpotState();
            var journal = commitJournal(state);
            reservation = state.exportState().reserveAdmission(2);
            assertThat(state.exportState().metrics().currentBacklog()
                    + state.exportState().metrics().reservedEvents()).isEqualTo(4);
            OwnerSurface before = ownerSurface(state);
            CoreMessage rejectedCommand = placeOrder(UUID.randomUUID(), 3, 9_302, "export-full-9302");

            CoreResponse rejected = state.apply(rejectedCommand, 9_302, 9_302);

            assertThat(rejected.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.EXPORT_BACKLOG_FULL);
            assertOwnerSurfaceUnchanged(state, before, rejectedCommand.header().commandId());
            assertThat(journal.metrics().reservedEntries()).isZero();
            assertThat(journal.metrics().reservedBytes()).isZero();
            assertThat(state.exportState().metrics().reservedEvents()).isEqualTo(2);
            assertThat(state.exportState().metrics().rejectionCount()).isPositive();
        } finally {
            if (state != null) {
                if (reservation != null) state.exportState().release(reservation);
                state.close();
            }
            restoreProperty(exportProperty, previousExportCapacity);
        }
    }

    @Test
    @Timeout(10)
    void bothFullAdmissionQueuesRejectWithoutCursorHashLaneOrIndexDrift() throws Exception {
        String capacityProperty = "surprising.aeron.commit-journal-capacity";
        String exportProperty = "surprising.aeron.export-materialization-capacity";
        String previousCapacity = System.getProperty(capacityProperty);
        String previousExportCapacity = System.getProperty(exportProperty);
        System.setProperty(capacityProperty, "1024");
        System.setProperty(exportProperty, "4");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CoreExportState.AdmissionReservation reservation = null;
        CoreProbeState state = null;
        try {
            state = fundedSpotState();
            var journal = commitJournal(state);
            var stableProjection = state.tradingState();
            reservation = state.exportState().reserveAdmission(2);
            journal.blockProjectorForTest(entered, release);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            fillCommitJournal(journal, stableProjection);
            assertThat(journal.metrics().currentBacklog()).isEqualTo(1_024);
            assertThat(state.exportState().metrics().currentBacklog()
                    + state.exportState().metrics().reservedEvents()).isEqualTo(4);
            OwnerSurface before = ownerSurface(state, stableProjection);
            CoreMessage rejectedCommand = placeOrder(UUID.randomUUID(), 3, 9_303, "both-full-9303");

            CoreResponse rejected = state.apply(rejectedCommand, 9_303, 9_303);

            assertThat(rejected.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.MATCHING_BACKPRESSURE);
            assertOwnerSurfaceUnchanged(state, stableProjection, before,
                    rejectedCommand.header().commandId());
            assertThat(journal.metrics().currentBacklog()).isEqualTo(1_024);
            assertThat(journal.metrics().reservedEntries()).isZero();
            assertThat(state.exportState().metrics().reservedEvents()).isEqualTo(2);
        } finally {
            release.countDown();
            if (state != null) {
                if (reservation != null) state.exportState().release(reservation);
                var journal = commitJournal(state);
                journal.await(journal.publishedSequence(), System.nanoTime() + TimeUnit.SECONDS.toNanos(5), true);
                state.close();
            }
            restoreProperty(capacityProperty, previousCapacity);
            restoreProperty(exportProperty, previousExportCapacity);
        }
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
                .hasMessageContaining("unsupported snapshot version: 2");
    }

    @Test
    void snapshotManifestReportsAuthoritativeMetadata() {
        CoreProbeState state = new CoreProbeState(ProductLine.OPTION);
        state.apply(command(ProductLine.OPTION, UUID.randomUUID(), 1, 3));

        CoreSnapshotManifest manifest = CoreProbeState.inspectSnapshot(ProductLine.OPTION, state.snapshot());

        assertThat(manifest.productLine()).isEqualTo(ProductLine.OPTION);
        assertThat(manifest.schemaVersion()).isEqualTo(17);
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
        long outboxSequenceBeforeAck = state.exportState().nextSequence();
        long businessHashBeforeAck = state.tradingState().businessStateHash();
        long revisionBeforeAck = state.tradingState().revision();
        long businessHashCoreSequenceBeforeAck = state.committedBusinessHashCoreSequence();
        long fundsHashCoreSequenceBeforeAck = state.committedFundsHashCoreSequence();
        long projectionSequenceBeforeAck = state.committedProjectionSequence();
        CoreMessage ack = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ACK_EXPORT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 9, 2, 0, 1_000, 5),
                CoreExportCodec.encodeAck(new AckExportCommand(throughSequence)));

        assertThat(state.apply(ack).status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.exportState().nextSequence()).isEqualTo(outboxSequenceBeforeAck);
        assertThat(state.committedBusinessHashCoreSequence()).isEqualTo(businessHashCoreSequenceBeforeAck);
        assertThat(state.committedFundsHashCoreSequence()).isEqualTo(fundsHashCoreSequenceBeforeAck);
        assertThat(state.committedProjectionSequence()).isEqualTo(projectionSequenceBeforeAck + 1);
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
        long outboxSequenceBeforeSnapshot = state.exportState().nextSequence();
        long projectionSequenceBeforeSnapshot = state.committedProjectionSequence();
        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot());
        assertThat(restored.tradingState().user(1001).reservations()).doesNotContainKey(901L);
        assertThat(restored.terminalRetentionTombstoneCount()).isEqualTo(1);
        assertThat(restored.exportState().nextSequence()).isEqualTo(outboxSequenceBeforeSnapshot);
        assertThat(restored.committedProjectionSequence()).isEqualTo(projectionSequenceBeforeSnapshot);
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

    private static CoreProbeState fundedSpotState() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        assertThat(state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 2,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                .status()).isEqualTo(ResponseStatus.APPLIED);
        return state;
    }

    private static CoreMessage placeOrder(UUID commandId, long sourceSequence, long orderId,
                                          String clientOrderId) {
        return tradingCommand(CoreMessageType.PLACE_ORDER, commandId, sourceSequence,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "BTC-USDT", 1,
                        CoreOrderSide.BUY, 600, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, clientOrderId)));
    }

    private static com.surprising.aeron.service.state.RuntimeCommitJournal commitJournal(
            CoreProbeState state) throws Exception {
        return (com.surprising.aeron.service.state.RuntimeCommitJournal) field(state,
                "runtimeProjectionJournal");
    }

    private static void fillCommitJournal(com.surprising.aeron.service.state.RuntimeCommitJournal journal,
                                          com.surprising.aeron.service.state.TradingCoreState state) throws Exception {
        long fundsHash = com.surprising.aeron.service.state.RollingFundsStateHash.compute(state);
        long sequence = journal.publishedSequence();
        for (int entry = 0; entry < 1_024; entry++) {
            sequence++;
            var builder = com.surprising.aeron.service.state.RuntimeCommitPatch.builder(
                    state.productLine(), sequence - 1, sequence, sequence - 1, sequence)
                    .matcherTransition(com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0));
            var patch = builder.seal(
                    new com.surprising.aeron.service.state.RuntimeCommitPatch.SealMetadata(
                            state.revision(), state.revision(), state.businessStateHash(), state.businessStateHash(),
                            fundsHash, fundsHash, 0, null));
            journal.publish(patch, patch.businessStateHash(), patch.fundsStateHash());
        }
    }

    private static OwnerSurface ownerSurface(CoreProbeState state) throws Exception {
        return ownerSurface(state, state.tradingState());
    }

    private static OwnerSurface ownerSurface(
            CoreProbeState state,
            com.surprising.aeron.service.state.TradingCoreState snapshot) throws Exception {
        var user = snapshot.user(1001);
        var runtime = (com.surprising.aeron.service.state.TradingRuntimeState) field(state,
                "runtimePlaceOrderState");
        var activeOrders = (com.surprising.aeron.service.state.ActiveOrderIndex) field(state, "activeOrderIndex");
        return new OwnerSurface(state.appliedCommandCount(), state.committedCoreSequence(), state.stateHash(),
                state.sourceSequenceDigest(), state.lastSourceSequences(), state.commandResults(),
                (long) field(state, "currentClusterTimestamp"), (long) field(state, "currentClusterPosition"),
                snapshot.businessStateHash(), user.balances(), user.reservations(), user.positions(),
                snapshot.orders(), activeOrders.page(0, "BTC-USDT", Long.MAX_VALUE, 10),
                runtime.accountLane(1001).committedSequence(), state.pendingMatching(),
                commitJournal(state).publishedSequence(), state.exportState().status());
    }

    private static com.surprising.aeron.service.state.TradingCoreState linearStateWithPosition(
            com.surprising.aeron.service.state.TradingCoreReducer reducer,
            long userId, long quantity, long entryPrice, long wallet, long margin) {
        var state = reducer.upsertInstrument(
                com.surprising.aeron.service.state.TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                new UpsertInstrumentCommand("BTC-USDT", 1, ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 100_000,
                        0, 0, 0, -1, 0));
        return linearStateWithPosition(reducer, state, userId, quantity, entryPrice, wallet, margin);
    }

    private static com.surprising.aeron.service.state.TradingCoreState linearStateWithPosition(
            com.surprising.aeron.service.state.TradingCoreReducer reducer,
            com.surprising.aeron.service.state.TradingCoreState state,
            long userId, long quantity, long entryPrice, long wallet, long margin) {
        var funded = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", wallet));
        var current = funded.user(userId);
        var balances = new TreeMap<>(current.balances());
        balances.put("USDT", new com.surprising.aeron.service.state.AssetBalance(
                "USDT", wallet - margin, margin));
        var positions = new TreeMap<>(current.positions());
        positions.put("BTC-USDT", new com.surprising.aeron.service.state.CorePositionState(
                "BTC-USDT", "USDT", 1, quantity, entryPrice,
                Math.multiplyExact(Math.absExact(quantity), entryPrice), 0, margin));
        var user = new com.surprising.aeron.service.state.CoreUserState(
                funded.productLine(), userId, current.revision() + 1,
                balances, current.reservations(), positions);
        var users = new TreeMap<>(funded.users());
        users.put(userId, user);
        return new com.surprising.aeron.service.state.TradingCoreState(
                funded.productLine(), funded.revision() + 1, users, funded.orders(),
                funded.instruments(), funded.riskState(), funded.treasuryState());
    }

    private static CoreProbeState restoredLinearState(
            com.surprising.aeron.service.state.TradingCoreState state) {
        return CoreProbeStateRestoreTestSupport.restore(ProductLine.LINEAR_PERPETUAL, 0, 0,
                Map.of(), Map.of(), state, new CoreExportState());
    }

    private static void assertOwnerSurfaceUnchanged(CoreProbeState state, OwnerSurface before,
                                                    UUID rejectedCommandId) throws Exception {
        assertOwnerSurfaceUnchanged(state, state.tradingState(), before, rejectedCommandId);
    }

    private static void assertOwnerSurfaceUnchanged(
            CoreProbeState state,
            com.surprising.aeron.service.state.TradingCoreState snapshot,
            OwnerSurface before,
            UUID rejectedCommandId) throws Exception {
        var after = ownerSurface(state, snapshot);
        assertThat(after).isEqualTo(before);
        assertThat(state.matchingSequence(rejectedCommandId)).isZero();
        assertThat(state.commandResults()).doesNotContainKey(rejectedCommandId);
    }

    private static void assertSnapshotStateEquivalent(
            ProductLine productLine,
            byte[] beforeSnapshot,
            byte[] afterSnapshot) throws Exception {
        CoreSnapshotManifest beforeManifest = CoreProbeState.inspectSnapshot(productLine, beforeSnapshot);
        CoreSnapshotManifest afterManifest = CoreProbeState.inspectSnapshot(productLine, afterSnapshot);
        assertThat(afterManifest)
                .usingRecursiveComparison()
                .ignoringFields("snapshotId", "clusterTimestamp", "clusterPosition",
                        "matcherSequence", "checksum")
                .isEqualTo(beforeManifest);
        assertThat(afterManifest.matcherSequence()).isGreaterThan(beforeManifest.matcherSequence());
        try (CoreProbeState before = CoreProbeState.fromSnapshot(productLine, beforeSnapshot);
             CoreProbeState after = CoreProbeState.fromSnapshot(productLine, afterSnapshot)) {
            assertThat(ownerSurface(after)).isEqualTo(ownerSurface(before));
            assertThat(after.tradingState()).isEqualTo(before.tradingState());
            assertThat(after.committedBusinessHashCoreSequence())
                    .isEqualTo(before.committedBusinessHashCoreSequence());
            assertThat(after.committedFundsHashCoreSequence())
                    .isEqualTo(before.committedFundsHashCoreSequence());
            assertThat(after.committedProjectionSequence())
                    .isEqualTo(before.committedProjectionSequence());
        }
    }

    private static void restoreProperty(String property, String value) {
        if (value == null) System.clearProperty(property); else System.setProperty(property, value);
    }

    private record OwnerSurface(long appliedCommandCount, long committedCoreSequence, long stateHash,
                                long sourceSequenceDigest, Map<CoreProbeState.SourceKey, Long> sourceCursors,
                                Map<UUID, CoreProbeState.StoredResult> commandResults,
                                long clusterTimestamp, long clusterPosition, long businessStateHash,
                                Map<String, com.surprising.aeron.service.state.AssetBalance> balances,
                                Map<Long, com.surprising.aeron.service.state.OrderReservation> reservations,
                                Map<String, com.surprising.aeron.service.state.CorePositionState> positions,
                                Map<Long, com.surprising.aeron.service.state.CoreOrderState> orders,
                                com.surprising.aeron.service.state.ActiveOrderIndex.Page activeOrders,
                                long laneSequence, Map<Long, PendingMatching> pendingMatching,
                                long journalSequence,
                                com.surprising.aeron.protocol.CoreExportStatus exportStatus) {
    }

    private record UnderestimateScenario(
            com.surprising.aeron.service.state.TradingCoreState state, CoreMessage command) {
    }

    private static CoreMessage command(UUID commandId, long sourceSequence, long delta) {
        return command(ProductLine.SPOT, commandId, sourceSequence, delta);
    }

    private static void awaitMaterializer(CountDownLatch releaseMaterializer) {
        try {
            if (!releaseMaterializer.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Core Fact materializer was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Core Fact materializer interrupted", exception);
        }
    }

    private static CoreExportState.Draft draft(
            CoreMessage command, long appliedCount, long businessStateHash, long beforeBusinessStateHash,
            com.surprising.aeron.protocol.CoreMatcherTransition transition, List<Long> terminalOrderIds) {
        var metadata = new com.surprising.aeron.service.state.RuntimeCommitPatch.CoreFactMetadata(
                command.header().commandId(), com.surprising.aeron.protocol.CommandFingerprint.of(command),
                command.header().messageType().wireCode(), command.header().userId(), ResponseStatus.APPLIED,
                CoreResultCode.NONE, appliedCount, appliedCount, 1, 1, false);
        return new CoreExportState.Draft(command, ResponseStatus.APPLIED, CoreResultCode.NONE,
                appliedCount, businessStateHash, beforeBusinessStateHash, 0, 0, 1, 1, transition,
                appliedCount, appliedCount, terminalOrderIds.size(), terminalOrderIds.stream()
                .mapToLong(Long::longValue).toArray(), null, CoreCommandDelta.empty(),
                com.surprising.aeron.service.state.RuntimeFundsDelta.empty(), metadata);
    }

    private static CoreAdmissionReservation.FactPermit factPermit(
            com.surprising.aeron.service.state.RuntimeCommitPatch patch) {
        return factPermits(List.of(patch)).getFirst();
    }

    private static List<CoreAdmissionReservation.FactPermit> factPermits(
            List<com.surprising.aeron.service.state.RuntimeCommitPatch> patches) {
        int items = patches.stream().mapToInt(
                com.surprising.aeron.service.state.RuntimeCommitPatch::coreFactItemCount).sum();
        long bytes = patches.stream().mapToLong(
                com.surprising.aeron.service.state.RuntimeCommitPatch::estimatedCoreFactBytes).sum();
        var budget = new CoreAdmissionReservation.FactBudget(patches.size(),
                Math.max(items, patches.size()), Math.max(bytes, patches.size()));
        var permits = new java.util.ArrayList<CoreAdmissionReservation.FactPermit>(patches.size());
        for (var patch : patches) {
            var permit = budget.reservePatch();
            permit.consume(patch);
            permits.add(permit);
        }
        return List.copyOf(permits);
    }

    private static com.surprising.aeron.service.state.RuntimeCommitPatch conservedFundsPatch(
            com.surprising.aeron.service.state.RuntimeIdentityRegistry identities, CoreMessage command) {
        int assetId = identities.assetId("USDT");
        var builder = com.surprising.aeron.service.state.RuntimeCommitPatch.builder(
                        ProductLine.LINEAR_PERPETUAL, 0, 1, 0, 1)
                .matcherTransition(com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0))
                .addFundsPosting(new com.surprising.aeron.service.state.RuntimeCommitPatch.FundsPosting(assetId,
                        com.surprising.aeron.service.state.FundsPosting.OwnerKind.USER, 1,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.AVAILABLE, -10))
                .addFundsPosting(new com.surprising.aeron.service.state.RuntimeCommitPatch.FundsPosting(assetId,
                        com.surprising.aeron.service.state.FundsPosting.OwnerKind.TREASURY, 0,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.FEE, 10));
        var metadata = new com.surprising.aeron.service.state.RuntimeCommitPatch.CoreFactMetadata(
                command.header().commandId(), com.surprising.aeron.protocol.CommandFingerprint.of(command),
                command.header().messageType().wireCode(), command.header().userId(), ResponseStatus.APPLIED,
                CoreResultCode.NONE, 1, 1, 1, 1, false);
        var prepared = builder.prepare(new com.surprising.aeron.service.state.RuntimeCommitPatch.PrepareMetadata(
                0, 1, 0, 0, 0, metadata, false), identities);
        return builder.seal(prepared, 1, 1);
    }

    private static CoreExportState.Draft draft(
            CoreMessage command, long appliedCount, long businessStateHash, long beforeBusinessStateHash,
            com.surprising.aeron.protocol.CoreMatcherTransition transition, List<Long> terminalOrderIds,
            CoreExportState.PatchChain patches,
            com.surprising.aeron.service.state.RuntimeIdentityRegistry identities) {
        var metadata = new com.surprising.aeron.service.state.RuntimeCommitPatch.CoreFactMetadata(
                command.header().commandId(), com.surprising.aeron.protocol.CommandFingerprint.of(command),
                command.header().messageType().wireCode(), command.header().userId(), ResponseStatus.APPLIED,
                CoreResultCode.NONE, appliedCount, appliedCount, 1, 1, false);
        int itemCount = terminalOrderIds.size() + (patches == null ? 0 : patches.itemCount());
        return new CoreExportState.Draft(command, ResponseStatus.APPLIED, CoreResultCode.NONE,
                appliedCount, businessStateHash, beforeBusinessStateHash, 0, 0, 1, 1, transition,
                appliedCount, appliedCount, itemCount, terminalOrderIds.stream().mapToLong(Long::longValue).toArray(),
                patches, CoreCommandDelta.empty(),
                com.surprising.aeron.service.state.RuntimeFundsDelta.empty(), metadata);
    }

    private static com.surprising.aeron.service.state.RuntimeCommitPatch orderPatch(
            com.surprising.aeron.service.state.RuntimeIdentityRegistry identities,
            com.surprising.aeron.service.state.OrderRuntime before,
            com.surprising.aeron.service.state.OrderRuntime after,
            long previousSequence, long sequence) {
        var builder = com.surprising.aeron.service.state.RuntimeCommitPatch.builder(
                        ProductLine.LINEAR_PERPETUAL, previousSequence, sequence, previousSequence, sequence)
                .matcherTransition(com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0));
        var businessBefore = before == null ? null
                : com.surprising.aeron.service.state.RuntimeStateMaterializer.orderSnapshot(before, identities);
        var businessAfter = after == null ? null
                : com.surprising.aeron.service.state.RuntimeStateMaterializer.orderSnapshot(after, identities);
        builder.recordOrder(1, before, after, businessBefore, businessAfter);
        builder.addLaneCommit(new com.surprising.aeron.service.state.RuntimeCommitPatch.LaneCommit(
                1, sequence, sequence, 0, 1, previousSequence, sequence,
                previousSequence, sequence, previousSequence, sequence));
        CoreMessage cause = command(ProductLine.LINEAR_PERPETUAL, UUID.randomUUID(), sequence, 1);
        var metadata = new com.surprising.aeron.service.state.RuntimeCommitPatch.CoreFactMetadata(
                cause.header().commandId(), com.surprising.aeron.protocol.CommandFingerprint.of(cause),
                cause.header().messageType().wireCode(), cause.header().userId(), ResponseStatus.APPLIED,
                CoreResultCode.NONE, sequence, sequence, 1, 1, false);
        var prepared = builder.prepare(new com.surprising.aeron.service.state.RuntimeCommitPatch.PrepareMetadata(
                previousSequence, sequence, previousSequence, previousSequence, 1L << 1, metadata, false),
                identities);
        return builder.seal(prepared, sequence, sequence);
    }

    private static void fillExportBacklogCapacity(CoreExportState exportState) {
        byte[] payload = new byte[CoreExportCodec.MAX_COMMAND_PAYLOAD / 2];
        for (long sequence = 1; sequence <= 6; sequence++) {
            CoreMessage command = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 91, sequence,
                    0, 1_000, sequence), payload);
            var transition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
            exportState.append(draft(command, sequence, 0, 0, transition, List.of()));
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

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void failHashCommit(Object hash) throws Exception {
        var method = hash.getClass().getDeclaredMethod("failAfterStagedOperationForTest", int.class);
        method.setAccessible(true);
        method.invoke(hash, 0);
    }

    private static CoreResponse completeMatching(CoreProbeState state, long sequence, CoreMessage message) {
        com.surprising.aeron.service.matching.CoreMatchingResult result = awaitMatching(state, sequence);
        CoreResponse completed = state.completeMatching(sequence, result, message.header().submittedAtEpochMillis(),
                message.header().sourceSequence());
        assertThat(completed).isNotNull();
        return completed;
    }

    private static com.surprising.aeron.service.matching.CoreMatchingResult awaitMatching(
            CoreProbeState state, long sequence) {
        com.surprising.aeron.service.matching.CoreMatchingResult result = null;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (result == null && System.nanoTime() < deadline) {
            result = state.takeMatchingResult(sequence);
            if (result == null) Thread.onSpinWait();
        }
        assertThat(result).as("matching result for sequence " + sequence).isNotNull();
        return result;
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
        return tradingCommand(ProductLine.SPOT, messageType, commandId, sourceSequence, payload);
    }

    private static CoreMessage tradingCommand(
            ProductLine productLine,
            CoreMessageType messageType,
            UUID commandId,
            long sourceSequence,
            byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(messageType, commandId,
                productLine, CommandSource.GATEWAY, 7, sourceSequence, 1001,
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
