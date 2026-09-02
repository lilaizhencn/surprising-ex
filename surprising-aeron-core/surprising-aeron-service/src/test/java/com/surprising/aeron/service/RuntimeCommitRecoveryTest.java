package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.protocol.AckExportCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.ContinueRiskScanCommand;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.service.state.CoreFeePolicyState;
import com.surprising.aeron.service.state.CoreLiquidationState;
import com.surprising.aeron.service.state.CoreOrderStatus;
import com.surprising.aeron.service.state.LaneTopology;
import com.surprising.aeron.service.state.RuntimeFactFrame;
import com.surprising.aeron.service.state.TransferRuntime;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.Header;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32C;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

class RuntimeCommitRecoveryTest {

    @Test
    void restorePublishesAuxiliaryStateWithTheCanonicalCandidate() {
        CoreFeePolicyState feePolicy = new CoreFeePolicyState(
                91, 3, 1001, "BTC-USDT", -25, 75, 2, true, 900, 2_000);
        TransferRuntime transfer = new TransferRuntime(1001, new TransferFundsCommand(
                701, ProductLine.LINEAR_PERPETUAL, ProductLine.SPOT,
                "USDT_PERPETUAL", "SPOT", "USDT", 125, "recovery-701", "snapshot parity"));
        try (CoreProbeState original = new CoreProbeState(ProductLine.LINEAR_PERPETUAL)) {
            original.restoreFeePolicies(Map.of(feePolicy.policyId(), feePolicy));
            original.restorePendingTransfers(Map.of(transfer.transferId(), transfer));
            long originalStateHash = original.stateHash();
            long originalBusinessHash = original.snapshotBusinessStateHash();
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(
                    ProductLine.LINEAR_PERPETUAL, original.snapshot(700))) {
                assertThat(restored.feePolicies()).containsExactlyEntriesOf(original.feePolicies());
                assertThat(restored.pendingTransfers()).containsExactlyEntriesOf(original.pendingTransfers());
                assertThat(restored.stateHash()).isEqualTo(originalStateHash);
                assertThat(restored.snapshotProjectionSequence()).isEqualTo(original.snapshotProjectionSequence());
                List<CoreMessage> postSnapshot = List.of(
                        command(1, 1001, CoreMessageType.ADJUST_BALANCE, balance(500)));
                ReplayResult originalReplay = replay(original, postSnapshot);
                ReplayResult restoredReplay = replay(restored, postSnapshot);
                assertParity(restored, original, restoredReplay, originalReplay);
                PatchEvidence firstRestoredPatch = restoredReplay.patches().getFirst();
                assertThat(firstRestoredPatch.beforeBusinessStateHash()).isEqualTo(originalBusinessHash);
                assertThat(firstRestoredPatch.businessStateHash())
                        .isEqualTo(restored.snapshotBusinessStateHash());
                assertThat(restored.snapshotProjectionSequence()).isEqualTo(firstRestoredPatch.projectionSequence());
                assertThat(restored.feePolicies()).containsEntry(feePolicy.policyId(), feePolicy);
                assertThat(restored.pendingTransfers()).containsEntry(transfer.transferId(), transfer);
                try (CoreProbeState recoveredAgain = CoreProbeState.fromSnapshot(
                        ProductLine.LINEAR_PERPETUAL, restored.snapshot(704))) {
                    assertThat(recoveredAgain.stateHash()).isEqualTo(restored.stateHash());
                    assertThat(recoveredAgain.snapshotBusinessStateHash())
                            .isEqualTo(firstRestoredPatch.businessStateHash());
                }
            }
        }
    }

    @Test
    void replayAfterRestoreProducesIdenticalPatchesAndFacts() {
        List<CoreMessage> partialFill = List.of(
                command(4, 22, CoreMessageType.PLACE_ORDER, place(202, CoreOrderSide.BUY, 100, 4, false)));
        List<CoreMessage> middle = List.of(
                command(5, 33, CoreMessageType.ADJUST_BALANCE, balance(2_000)),
                command(6, 33, CoreMessageType.PLACE_ORDER, place(303, CoreOrderSide.BUY, 100, 6, false)),
                command(7, 11, CoreMessageType.PLACE_ORDER, place(102, CoreOrderSide.SELL, 110, 2, false)),
                command(8, 11, CoreMessageType.CANCEL_ORDER,
                        TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(102))),
                operationsCommand(2, CoreMessageType.APPLY_FUNDING,
                        TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(501, "BTC-USDT", 1, 10_000))));
        List<CoreMessage> tail = List.of(
                command(9, 11, CoreMessageType.PLACE_ORDER, place(401, CoreOrderSide.BUY, 100, 10, false)),
                command(10, 22, CoreMessageType.PLACE_ORDER, place(402, CoreOrderSide.SELL, 100, 4, true)),
                command(11, 33, CoreMessageType.PLACE_ORDER, place(403, CoreOrderSide.SELL, 100, 6, true)));
        try (CoreProbeState uninterrupted = seededLinearPerpetual()) {
            byte[] snapshot = uninterrupted.snapshot(701);
            try (CoreProbeState firstRestore = CoreProbeState.fromSnapshot(ProductLine.LINEAR_PERPETUAL, snapshot)) {
                long fenceProjectionSequence = uninterrupted.snapshotProjectionSequence();
                assertThat(firstRestore.snapshotProjectionSequence()).isEqualTo(fenceProjectionSequence);
                ReplayResult uninterruptedPartial = replay(uninterrupted, partialFill);
                ReplayResult restoredPartial = replay(firstRestore, partialFill);
                assertParity(firstRestore, uninterrupted, restoredPartial, uninterruptedPartial);
                assertThat(uninterrupted.tradingState().order(101).status()).isEqualTo(CoreOrderStatus.OPEN);
                assertThat(uninterrupted.tradingState().order(101).executedQuantitySteps()).isEqualTo(4);
                assertThat(uninterrupted.tradingState().order(101).remainingQuantitySteps()).isEqualTo(6);
                assertThat(uninterrupted.tradingState().user(11).balances().get("USDT").lockedUnits())
                        .isPositive();
                ReplayResult uninterruptedMiddle = replay(uninterrupted, middle);
                ReplayResult restoredMiddle = replay(firstRestore, middle);
                assertParity(firstRestore, uninterrupted, restoredMiddle, uninterruptedMiddle);
                assertThat(uninterrupted.tradingState().order(101).status()).isEqualTo(CoreOrderStatus.FILLED);
                assertThat(uninterrupted.tradingState().order(202).executedQuantitySteps()).isEqualTo(4);
                assertThat(uninterrupted.tradingState().order(102).status()).isEqualTo(CoreOrderStatus.CANCELED);
                assertThat(uninterruptedMiddle.patches()).anyMatch(patchEvidence ->
                        !patchEvidence.coreFactValues().fundingPayments().isEmpty());

                byte[] secondSnapshot = uninterrupted.snapshot(702);
                try (CoreProbeState secondRestore = CoreProbeState.fromSnapshot(
                        ProductLine.LINEAR_PERPETUAL, secondSnapshot)) {
                    ReplayResult uninterruptedTail = replay(uninterrupted, tail);
                    ReplayResult firstRestoreTail = replay(firstRestore, tail);
                    ReplayResult secondRestoreTail = replay(secondRestore, tail);
                    assertParity(firstRestore, uninterrupted, firstRestoreTail, uninterruptedTail);
                    assertParity(secondRestore, uninterrupted, secondRestoreTail, uninterruptedTail);
                    assertThat(uninterrupted.tradingState().user(11).positions().get("BTC-USDT")
                            .signedQuantitySteps()).isZero();
                    assertThat(uninterrupted.tradingState().user(22).positions().get("BTC-USDT")
                            .signedQuantitySteps()).isZero();
                    assertThat(uninterrupted.tradingState().user(33).positions().get("BTC-USDT")
                            .signedQuantitySteps()).isZero();
                    assertThat(uninterruptedTail.patches()).anyMatch(patchEvidence ->
                            !patchEvidence.terminalIds().orderIds().isEmpty());

                    ReplayResult uninterruptedLiquidation = liquidate(uninterrupted);
                    ReplayResult firstRestoreLiquidation = liquidate(firstRestore);
                    ReplayResult secondRestoreLiquidation = liquidate(secondRestore);
                    assertParity(firstRestore, uninterrupted, firstRestoreLiquidation, uninterruptedLiquidation);
                    assertParity(secondRestore, uninterrupted, secondRestoreLiquidation, uninterruptedLiquidation);
                    assertThat(uninterrupted.tradingState().riskState().liquidations().values())
                            .anyMatch(value -> value.status() == CoreLiquidationState.Status.INSURANCE_REQUIRED
                                    && value.deficitUnits() > 0);
                    assertThat(uninterruptedLiquidation.patches()).allMatch(patchEvidence ->
                            patchEvidence.terminalIds().liquidationIds().isEmpty());
                }

                CoreMessage duplicateCommand = partialFill.getFirst();
                long projectionBeforeDuplicate = firstRestore.snapshotProjectionSequence();
                long exportBeforeDuplicate = firstRestore.exportState().nextSequence();
                firstRestore.captureCommittedPatchesForTest();
                CoreResponse duplicate = firstRestore.apply(duplicateCommand);
                assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(duplicate.resultCode()).isEqualTo(restoredPartial.responses().getFirst().resultCode());
                assertThat(duplicate.stateHash()).isEqualTo(restoredPartial.responses().getFirst().stateHash());
                assertThat(HexFormat.of().formatHex(duplicate.data()))
                        .isEqualTo(restoredPartial.responses().getFirst().data());
                assertThat(firstRestore.drainCapturedFactFramesForTest()).isEmpty();
                assertThat(firstRestore.snapshotProjectionSequence()).isEqualTo(projectionBeforeDuplicate);
                assertThat(firstRestore.exportState().nextSequence()).isEqualTo(exportBeforeDuplicate);
                CoreMessage changedRetry = new CoreMessage(duplicateCommand.header(),
                        place(202, CoreOrderSide.BUY, 100, 5, false));
                firstRestore.captureCommittedPatchesForTest();
                assertThat(firstRestore.apply(changedRetry).resultCode())
                        .isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
                assertThat(firstRestore.drainCapturedFactFramesForTest()).isEmpty();
                assertThat(firstRestore.snapshotProjectionSequence()).isEqualTo(projectionBeforeDuplicate);
                assertThat(firstRestore.exportState().nextSequence()).isEqualTo(exportBeforeDuplicate);
            }
        }
    }

    @Test
    void rejectsCorruptLaneAndHashSnapshotAtomically() {
        try (CoreProbeState original = seededLinearPerpetual()) {
            byte[] snapshot = original.snapshot(703);
            long stateHash = original.stateHash();
            long projectionSequence = original.snapshotProjectionSequence();
            long projectorThreads = projectorThreadCount();
            AtomicReference<CoreProbeState> published = new AtomicReference<>(original);
            byte[] corruptLaneManifest = mutateLaneUserPreservingDigest(snapshot, original.laneTopology());
            byte[] corruptChecksum = snapshot.clone();
            corruptChecksum[SectionedCoreSnapshotCodec.ENVELOPE_LENGTH
                    + SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH + 20] ^= 1;
            byte[] corruptBusinessHash = mutateLongInSection(snapshot, 1, 138, 1);
            byte[] corruptProjectionSequence = mutateLongInSection(snapshot, 1, 90, 1);
            byte[] corruptExportSequence = mutateLongInSection(snapshot, 4, Long.BYTES, 1);

            assertThatThrownBy(() -> published.set(CoreStateSnapshotCodec.decode(
                    corruptLaneManifest, ProductLine.LINEAR_PERPETUAL)))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("user manifest differs from global state");
            assertThatThrownBy(() -> published.set(CoreStateSnapshotCodec.decode(
                    corruptChecksum, ProductLine.LINEAR_PERPETUAL)))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("checksum");
            assertThatThrownBy(() -> CoreStateSnapshotCodec.decode(
                    corruptBusinessHash, ProductLine.LINEAR_PERPETUAL))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("business state hash");
            assertThatThrownBy(() -> CoreStateSnapshotCodec.decode(
                    corruptProjectionSequence, ProductLine.LINEAR_PERPETUAL))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("projection sequence");
            assertThatThrownBy(() -> CoreStateSnapshotCodec.decode(
                    corruptExportSequence, ProductLine.LINEAR_PERPETUAL))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("outbox next sequence");
            assertThat(original.stateHash()).isEqualTo(stateHash);
            assertThat(original.snapshotProjectionSequence()).isEqualTo(projectionSequence);
            assertThat(published.get()).isSameAs(original);
            assertThat(projectorThreadCount()).isEqualTo(projectorThreads);
        }
    }

    @Test
    void recoversPairedOrderBatchWithSynchronousOwnerMatching()
            throws Exception {
        CoreMessage batch = command(5, 1001, CoreMessageType.PLACE_ORDER_BATCH,
                TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                        batchPlace(82_001, "fatal-batch-first", CoreOrderSide.BUY, 100, 1),
                        batchPlace(82_002, "fatal-batch-second", CoreOrderSide.BUY, 100, 1)))));
        byte[] pairedSnapshot;
        try (CoreProbeState seed = seededPerpetualBatchState()) {
            pairedSnapshot = seed.snapshot(705);
        }

        SurprisingClusteredService uninterrupted = restoredService(pairedSnapshot);
        SurprisingClusteredService recovered = restoredService(pairedSnapshot);
        try {
            BatchReplay reference = completeBatch(uninterrupted, batch);
            BatchReplay replayed = completeBatch(recovered, batch);

            assertThat(replayed).isEqualTo(reference);
            assertBatchRecoveryParity(recovered.state(), uninterrupted.state());
            assertThat(replayed.patches()).anyMatch(patch -> !patch.terminalIds().orderIds().isEmpty());
            assertThat(replayed.patches()).anyMatch(patch -> !patch.fundsPostings().isEmpty());
            assertThat(recovered.state().tradingState().user(1001).balances().get("USDT").lockedUnits()).isPositive();
            assertThat(recovered.state().tradingState().user(1001).reservations().values())
                    .allMatch(reservation -> reservation.remainingUnits() == 0);
            assertThat(recovered.state().tradingState().user(2001).balances().get("USDT").lockedUnits()).isPositive();
            assertThat(recovered.state().tradingState().user(2001).reservations().values())
                    .allMatch(reservation -> reservation.remainingUnits() == 0);
            assertThat(recovered.state().tradingState().user(1001).positions().get("BTC-USDT").signedQuantitySteps())
                    .isEqualTo(2);
            assertThat(recovered.state().tradingState().user(2001).positions().get("BTC-USDT").signedQuantitySteps())
                    .isEqualTo(-2);
            assertThat(recovered.state().tradingState().orders().values())
                    .allMatch(order -> order.status() == CoreOrderStatus.FILLED);
            assertThat(recovered.state().tradingState().clientOrderIndex()).containsEntry(
                    new com.surprising.aeron.service.state.TradingCoreState.ClientOrderKey(
                            1001, "fatal-batch-first"), 82_001L);
            assertThat(recovered.state().tradingState().clientOrderIndex()).containsEntry(
                    new com.surprising.aeron.service.state.TradingCoreState.ClientOrderKey(
                            1001, "fatal-batch-second"), 82_002L);
            assertThat(recovered.state().tradingState().riskState())
                    .isEqualTo(uninterrupted.state().tradingState().riskState());
            assertThat(recovered.state().tradingState().treasuryState())
                    .isEqualTo(uninterrupted.state().tradingState().treasuryState());
            assertThat(recovered.state().tradingState().treasuryState().feeBalances().get("USDT")).isPositive();
            assertThat(economicUsdt(recovered.state().tradingState())).isEqualTo(4_000);
            assertThat(replayed.patches().stream().flatMap(patch -> patch.fundsPostings().stream())
                    .collect(java.util.stream.Collectors.groupingBy(
                            RuntimeFactFrame.FundsPosting::assetId,
                            java.util.stream.Collectors.summingLong(RuntimeFactFrame.FundsPosting::units))))
                    .allSatisfy((assetId, units) -> assertThat(units).as("assetId=" + assetId).isZero());

            long projectionBeforeDuplicate = recovered.state().snapshotProjectionSequence();
            List<String> factsBeforeDuplicate = encodedV10OutboxFacts(recovered.state());
            CoreResponse referenceDuplicate = replayClusterCommand(uninterrupted, batch).response();
            CoreResponse recoveredDuplicate = replayClusterCommand(recovered, batch).response();
            assertThat(response(recoveredDuplicate)).isEqualTo(response(referenceDuplicate));
            assertThat(recoveredDuplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(recovered.state().snapshotProjectionSequence()).isEqualTo(projectionBeforeDuplicate);
            assertThat(encodedV10OutboxFacts(recovered.state())).isEqualTo(factsBeforeDuplicate);
            assertBatchRecoveryParity(recovered.state(), uninterrupted.state());
        } finally {
            uninterrupted.state().close();
            recovered.state().close();
        }

    }

    @Test
    void replaysInsuranceResolutionAndAdlAcrossPairedClusteredSnapshotCuts() throws Exception {
        SurprisingClusteredService uninterrupted = new SurprisingClusteredService(ProductLine.LINEAR_PERPETUAL);
        uninterrupted.onStart(cluster(), null);
        try {
            ReplayResult setup = replayClustered(uninterrupted, List.of(
                    operationsCommand(1, CoreMessageType.UPSERT_INSTRUMENT,
                            TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand(
                                    "BTC-USDT", 1, ContractType.LINEAR_PERPETUAL.ordinal(),
                                    "BTC", "USDT", "USDT", 1, 1, 1,
                                    100_000, 50_000, 0, 0, 0, -1, 0))),
                    kafkaCommand(1, CoreMessageType.APPLY_MARK_PRICE,
                            TradingCommandCodec.encodeApplyMarkPrice(
                                    new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1, 1_000))),
                    command(1, 1, CoreMessageType.ADJUST_BALANCE, balance(110)),
                    command(2, 2, CoreMessageType.ADJUST_BALANCE, balance(1_000)),
                    command(3, 3, CoreMessageType.ADJUST_BALANCE, balance(100)),
                    command(4, 2, CoreMessageType.PLACE_ORDER,
                            place(91_001, CoreOrderSide.SELL, 100, 10, false)),
                    command(5, 1, CoreMessageType.PLACE_ORDER,
                            place(91_002, CoreOrderSide.BUY, 100, 10, false)),
                    command(6, 3, CoreMessageType.PLACE_ORDER,
                            place(91_003, CoreOrderSide.BUY, 1, 10, false)),
                    operationsCommand(2, CoreMessageType.APPLY_FUNDING,
                            TradingCommandCodec.encodeApplyFunding(
                                    new ApplyFundingCommand(901, "BTC-USDT", 1, 1_000))),
                    kafkaCommand(2, CoreMessageType.APPLY_MARK_PRICE,
                            TradingCommandCodec.encodeApplyMarkPrice(
                                    new ApplyMarkPriceCommand("BTC-USDT", 1, 1, 2, 2_000)))));
            assertThat(setup.patches()).isNotEmpty();
            assertThat(setup.patches()).anyMatch(patch -> !patch.coreFactValues().fundingPayments().isEmpty());
            long operationsSequence = 3;
            CoreLiquidationWorkView work = liquidationWork(uninterrupted.state());
            while (work.riskScanPending()) {
                replayClustered(uninterrupted, List.of(operationsCommand(
                        operationsSequence++, CoreMessageType.CONTINUE_RISK_SCAN,
                        TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(1)))));
                work = liquidationWork(uninterrupted.state());
            }
            CoreLiquidationState planned = uninterrupted.state().tradingState().riskState().liquidations()
                    .values().stream().filter(value -> value.userId() == 1).findFirst().orElseThrow();
            CoreMessage executeLiquidation = operationsCommand(
                    operationsSequence++, CoreMessageType.EXECUTE_LIQUIDATION,
                    TradingCommandCodec.encodeExecuteLiquidation(new ExecuteLiquidationCommand(
                            planned.liquidationId(), planned.triggerPriceSequence(), 1, 0)));
            long insuranceAdjustSequence = operationsSequence++;
            long insuranceResolveSequence = operationsSequence++;
            long adlSequence = operationsSequence;
            ReplayResult liquidation = replayClustered(uninterrupted, List.of(executeLiquidation));
            CoreLiquidationState insuranceRequired = liquidation(uninterrupted.state(), planned.liquidationId());
            long deficit = insuranceRequired.deficitUnits();
            assertThat(deficit).isGreaterThan(25);
            assertThat(insuranceRequired.status()).isEqualTo(CoreLiquidationState.Status.INSURANCE_REQUIRED);
            assertThat(deficit(uninterrupted.state())).isEqualTo(deficit);
            assertPosting(liquidation, com.surprising.aeron.service.state.FundsPosting.Subledger.DEFICIT,
                    Math.negateExact(deficit));
            assertFundsPostingConservation(liquidation);
            long economicBeforeInsurance = economicEquityUsdt(uninterrupted.state());
            long acknowledged = uninterrupted.state().exportState().nextSequence() - 1;
            ReplayResult ack = replayClustered(uninterrupted, List.of(sourcedCommand(
                    CommandSource.RECOVERY_TOOL, 90, 1, 0, CoreMessageType.ACK_EXPORT,
                    CoreExportCodec.encodeAck(new AckExportCommand(acknowledged)))));
            assertThat(ack.patches()).allSatisfy(patch -> {
                assertThat(patch.coreSequence())
                        .isEqualTo(Math.incrementExact(patch.previousCoreSequence()));
                assertThat(patch.beforeRevision()).isEqualTo(patch.afterRevision());
                assertThat(patch.fundsPostings()).isEmpty();
            });
            assertThat(ack.patches()).anyMatch(patch ->
                    !patch.coreFactFragment().tombstones().orderIds().isEmpty());
            assertThat(uninterrupted.state().exportState().snapshot().acknowledgedSequence())
                    .isEqualTo(acknowledged);

            byte[] beforeInsurance = uninterrupted.state().snapshot(710);
            SurprisingClusteredService restoredBeforeInsurance = restoredService(beforeInsurance);
            try {
                assertBatchRecoveryParity(restoredBeforeInsurance.state(), uninterrupted.state());
                List<CoreMessage> partialInsurance = List.of(
                        operationsCommand(insuranceAdjustSequence, CoreMessageType.ADJUST_INSURANCE_FUND,
                                TradingCommandCodec.encodeAdjustInsuranceFund(
                                        new AdjustInsuranceFundCommand("USDT", 25))),
                        operationsCommand(insuranceResolveSequence, CoreMessageType.RESOLVE_LIQUIDATION,
                                TradingCommandCodec.encodeResolveLiquidation(new ResolveLiquidationCommand(
                                        planned.liquidationId(), ResolveLiquidationCommand.Resolution.INSURANCE, 25))));
                ReplayResult partialReference = replayClustered(uninterrupted, partialInsurance);
                ReplayResult partialRecovered = replayClustered(restoredBeforeInsurance, partialInsurance);
                assertThat(partialRecovered).isEqualTo(partialReference);
                assertBatchRecoveryParity(restoredBeforeInsurance.state(), uninterrupted.state());
                long residual = Math.subtractExact(deficit, 25);
                assertThat(liquidation(uninterrupted.state(), planned.liquidationId()).deficitUnits())
                        .isEqualTo(residual);
                assertThat(liquidation(uninterrupted.state(), planned.liquidationId()).status())
                        .isEqualTo(CoreLiquidationState.Status.ADL_REQUIRED);
                assertThat(deficit(uninterrupted.state())).isEqualTo(residual);
                assertPosting(partialReference,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.DEFICIT, 25);
                assertFundsPostingConservation(partialReference);
                assertThat(economicEquityUsdt(uninterrupted.state()))
                        .isEqualTo(Math.addExact(economicBeforeInsurance, 25));
                assertDuplicateClusterReplay(
                        uninterrupted, restoredBeforeInsurance, partialInsurance.getFirst());
                assertDuplicateClusterReplay(
                        uninterrupted, restoredBeforeInsurance, partialInsurance.getLast());

                byte[] afterInsurance = uninterrupted.state().snapshot(711);
                SurprisingClusteredService restoredAfterInsurance = restoredService(afterInsurance);
                try {
                    assertBatchRecoveryParity(restoredAfterInsurance.state(), uninterrupted.state());
                    CoreMessage adl = operationsCommand(adlSequence, CoreMessageType.EXECUTE_ADL,
                            TradingCommandCodec.encodeExecuteAdl(new ExecuteAdlCommand(
                                    planned.liquidationId(), 2, "BTC-USDT", CoreMarginMode.CROSS,
                                    CorePositionSide.NET, -10, 100, 2, 10, residual)));
                    long beforeAdlEconomic = economicEquityUsdt(uninterrupted.state());
                    ReplayResult adlReference = replayClustered(uninterrupted, List.of(adl));
                    ReplayResult adlFromBeforeCut = replayClustered(restoredBeforeInsurance, List.of(adl));
                    ReplayResult adlFromAfterCut = replayClustered(restoredAfterInsurance, List.of(adl));
                    assertThat(adlFromBeforeCut).isEqualTo(adlReference);
                    assertThat(adlFromAfterCut).isEqualTo(adlReference);
                    assertBatchRecoveryParity(restoredBeforeInsurance.state(), uninterrupted.state());
                    assertBatchRecoveryParity(restoredAfterInsurance.state(), uninterrupted.state());
                    assertThat(liquidation(uninterrupted.state(), planned.liquidationId()).deficitUnits()).isZero();
                    assertThat(deficit(uninterrupted.state())).isZero();
                    assertThat(liquidation(uninterrupted.state(), planned.liquidationId()).status())
                            .isEqualTo(CoreLiquidationState.Status.COMPLETED);
                    assertThat(uninterrupted.state().tradingState().user(2).positions().get("BTC-USDT")
                            .signedQuantitySteps()).isZero();
                    assertThat(adlReference.patches()).anyMatch(patch ->
                            patch.terminalIds().liquidationIds().contains(planned.liquidationId()));
                    assertPosting(adlReference,
                            com.surprising.aeron.service.state.FundsPosting.Subledger.DEFICIT, residual);
                    assertFundsPostingConservation(adlReference);
                    assertThat(economicEquityUsdt(uninterrupted.state())).isEqualTo(beforeAdlEconomic);

                    byte[] afterAdl = uninterrupted.state().snapshot(712);
                    SurprisingClusteredService restoredAfterAdl = restoredService(afterAdl);
                    try {
                        assertBatchRecoveryParity(restoredAfterAdl.state(), uninterrupted.state());
                        assertDuplicateClusterReplay(uninterrupted, restoredAfterAdl, adl);
                    } finally {
                        restoredAfterAdl.state().close();
                    }
                } finally {
                    restoredAfterInsurance.state().close();
                }
            } finally {
                restoredBeforeInsurance.state().close();
            }

            SurprisingClusteredService fullReference = restoredService(beforeInsurance);
            SurprisingClusteredService fullRecovered = restoredService(beforeInsurance);
            try {
                List<CoreMessage> fullInsurance = List.of(
                        operationsCommand(insuranceAdjustSequence, CoreMessageType.ADJUST_INSURANCE_FUND,
                                TradingCommandCodec.encodeAdjustInsuranceFund(
                                        new AdjustInsuranceFundCommand("USDT", deficit))),
                        operationsCommand(insuranceResolveSequence, CoreMessageType.RESOLVE_LIQUIDATION,
                                TradingCommandCodec.encodeResolveLiquidation(new ResolveLiquidationCommand(
                                        planned.liquidationId(), ResolveLiquidationCommand.Resolution.INSURANCE,
                                        deficit))));
                ReplayResult fullReferenceResult = replayClustered(fullReference, fullInsurance);
                ReplayResult fullRecoveredResult = replayClustered(fullRecovered, fullInsurance);
                assertThat(fullRecoveredResult).isEqualTo(fullReferenceResult);
                assertBatchRecoveryParity(fullRecovered.state(), fullReference.state());
                assertThat(liquidation(fullReference.state(), planned.liquidationId()).deficitUnits()).isZero();
                assertThat(deficit(fullReference.state())).isZero();
                assertThat(liquidation(fullReference.state(), planned.liquidationId()).status())
                        .isEqualTo(CoreLiquidationState.Status.COMPLETED);
                assertThat(fullReferenceResult.patches()).anyMatch(patch ->
                        patch.terminalIds().liquidationIds().contains(planned.liquidationId()));
                assertPosting(fullReferenceResult,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.DEFICIT, deficit);
                assertFundsPostingConservation(fullReferenceResult);
                assertThat(economicEquityUsdt(fullReference.state()))
                        .isEqualTo(Math.addExact(economicBeforeInsurance, deficit));
                byte[] afterFullInsurance = fullReference.state().snapshot(713);
                SurprisingClusteredService restoredAfterFull = restoredService(afterFullInsurance);
                try {
                    assertBatchRecoveryParity(restoredAfterFull.state(), fullReference.state());
                    assertDuplicateClusterReplay(fullReference, restoredAfterFull, fullInsurance.getLast());
                } finally {
                    restoredAfterFull.state().close();
                }
            } finally {
                fullReference.state().close();
                fullRecovered.state().close();
            }
        } finally {
            uninterrupted.state().close();
        }
    }

    private static CoreProbeState seededLinearPerpetual() {
        CoreProbeState state = new CoreProbeState(ProductLine.LINEAR_PERPETUAL);
        apply(state, operationsCommand(1, CoreMessageType.UPSERT_INSTRUMENT,
                TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand(
                        "BTC-USDT", 1, ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                        1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0))));
        apply(state, kafkaCommand(1, CoreMessageType.APPLY_MARK_PRICE,
                TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1, 1_000))));
        apply(state, command(1, 11, CoreMessageType.ADJUST_BALANCE, balance(2_000)));
        apply(state, command(2, 22, CoreMessageType.ADJUST_BALANCE, balance(2_000)));
        apply(state, command(3, 11, CoreMessageType.PLACE_ORDER,
                place(101, CoreOrderSide.SELL, 100, 10, false)));
        return state;
    }

    private static CoreProbeState seededPerpetualBatchState() {
        CoreProbeState state = new CoreProbeState(ProductLine.LINEAR_PERPETUAL);
        apply(state, operationsCommand(1, CoreMessageType.UPSERT_INSTRUMENT,
                TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand(
                        "BTC-USDT", 1, ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                        1, 1, 1, 100_000, 50_000, 10_000, 20_000, 0, -1, 0))));
        apply(state, kafkaCommand(1, CoreMessageType.APPLY_MARK_PRICE,
                TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1, 1_000))));
        apply(state, command(1, 2001, CoreMessageType.ADJUST_BALANCE, balance(2_000)));
        apply(state, command(2, 1001, CoreMessageType.ADJUST_BALANCE, balance(2_000)));
        apply(state, command(3, 2001, CoreMessageType.PLACE_ORDER,
                TradingCommandCodec.encodePlaceOrder(
                        batchPlace(81_001, "paired-maker-first", CoreOrderSide.SELL, 100, 1))));
        apply(state, command(4, 2001, CoreMessageType.PLACE_ORDER,
                TradingCommandCodec.encodePlaceOrder(
                        batchPlace(81_002, "paired-maker-second", CoreOrderSide.SELL, 100, 1))));
        return state;
    }

    private static long economicUsdt(com.surprising.aeron.service.state.TradingCoreState state) {
        long total = 0;
        for (var user : state.users().values()) {
            var balance = user.balances().get("USDT");
            if (balance != null) total = Math.addExact(total,
                    Math.addExact(balance.availableUnits(), balance.lockedUnits()));
        }
        var treasury = state.treasuryState();
        total = Math.addExact(total, treasury.feeBalances().getOrDefault("USDT", 0L));
        total = Math.addExact(total, treasury.insuranceBalances().getOrDefault("USDT", 0L));
        total = Math.subtractExact(total, treasury.insuranceDeficits().getOrDefault("USDT", 0L));
        total = Math.addExact(total, treasury.liquidationFeeBalances().getOrDefault("USDT", 0L));
        total = Math.addExact(total, treasury.fundingResidualBalances().getOrDefault("USDT", 0L));
        total = Math.addExact(total, treasury.roundingResidualBalances().getOrDefault("USDT", 0L));
        return total;
    }

    private static byte[] balance(long amount) {
        return balance("USDT", amount);
    }

    private static byte[] balance(String asset, long amount) {
        return TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, amount));
    }

    private static byte[] place(long orderId, CoreOrderSide side, long price, long quantity,
                                boolean reduceOnly) {
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "BTC-USDT", 1,
                side, price, quantity, reduceOnly, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "recovery-" + orderId));
    }

    private static PlaceOrderCommand batchPlace(long orderId, String clientOrderId, CoreOrderSide side,
                                                long price, long quantity) {
        return new PlaceOrderCommand(orderId, "BTC-USDT", 1, side, price, quantity, false,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, clientOrderId);
    }

    private static CoreMessage command(long sourceSequence, long userId, CoreMessageType type, byte[] payload) {
        return sourcedCommand(CommandSource.GATEWAY, 77, sourceSequence, userId, type, payload);
    }

    private static CoreMessage operationsCommand(long sourceSequence, CoreMessageType type, byte[] payload) {
        return sourcedCommand(CommandSource.OPERATIONS, 88, sourceSequence, 0, type, payload);
    }

    private static CoreMessage kafkaCommand(long sourceSequence, CoreMessageType type, byte[] payload) {
        return sourcedCommand(CommandSource.KAFKA_INPUT_BRIDGE, 89, sourceSequence, 0, type, payload);
    }

    private static CoreMessage sourcedCommand(CommandSource source, long sourceId, long sourceSequence,
                                               long userId, CoreMessageType type, byte[] payload) {
        UUID commandId = UUID.nameUUIDFromBytes((source.name() + ':' + sourceSequence + ':' + userId + ':' + type)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new CoreMessage(CoreMessageHeader.command(type, commandId, ProductLine.LINEAR_PERPETUAL,
                source, sourceId, sourceSequence, userId, 1_000 + sourceSequence, sourceSequence), payload);
    }

    private static List<String> encodedV10OutboxFacts(CoreProbeState state) {
        return state.exportState().snapshot().pendingEvents().stream()
                .map(CoreMessageCodec::encode).map(HexFormat.of()::formatHex).toList();
    }

    private static ResponseView response(CoreResponse response) {
        return new ResponseView(response.status(), response.commandStatus(), response.resultCode(),
                response.appliedCommandCount(), response.requiredExportSequence(), response.stateHash(),
                HexFormat.of().formatHex(response.data()));
    }

    private static CoreResponse apply(CoreProbeState state, CoreMessage command) {
        CoreResponse response = state.apply(command);
        if (response.resultCode() != CoreResultCode.MATCHING_PENDING) {
            assertThat(response.status()).as(response.resultCode().name())
                    .isIn(ResponseStatus.APPLIED, ResponseStatus.DUPLICATE);
            return response;
        }
        long sequence = state.matchingSequence(command.header().commandId());
        com.surprising.aeron.service.matching.CoreMatchingResult matching = null;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (matching == null && System.nanoTime() < deadline) {
            matching = state.takeMatchingResult(sequence);
            if (matching == null) Thread.onSpinWait();
        }
        assertThat(matching).as("matching completion for " + command.header().messageType()).isNotNull();
        CoreResponse completed = null;
        deadline = System.nanoTime() + 5_000_000_000L;
        while (completed == null && System.nanoTime() < deadline) {
            completed = state.completeMatching(sequence, matching,
                    command.header().submittedAtEpochMillis(), command.header().sourceSequence());
            if (completed == null) Thread.onSpinWait();
        }
        assertThat(completed).isNotNull();
        assertThat(completed.status()).isEqualTo(ResponseStatus.APPLIED);
        return completed;
    }

    private static ReplayResult liquidate(CoreProbeState state) {
        state.captureCommittedPatchesForTest();
        ArrayList<ResponseView> responses = new ArrayList<>();
        List<CoreMessage> setup = List.of(
                command(12, 44, CoreMessageType.ADJUST_BALANCE, balance(180)),
                command(13, 55, CoreMessageType.ADJUST_BALANCE, balance(180)),
                command(14, 44, CoreMessageType.PLACE_ORDER, place(501, CoreOrderSide.SELL, 100, 10, false)),
                command(15, 55, CoreMessageType.PLACE_ORDER, place(502, CoreOrderSide.BUY, 100, 10, false)),
                kafkaCommand(2, CoreMessageType.APPLY_MARK_PRICE,
                        TradingCommandCodec.encodeApplyMarkPrice(
                                new ApplyMarkPriceCommand("BTC-USDT", 1, 80, 2, 2_000))));
        setup.stream().map(command -> response(apply(state, command))).forEach(responses::add);

        long operationsSequence = 3;
        CoreLiquidationWorkView work = liquidationWork(state);
        while (work.riskScanPending()) {
            CoreMessage continuation = operationsCommand(operationsSequence++, CoreMessageType.CONTINUE_RISK_SCAN,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(1)));
            responses.add(response(apply(state, continuation)));
            work = liquidationWork(state);
        }
        var action = work.actions().stream().filter(value -> value.userId() == 55).findFirst().orElseThrow();
        CoreMessage execution = operationsCommand(operationsSequence, CoreMessageType.EXECUTE_LIQUIDATION,
                TradingCommandCodec.encodeExecuteLiquidation(new ExecuteLiquidationCommand(
                        action.liquidationId(), action.triggerPriceSequence(), action.markPriceTicks(), 100_000)));
        responses.add(response(apply(state, execution)));
        List<PatchEvidence> patches = state.drainCapturedFactFramesForTest().stream()
                .map(PatchEvidence::from).toList();
        return new ReplayResult(List.copyOf(responses), patches);
    }

    private static CoreLiquidationWorkView liquidationWork(CoreProbeState state) {
        CoreMessage query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.LIQUIDATION_WORK_QUERY,
                UUID.nameUUIDFromBytes("recovery-liquidation-work".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 77, 0, 0, 3_000, 0),
                CoreLiquidationWorkCodec.encodeQuery(ProductLine.LINEAR_PERPETUAL,
                        CoreLiquidationWorkView.Purpose.EXECUTION, 0, 100, 1_048_576));
        CoreResponse response = state.apply(query);
        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        return CoreLiquidationWorkCodec.decodeWork(response.data());
    }

    private static void assertParity(CoreProbeState actualState, CoreProbeState expectedState,
                                     ReplayResult actual, ReplayResult expected) {
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.patches()).isNotEmpty();
        assertThat(actual.patches()).anyMatch(patch -> !patch.fundsPostings().isEmpty());
        assertThat(actual.patches()).allMatch(patch -> patch.coreFactFragment() != null);
        assertThat(actualState.tradingState()).isEqualTo(expectedState.tradingState());
        assertThat(actualState.tradingState().users()).isEqualTo(expectedState.tradingState().users());
        assertThat(actualState.tradingState().orders()).isEqualTo(expectedState.tradingState().orders());
        assertThat(actualState.tradingState().riskState()).isEqualTo(expectedState.tradingState().riskState());
        assertThat(actualState.tradingState().treasuryState())
                .isEqualTo(expectedState.tradingState().treasuryState());
        assertThat(actualState.stateHash()).isEqualTo(expectedState.stateHash());
        assertThat(actualState.snapshotBusinessStateHash()).isEqualTo(expectedState.snapshotBusinessStateHash());
        assertThat(actualState.snapshotFundsStateHash()).isEqualTo(expectedState.snapshotFundsStateHash());
        assertThat(actualState.snapshotProjectionSequence()).isEqualTo(expectedState.snapshotProjectionSequence());
        assertThat(actualState.exportState().nextSequence()).isEqualTo(expectedState.exportState().nextSequence());
        assertThat(actualState.commandResults()).isEqualTo(expectedState.commandResults());
        assertThat(actualState.lastSourceSequences()).isEqualTo(expectedState.lastSourceSequences());
        assertThat(actualState.feePolicies()).isEqualTo(expectedState.feePolicies());
        assertThat(actualState.pendingTransfers()).isEqualTo(expectedState.pendingTransfers());
        assertThat(actualState.exportState().snapshot()).isEqualTo(expectedState.exportState().snapshot());
        assertThat(encodedV10OutboxFacts(actualState)).isEqualTo(encodedV10OutboxFacts(expectedState));
    }

    private static BatchReplay completeBatch(SurprisingClusteredService service, CoreMessage batch) {
        CoreProbeState state = service.state();
        state.captureCommittedPatchesForTest();
        ClusterReplay replay = replayClusterCommand(service, batch);
        assertThat(replay.responses()).hasSize(1);
        assertThat(state.matchingSequence(batch.header().commandId())).isZero();
        CoreResponse response = replay.response();
        assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
        List<PatchEvidence> patches = state.drainCapturedFactFramesForTest().stream()
                .map(PatchEvidence::from).toList();
        return new BatchReplay(response(response), patches, encodedV10OutboxFacts(state),
                state.exportState().snapshot().acknowledgedSequence());
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

    private static SurprisingClusteredService restoredService(byte[] snapshot) {
        SurprisingClusteredService service = new SurprisingClusteredService(ProductLine.LINEAR_PERPETUAL);
        service.onStart(cluster(), null);
        service.restoreSnapshot(snapshot);
        return service;
    }

    private static Cluster cluster() {
        return (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "logPosition" -> 7L;
                    case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                    case "timeUnit" -> TimeUnit.MILLISECONDS;
                    case "time" -> 1_000L;
                    case "scheduleTimer" -> true;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    private static ClusterReplay replayClusterCommand(SurprisingClusteredService service, CoreMessage command) {
        ClusterReplay replay = replayClusterCommandRaw(service, command);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (replay.responses().isEmpty() && System.nanoTime() < deadline) {
            if (service.doBackgroundWork(System.nanoTime()) == 0) Thread.onSpinWait();
        }
        assertThat(replay.responses()).isNotEmpty();
        return replay;
    }

    private static ClusterReplay replayClusterCommandRaw(
            SurprisingClusteredService service, CoreMessage command) {
        List<byte[]> responses = new ArrayList<>();
        byte[] encoded = CoreMessageCodec.encode(command);
        service.onSessionMessage(clusterClient(responses), command.header().submittedAtEpochMillis(),
                new UnsafeBuffer(encoded), 0, encoded.length, aeronHeader());
        return new ClusterReplay(responses);
    }

    private static ReplayResult replayClustered(
            SurprisingClusteredService service, List<CoreMessage> commands) {
        CoreProbeState state = service.state();
        state.captureCommittedPatchesForTest();
        ArrayList<ResponseView> responses = new ArrayList<>();
        for (CoreMessage command : commands) {
            ClusterReplay replay = replayClusterCommand(service, command);
            CoreResponse completed = replay.response();
            assertThat(completed.status()).as(completed.resultCode().name())
                    .isIn(ResponseStatus.APPLIED, ResponseStatus.DUPLICATE);
            responses.add(response(completed));
        }
        List<PatchEvidence> patches = state.drainCapturedFactFramesForTest().stream()
                .map(PatchEvidence::from).toList();
        return new ReplayResult(List.copyOf(responses), patches);
    }

    private static CoreLiquidationState liquidation(CoreProbeState state, long liquidationId) {
        return java.util.Objects.requireNonNull(
                state.tradingState().riskState().liquidations().get(liquidationId), "liquidation");
    }

    private static long deficit(CoreProbeState state) {
        return state.tradingState().treasuryState().insuranceDeficits().getOrDefault("USDT", 0L);
    }

    private static void assertPosting(ReplayResult replay,
                                      com.surprising.aeron.service.state.FundsPosting.Subledger subledger,
                                      long expectedUnits) {
        long units = replay.patches().stream().flatMap(patch -> patch.fundsPostings().stream())
                .filter(posting -> posting.subledger() == subledger)
                .mapToLong(RuntimeFactFrame.FundsPosting::units).sum();
        assertThat(units).as(subledger.name()).isEqualTo(expectedUnits);
    }

    private static void assertFundsPostingConservation(ReplayResult replay) {
        assertThat(replay.patches().stream()
                .filter(patch -> !patch.coreFactMetadata().externalAdjustment())
                .flatMap(patch -> patch.fundsPostings().stream())
                .collect(java.util.stream.Collectors.groupingBy(
                        RuntimeFactFrame.FundsPosting::assetId,
                        java.util.stream.Collectors.summingLong(RuntimeFactFrame.FundsPosting::units))))
                .allSatisfy((assetId, units) -> assertThat(units).as("assetId=" + assetId).isZero());
    }

    private static long economicEquityUsdt(CoreProbeState state) {
        var core = state.tradingState();
        long total = economicUsdt(core);
        long unrealized = 0;
        for (var user : core.users().values()) {
            for (var position : user.positions().values()) {
                var instrument = core.instruments().get(position.symbol());
                var mark = core.riskState().markPrices().get(position.symbol());
                if (position.signedQuantitySteps() != 0 && mark != null
                        && instrument.settleAsset().equals("USDT")) {
                    unrealized = Math.addExact(unrealized, Math.multiplyExact(
                            position.signedQuantitySteps(),
                            Math.subtractExact(mark.markPriceTicks(), position.entryPriceTicks())));
                }
            }
        }
        return Math.addExact(total, unrealized);
    }

    private static void assertDuplicateClusterReplay(
            SurprisingClusteredService reference, SurprisingClusteredService restored,
            CoreMessage command) throws Exception {
        long projectionBefore = reference.state().snapshotProjectionSequence();
        long exportBefore = reference.state().exportState().nextSequence();
        List<String> factsBefore = encodedV10OutboxFacts(reference.state());
        ReplayResult referenceDuplicate = replayClustered(reference, List.of(command));
        ReplayResult restoredDuplicate = replayClustered(restored, List.of(command));
        assertThat(restoredDuplicate).isEqualTo(referenceDuplicate);
        assertThat(referenceDuplicate.responses()).allMatch(value -> value.status() == ResponseStatus.DUPLICATE);
        assertThat(referenceDuplicate.patches()).isEmpty();
        assertThat(reference.state().snapshotProjectionSequence()).isEqualTo(projectionBefore);
        assertThat(reference.state().exportState().nextSequence()).isEqualTo(exportBefore);
        assertThat(encodedV10OutboxFacts(reference.state())).isEqualTo(factsBefore);
        assertBatchRecoveryParity(restored.state(), reference.state());
    }

    private static ClientSession clusterClient(List<byte[]> responses) {
        return (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "id" -> 91L;
                    case "isClosing" -> false;
                    case "offer" -> {
                        var buffer = (org.agrona.DirectBuffer) arguments[0];
                        int offset = (int) arguments[1];
                        int length = (int) arguments[2];
                        byte[] response = new byte[length];
                        buffer.getBytes(offset, response);
                        responses.add(response);
                        yield 1L;
                    }
                    default -> primitiveDefault(method.getReturnType());
                });
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    private static Header aeronHeader() {
        return new Header(0, 0).buffer(new UnsafeBuffer(new byte[64])).offset(0)
                .initialTermId(0).positionBitsToShift(16);
    }

    private static RuntimeFinancialView runtimeFinancialView(
            com.surprising.aeron.service.state.TradingRuntimeState runtime, int assetId) {
        var buyer = runtime.balance(1001, assetId);
        var maker = runtime.balance(2001, assetId);
        var treasury = runtime.treasury();
        long treasuryUnits = treasury.fee(assetId) + treasury.insurance(assetId)
                - treasury.insuranceDeficit(assetId) + treasury.liquidationFee(assetId)
                + treasury.fundingResidual(assetId) + treasury.roundingResidual(assetId)
                + treasury.clearingPnl(assetId);
        long economicUnits = buyer.availableUnits() + buyer.lockedUnits()
                + maker.availableUnits() + maker.lockedUnits() + treasuryUnits;
        return new RuntimeFinancialView(
                new RuntimeBalanceView(buyer.availableUnits(), buyer.lockedUnits()),
                new RuntimeBalanceView(maker.availableUnits(), maker.lockedUnits()),
                treasury.fee(assetId), treasuryUnits, economicUnits);
    }

    private static void assertBatchRecoveryParity(CoreProbeState recovered, CoreProbeState reference)
            throws Exception {
        assertThat(recovered.tradingState()).isEqualTo(reference.tradingState());
        assertThat(recovered.tradingState().users()).isEqualTo(reference.tradingState().users());
        assertThat(recovered.tradingState().orders()).isEqualTo(reference.tradingState().orders());
        assertThat(recovered.tradingState().clientOrderIndex())
                .isEqualTo(reference.tradingState().clientOrderIndex());
        var recoveredIdentities = (com.surprising.aeron.service.state.RuntimeIdentityRegistry)
                field(recovered, "runtimePlaceOrderIdentities");
        var referenceIdentities = (com.surprising.aeron.service.state.RuntimeIdentityRegistry)
                field(reference, "runtimePlaceOrderIdentities");
        assertThat(recoveredIdentities.snapshot()).isEqualTo(referenceIdentities.snapshot());
        assertThat(recovered.tradingState().riskState()).isEqualTo(reference.tradingState().riskState());
        assertThat(recovered.tradingState().treasuryState()).isEqualTo(reference.tradingState().treasuryState());
        assertThat(recovered.snapshotBusinessStateHash()).isEqualTo(reference.snapshotBusinessStateHash());
        assertThat(recovered.snapshotFundsStateHash()).isEqualTo(reference.snapshotFundsStateHash());
        assertThat(recovered.stateHash()).isEqualTo(reference.stateHash());
        assertThat(recovered.commandResults()).isEqualTo(reference.commandResults());
        assertThat(recovered.lastSourceSequences()).isEqualTo(reference.lastSourceSequences());
        assertThat(recovered.exportState().snapshot()).isEqualTo(reference.exportState().snapshot());
        assertThat(encodedV10OutboxFacts(recovered)).isEqualTo(encodedV10OutboxFacts(reference));
        long fence = recovered.appliedCommandCount();
        var recoveredLanes = recovered.accountLaneSnapshots(fence, recovered.tradingState());
        assertThat(recoveredLanes).isEqualTo(reference.accountLaneSnapshots(fence, reference.tradingState()));
        assertThat(recoveredLanes).allSatisfy(lane -> {
            assertThat(lane.appliedSequence()).isEqualTo(lane.committedSequence());
            assertThat(lane.committedSequence()).isEqualTo(fence);
        });
        assertNoTransientCommitReservations(recovered);
        assertNoTransientCommitReservations(reference);
        assertThat(allIndexSnapshots(recovered)).isEqualTo(allIndexSnapshots(reference));
        assertIndexesEqualCanonicalRebuild(recovered);
        assertIndexesEqualCanonicalRebuild(reference);
        assertThat((long[]) field(recovered, "appliedMatcherSequences"))
                .containsExactly((long[]) field(reference, "appliedMatcherSequences"));
        assertThat((long[]) field(recovered, "appliedMatcherPrefixDigests"))
                .containsExactly((long[]) field(reference, "appliedMatcherPrefixDigests"));
    }

    private static void assertNoTransientCommitReservations(CoreProbeState state) throws Exception {
        assertThat(field(state, "currentAdmission")).isNull();
        var journal = (com.surprising.aeron.service.state.RuntimeCommitJournal)
                field(state, "runtimeProjectionJournal");
        assertThat(journal.metrics().reservedEntries()).isZero();
        assertThat(journal.metrics().reservedBytes()).isZero();
        assertThat(state.exportState().metrics().reservedEvents()).isZero();
        assertThat(state.exportState().metrics().reservedBytes()).isZero();
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
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

    private static void assertIndexesEqualCanonicalRebuild(CoreProbeState state) throws Exception {
        var core = state.tradingState();
        var identities = (com.surprising.aeron.service.state.RuntimeIdentityRegistry)
                field(state, "runtimePlaceOrderIdentities");
        Map<String, Map<String, Object>> rebuilt = Map.ofEntries(
                Map.entry("position-user", indexSnapshot(
                        new com.surprising.aeron.service.state.PositionUserIndex(core, identities))),
                Map.entry("open-interest", indexSnapshot(
                        new com.surprising.aeron.service.state.OpenInterestIndex(core, identities))),
                Map.entry("trigger", indexSnapshot(
                        new com.surprising.aeron.service.state.TriggerOrderIndex(core))),
                Map.entry("algo", indexSnapshot(
                        new com.surprising.aeron.service.state.AlgoOrderIndex(core))),
                Map.entry("liquidation", indexSnapshot(
                        new com.surprising.aeron.service.state.LiquidationIndex(core))),
                Map.entry("timer", indexSnapshot(
                        new com.surprising.aeron.service.state.CancelAllAfterIndex(core))),
                Map.entry("active-order", indexSnapshot(
                        new com.surprising.aeron.service.state.ActiveOrderIndex(core, identities))),
                Map.entry("adl-position", indexSnapshot(
                        new com.surprising.aeron.service.state.AdlPositionIndex(core, identities))),
                Map.entry("risk-snapshot", indexSnapshot(
                        new com.surprising.aeron.service.state.RiskSnapshotIndex(core))));
        assertThat(allIndexSnapshots(state)).isEqualTo(rebuilt);
    }

    private static Map<String, Object> indexSnapshot(Object index) throws Exception {
        java.util.TreeMap<String, Object> snapshot = new java.util.TreeMap<>();
        for (var value : index.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(value.getModifiers()) || value.getName().equals("identities")) continue;
            value.setAccessible(true);
            snapshot.put(value.getName(), canonicalIndexValue(value.get(index)));
        }
        return Map.copyOf(snapshot);
    }

    private static Object canonicalIndexValue(Object value) {
        if (value == null) return "<null>";
        if (value instanceof Map<?, ?> map) {
            java.util.TreeMap<String, Object> copy = new java.util.TreeMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), canonicalIndexValue(nested)));
            return Map.copyOf(copy);
        }
        if (value instanceof java.util.Set<?> set) {
            return set.stream().map(RuntimeCommitRecoveryTest::canonicalIndexValue)
                    .map(String::valueOf).sorted().toList();
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.ArrayList<Object> copy = new java.util.ArrayList<>();
            iterable.forEach(nested -> copy.add(canonicalIndexValue(nested)));
            return List.copyOf(copy);
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            java.util.ArrayList<Object> copy = new java.util.ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                copy.add(canonicalIndexValue(java.lang.reflect.Array.get(value, index)));
            }
            return List.copyOf(copy);
        }
        if (value.getClass().getName().startsWith("org.eclipse.collections")) return value.toString();
        return value;
    }

    private static ReplayResult replay(CoreProbeState state, List<CoreMessage> commands) {
        state.captureCommittedPatchesForTest();
        List<ResponseView> responses = commands.stream().map(command -> response(apply(state, command))).toList();
        List<PatchEvidence> patches = state.drainCapturedFactFramesForTest().stream()
                .map(PatchEvidence::from).toList();
        return new ReplayResult(responses, patches);
    }

    private record ReplayResult(List<ResponseView> responses,
                                List<PatchEvidence> patches) {
    }

    private record BatchReplay(ResponseView response, List<PatchEvidence> patches,
                               List<String> encodedV10Facts, long acknowledgedSequence) {
    }

    private record ClusterReplay(List<byte[]> responses) {
        ClusterReplay {
            responses = java.util.Objects.requireNonNull(responses, "responses");
        }

        CoreResponse response() {
            assertThat(responses).isNotEmpty();
            CoreMessage message = CoreMessageCodec.decode(responses.getLast());
            return com.surprising.aeron.protocol.CoreProtocol.decodeResponse(message.payload());
        }
    }

    private record RuntimeBalanceView(long available, long locked) {
    }

    private record RuntimeFinancialView(RuntimeBalanceView buyerBalance,
                                        RuntimeBalanceView makerBalance,
                                        long treasuryFee,
                                        long treasuryUnits,
                                        long economicUnits) {
        long buyerAvailable() {
            return buyerBalance.available();
        }

        long buyerLocked() {
            return buyerBalance.locked();
        }

        long buyerTotal() {
            return buyerBalance.available() + buyerBalance.locked();
        }

        long makerLocked() {
            return makerBalance.locked();
        }

        long makerTotal() {
            return makerBalance.available() + makerBalance.locked();
        }
    }

    private record PatchEvidence(
            ProductLine productLine, long previousCoreSequence, long coreSequence,
            long previousProjectionSequence, long projectionSequence, long beforeRevision, long afterRevision,
            long beforeBusinessStateHash, long businessStateHash, long beforeFundsStateHash, long fundsStateHash,
            long laneMask,
            List<RuntimeFactFrame.AccountLaneOwnerGroup> accountLaneGroups,
            RuntimeFactFrame.GlobalOwnerGroup globalOwnerGroup,
            List<RuntimeFactFrame.FundsPosting> fundsPostings,
            com.surprising.aeron.protocol.CoreMatcherTransition matcherTransition,
            List<RuntimeFactFrame.MatcherEvidence> matcherEvidence, RuntimeFactFrame.TerminalIds terminalIds,
            RuntimeFactFrame.CoreFactValues coreFactValues,
            RuntimeFactFrame.CoreFactMetadata coreFactMetadata,
            RuntimeFactFrame.CoreFactFragment coreFactFragment) {

        static PatchEvidence from(RuntimeFactFrame patch) {
            return new PatchEvidence(patch.productLine(), patch.previousCoreSequence(), patch.coreSequence(),
                    patch.previousProjectionSequence(), patch.projectionSequence(), patch.beforeRevision(),
                    patch.afterRevision(), patch.beforeBusinessStateHash(), patch.businessStateHash(),
                    patch.beforeFundsStateHash(), patch.fundsStateHash(), patch.laneMask(),
                    patch.accountLaneGroups(), patch.globalOwnerGroup(), patch.fundsPostings(),
                    patch.matcherTransition(), patch.matcherEvidence(), patch.terminalIds(), patch.coreFactValues(),
                    patch.coreFactMetadata(), patch.materializeCoreFactFragment());
        }
    }

    private record ResponseView(ResponseStatus status, ResponseStatus commandStatus, CoreResultCode resultCode,
                                long appliedCommandCount, long requiredExportSequence, long stateHash, String data) {
    }

    private static byte[] mutateLongInSection(byte[] source, int sectionId, int payloadOffset, long delta) {
        byte[] mutated = source.clone();
        ByteBuffer buffer = ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(SectionedCoreSnapshotCodec.ENVELOPE_LENGTH);
        while (buffer.hasRemaining()) {
            int id = buffer.getInt();
            int length = buffer.getInt();
            int offset = buffer.position();
            if (id == sectionId) {
                buffer.putLong(offset + payloadOffset, Math.addExact(buffer.getLong(offset + payloadOffset), delta));
                rewriteChecksum(mutated);
                return mutated;
            }
            buffer.position(offset + length);
        }
        throw new AssertionError("snapshot section not found: " + sectionId);
    }

    private static byte[] mutateLaneUserPreservingDigest(byte[] source, LaneTopology topology) {
        byte[] mutated = source.clone();
        ByteBuffer buffer = ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(SectionedCoreSnapshotCodec.ENVELOPE_LENGTH);
        boolean changed = false;
        while (buffer.position() < mutated.length
                - SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH - SectionedCoreSnapshotCodec.FOOTER_LENGTH) {
            int sectionId = buffer.getInt();
            int length = buffer.getInt();
            int payloadOffset = buffer.position();
            if (!changed && sectionId >= 10) {
                int laneId = buffer.getInt(payloadOffset);
                int userCount = buffer.getInt(payloadOffset + Integer.BYTES + Long.BYTES * 5);
                if (userCount > 0) {
                    int lastUserOffset = payloadOffset + Integer.BYTES * 2 + Long.BYTES * (5 + userCount - 1);
                    long previousUser = userCount == 1 ? 0 : buffer.getLong(lastUserOffset - Long.BYTES);
                    long originalUser = buffer.getLong(lastUserOffset);
                    long replacement = Math.max(originalUser + 1, previousUser + 1);
                    while (topology.accountLaneId(replacement) != laneId || replacement == 11 || replacement == 22) {
                        replacement++;
                    }
                    buffer.putLong(lastUserOffset, replacement);
                    changed = true;
                }
            }
            buffer.position(payloadOffset + length);
        }
        if (!changed) throw new AssertionError("snapshot contains no account lane user");
        long digest = rawAccountLaneDigest(mutated);
        buffer.position(SectionedCoreSnapshotCodec.ENVELOPE_LENGTH);
        while (buffer.hasRemaining()) {
            int sectionId = buffer.getInt();
            int length = buffer.getInt();
            int payloadOffset = buffer.position();
            if (sectionId == 1) {
                buffer.putLong(payloadOffset + 106, digest);
                rewriteChecksum(mutated);
                return mutated;
            }
            buffer.position(payloadOffset + length);
        }
        throw new AssertionError("snapshot header section not found");
    }

    private static long rawAccountLaneDigest(byte[] snapshot) {
        ByteBuffer buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(SectionedCoreSnapshotCodec.ENVELOPE_LENGTH);
        long hash = 0xcbf29ce484222325L;
        while (buffer.position() < snapshot.length
                - SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH - SectionedCoreSnapshotCodec.FOOTER_LENGTH) {
            int sectionId = buffer.getInt();
            int length = buffer.getInt();
            int payloadOffset = buffer.position();
            if (sectionId >= 10) {
                hash = mix(hash, buffer.getInt());
                for (int index = 0; index < 5; index++) hash = mix(hash, buffer.getLong());
                int userCount = buffer.getInt();
                for (int index = 0; index < userCount; index++) hash = mix(hash, buffer.getLong());
            }
            buffer.position(payloadOffset + length);
        }
        return hash;
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private static long projectorThreadCount() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread.getName().startsWith("core-commit-projector-"))
                .count();
    }

    private static void rewriteChecksum(byte[] snapshot) {
        CRC32C checksum = new CRC32C();
        checksum.update(snapshot, 0,
                snapshot.length - SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH
                        - SectionedCoreSnapshotCodec.FOOTER_LENGTH);
        ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(snapshot.length - Long.BYTES, checksum.getValue());
    }
}
