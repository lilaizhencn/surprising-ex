package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMatcherTransition;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class RuntimeCommitJournalTest {

    @Test
    void byteLimitSplitsBatchAndReclaimsExactBacklogBytes() throws Exception {
        String batchProperty = "surprising.aeron.projection-batch-size";
        String bytesProperty = "surprising.aeron.projection-batch-bytes";
        String previousBatch = System.getProperty(batchProperty);
        String previousBytes = System.getProperty(bytesProperty);
        System.setProperty(batchProperty, "4");
        System.setProperty(bytesProperty, "500");
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (RuntimeCommitJournal journal = journal(initial)) {
            journal.blockProjectorForTest(entered, release);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            for (long sequence = 1; sequence <= 3; sequence++) {
                RuntimeCommitPatch patch = emptyPatch(initial, sequence);
                journal.publish(patch, patch.businessStateHash(), patch.fundsStateHash());
            }
            assertThat(journal.metrics().currentBacklogBytes()).isPositive();
            journal.requestProjection(3);
            release.countDown();
            journal.await(3, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), true);
            assertThat(journal.metrics().batchCount()).isEqualTo(3);
            assertThat(journal.metrics().batchItems()).isEqualTo(3);
            assertThat(journal.metrics().currentBacklogBytes()).isZero();
            assertThat(journal.metrics().reservedBytes()).isZero();
        } finally {
            release.countDown();
            restoreProperty(batchProperty, previousBatch);
            restoreProperty(bytesProperty, previousBytes);
        }
    }

    @Test
    void completelyFullRingRejectsReservationBeforePublication() throws Exception {
        String capacityProperty = "surprising.aeron.commit-journal-capacity";
        String batchProperty = "surprising.aeron.projection-batch-size";
        String previousCapacity = System.getProperty(capacityProperty);
        String previousBatch = System.getProperty(batchProperty);
        System.setProperty(capacityProperty, "1024");
        System.setProperty(batchProperty, "1024");
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (RuntimeCommitJournal journal = journal(initial)) {
            journal.blockProjectorForTest(entered, release);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            for (long sequence = 1; sequence <= 1_024; sequence++) {
                RuntimeCommitPatch patch = emptyPatch(initial, sequence);
                journal.publish(patch, patch.businessStateHash(), patch.fundsStateHash());
            }
            assertThat(journal.metrics().currentBacklog()).isEqualTo(1_024);
            assertThatThrownBy(() -> journal.reserveAdmission(1))
                    .isInstanceOf(CoreStateRejectedException.class)
                    .hasMessageContaining("commit journal backlog is full");
            assertThat(journal.publishedSequence()).isEqualTo(1_024);
            release.countDown();
            journal.await(1_024, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), true);
            assertThat(journal.metrics().currentBacklog()).isZero();
        } finally {
            release.countDown();
            restoreProperty(capacityProperty, previousCapacity);
            restoreProperty(batchProperty, previousBatch);
        }
    }

    @Test
    void slowProjectorRecordsMaximumAndZeroEndBacklog() throws Exception {
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RuntimeCommitJournal journal = journal(initial);
        try {
            journal.blockProjectorForTest(entered, release);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            for (long sequence = 1; sequence <= 64; sequence++) {
                RuntimeCommitPatch patch = emptyPatch(initial, sequence);
                journal.publish(patch, patch.businessStateHash(), patch.fundsStateHash());
            }
            assertThat(journal.metrics().currentBacklog()).isEqualTo(64);
            assertThat(journal.metrics().maxBacklog()).isEqualTo(64);
            release.countDown();
            journal.await(64, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), true);
            journal.close();
            assertThat(journal.metrics().endBacklog()).isZero();
        } finally {
            release.countDown();
            if (journal.projectorAlive()) journal.close();
        }
    }

    @Test
    void interruptedProjectionWaiterTerminatesWithoutConsumingFence() throws Exception {
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        CountDownLatch enteredProjector = new CountDownLatch(1);
        CountDownLatch releaseProjector = new CountDownLatch(1);
        CountDownLatch enteredProjectionWait = new CountDownLatch(1);
        RuntimeCommitJournal journal = journal(initial);
        Thread waiter = null;
        try {
            journal.blockProjectorForTest(enteredProjector, releaseProjector);
            assertThat(enteredProjector.await(1, TimeUnit.SECONDS)).isTrue();
            RuntimeCommitPatch patch = emptyPatch(initial, 1);
            journal.publish(patch, patch.businessStateHash(), patch.fundsStateHash());
            journal.signalProjectionWaiterForTest(enteredProjectionWait);
            AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
            waiter = Thread.ofPlatform().start(() -> {
                try {
                    journal.await(1, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), false);
                } catch (Throwable failure) {
                    waiterFailure.set(failure);
                }
            });
            assertThat(enteredProjectionWait.await(1, TimeUnit.SECONDS)).isTrue();
            waiter.interrupt();
            waiter.join(1_000);
            assertThat(waiter.isAlive()).isFalse();
            assertThat(waiterFailure.get()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("projection fence wait was interrupted");
            assertThat(journal.projectedSequence()).isZero();
            releaseProjector.countDown();
            journal.await(1, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), true);
        } finally {
            releaseProjector.countDown();
            if (waiter != null) {
                waiter.interrupt();
                waiter.join(1_000);
            }
            journal.close();
        }
    }

    @Test
    void boundedBatchesDrainAcrossWrap() {
        String capacityProperty = "surprising.aeron.commit-journal-capacity";
        String batchProperty = "surprising.aeron.projection-batch-size";
        String previousCapacity = System.getProperty(capacityProperty);
        String previousBatch = System.getProperty(batchProperty);
        System.setProperty(capacityProperty, "1024");
        System.setProperty(batchProperty, "32");
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        try (RuntimeCommitJournal journal = journal(initial)) {
            for (long sequence = 1; sequence <= 1_050; sequence++) {
                RuntimeCommitPatch patch = emptyPatch(initial, sequence);
                journal.publish(patch, patch.businessStateHash(), patch.fundsStateHash());
                if (sequence % 128 == 0) {
                    journal.await(sequence, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), true);
                }
            }
            journal.await(1_050, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), true);
            assertThat(journal.projectedSequence()).isEqualTo(1_050);
            assertThat(journal.lag()).isZero();
            assertThat(journal.metrics().maxBacklog()).isLessThanOrEqualTo(1_024);
            assertThat(journal.metrics().batchCount()).isPositive();
            assertThat(journal.metrics().batchItems()).isEqualTo(1_050);
        } finally {
            restoreProperty(capacityProperty, previousCapacity);
            restoreProperty(batchProperty, previousBatch);
        }
    }

    @Test
    void admissionReservationIsConsumedExactlyOnce() {
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        try (RuntimeCommitJournal journal = journal(initial)) {
            RuntimeCommitJournal.AdmissionReservation reservation = journal.reserveAdmission(2);
            assertThat(journal.metrics().reservedBytes()).isPositive();
            RuntimeCommitPatch first = emptyPatch(initial, 1);
            RuntimeCommitPatch second = emptyPatch(initial, 2);
            journal.publish(reservation, first, first.businessStateHash(), first.fundsStateHash());
            journal.publish(reservation, second, second.businessStateHash(), second.fundsStateHash());
            assertThat(reservation.remaining()).isZero();
            assertThatThrownBy(() -> journal.publish(reservation, emptyPatch(initial, 3),
                    initial.businessStateHash(), RollingFundsStateHash.compute(initial)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("consumed commit admission");
            journal.await(2, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), true);
            assertThat(journal.metrics().reservedEntries()).isZero();
            assertThat(journal.metrics().reservedBytes()).isZero();
        }
    }

    @Test
    void capacityPreflightIncludesReservedByteBudget() {
        String property = "surprising.aeron.commit-journal-capacity-bytes";
        String previous = System.getProperty(property);
        System.setProperty(property, Long.toString(RuntimeCommitJournal.maxReservedPatchBytes()));
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        try (RuntimeCommitJournal journal = journal(initial)) {
            RuntimeCommitJournal.AdmissionReservation reservation = journal.reserveAdmission(1);

            assertThat(journal.hasCapacityFor(1)).isFalse();

            journal.release(reservation);
            assertThat(journal.hasCapacityFor(1)).isTrue();
        } finally {
            restoreProperty(property, previous);
        }
    }

    @Test
    void admissionReservationRejectsPatchThatBorrowsLaterSliceBytes() {
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        RuntimeCommitPatch heavy = patchWithTerminalOrder(initial, 1);
        try (RuntimeCommitJournal journal = journal(initial)) {
            RuntimeCommitJournal.AdmissionReservation reservation = journal.reserveAdmission(2, 800);

            assertThatThrownBy(() -> journal.publish(reservation, heavy,
                    heavy.businessStateHash(), heavy.fundsStateHash()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("per-slice");
            assertThat(reservation.remaining()).isEqualTo(2);
            assertThat(journal.metrics().reservedEntries()).isEqualTo(2);
            assertThat(journal.metrics().reservedBytes()).isEqualTo(800);

            journal.release(reservation);
            assertThat(journal.metrics().reservedEntries()).isZero();
            assertThat(journal.metrics().reservedBytes()).isZero();
        }
    }

    @Test
    void appliesBatchAndFreezesOnlyAtRequestedFence() {
        String property = "surprising.aeron.projection-batch-size";
        String previousProperty = System.getProperty(property);
        System.setProperty(property, "2");
        TradingCoreState initial = mixedInitial();
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(initial, identities);
        try (RuntimeCommitJournal journal = journal(initial)) {
            List<RuntimeCommitPatch> patches = captureMixedPatches(runtime, identities, initial, 250, -100, 75);
            patches.forEach(patch -> journal.publish(patch, patch.businessStateHash(), patch.fundsStateHash()));

            awaitLag(journal, 0);
            assertThat(journal.projectedSequence()).isZero();
            assertThat(journal.projectionFreezeCount()).isZero();
            assertThat(patches.getFirst().projectionPoint().completed()).isTrue();
            assertThat(patches.getFirst().projectionPoint().projected()).isFalse();

            RuntimeCommitJournal.ProjectionVersion frozen = journal.await(
                    3, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), true);
            TradingCoreState oracle = RuntimeStateMaterializer.materialize(runtime, identities);
            assertThat(frozen.state()).isEqualTo(oracle);
            assertThat(frozen.businessStateHash()).isEqualTo(oracle.businessStateHash());
            assertThat(frozen.fundsStateHash()).isEqualTo(RollingFundsStateHash.compute(oracle));
            assertThat(frozen.state().users().get(7L).balances())
                    .isEqualTo(oracle.users().get(7L).balances());
            assertThat(frozen.state().users().get(7L).reservations())
                    .isEqualTo(oracle.users().get(7L).reservations());
            assertThat(frozen.state().users().get(7L).positions())
                    .isEqualTo(oracle.users().get(7L).positions());
            assertThat(frozen.state().orders()).isEqualTo(oracle.orders());
            assertThat(frozen.state().riskState().markPrices()).isEqualTo(oracle.riskState().markPrices());
            assertThat(frozen.state().treasuryState()).isEqualTo(oracle.treasuryState());
            assertThat(journal.projectionFreezeCount()).isEqualTo(1);
            assertThat(journal.await(3, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), false).state())
                    .isSameAs(frozen.state());
            assertThat(journal.projectionFreezeCount()).isEqualTo(1);
            assertThatThrownBy(() -> journal.await(patches.getFirst().projectionPoint()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("invalid runtime projection point fence");
        } finally {
            runtime.close();
            restoreProperty(property, previousProperty);
        }
    }

    @Test
    void midBatchFailureRollsBackTheWholeProjectionBatch() throws Exception {
        String property = "surprising.aeron.projection-batch-size";
        String previousProperty = System.getProperty(property);
        System.setProperty(property, "2");
        TradingCoreState initial = mixedInitial();
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(initial, identities);
        RuntimeCommitJournal journal = journal(initial);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            List<RuntimeCommitPatch> patches = captureMixedPatches(runtime, identities, initial, 250, -100);
            RuntimeCommitPatch first = patches.get(0);
            RuntimeCommitPatch second = patches.get(1);

            journal.blockProjectorForTest(entered, release);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            journal.failReplicaAfterMutationsForTest(2, 8);
            journal.publish(first, first.businessStateHash(), first.fundsStateHash());
            journal.publish(second, second.businessStateHash(), second.fundsStateHash());
            journal.requestProjection(2);
            release.countDown();
            awaitFailure(journal);

            assertThat(journal.publishedSequence()).isEqualTo(2);
            assertThat(journal.projectedSequence()).isZero();
            assertThat(journal.lag()).isEqualTo(2);
            assertThat(journal.projectionFreezeCount()).isZero();
            assertThat(first.projectionPoint().completed()).isFalse();
            assertThat(first.projectionPoint().projected()).isFalse();
            assertThatThrownBy(() -> journal.await(2, System.nanoTime() + TimeUnit.SECONDS.toNanos(1), false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("runtime commit journal failed");
        } finally {
            release.countDown();
            assertThatThrownBy(journal::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("did not drain");
            runtime.close();
            restoreProperty(property, previousProperty);
        }
    }

    @Test
    void interruptedCloseStillFlushesAndTerminatesProjector() {
        TradingCoreState initial = mixedInitial();
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(initial, identities);
        RuntimeCommitJournal journal = journal(initial);
        try {
            RuntimeCommitPatch patch = captureMixedPatches(runtime, identities, initial, 25).getFirst();
            journal.publish(patch, patch.businessStateHash(), patch.fundsStateHash());
            Thread.currentThread().interrupt();
            journal.close();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(journal.projectorAlive()).isFalse();
            assertThat(journal.lag()).isZero();
            assertThat(journal.projectedSequence()).isEqualTo(1);
        } finally {
            Thread.interrupted();
            runtime.close();
        }
    }

    @Test
    void rejectsGapDuplicateAndFutureFence() {
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        try (RuntimeCommitJournal journal = journal(initial)) {
            assertThatThrownBy(() -> journal.reservePublish(2))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("sequence gap");
            RuntimeCommitPatch.Builder builder = RuntimeCommitPatch.builder(
                    ProductLine.LINEAR_PERPETUAL, 0, 1)
                    .matcherTransition(CoreMatcherTransition.unchanged(0, 0));
            RuntimeCommitPatch patch = seal(builder, new RuntimeCommitPatch.SealMetadata(0, 0,
                    initial.businessStateHash(), initial.businessStateHash(),
                    RollingFundsStateHash.compute(initial), RollingFundsStateHash.compute(initial),
                    0, null));
            journal.publish(patch, patch.businessStateHash(), patch.fundsStateHash());
            assertThatThrownBy(() -> journal.reservePublish(1))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("sequence gap");
            assertThatThrownBy(() -> journal.requestProjection(2))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid requested");
        }
    }

    private static RuntimeCommitJournal journal(TradingCoreState initial) {
        return new RuntimeCommitJournal(initial.productLine(), initial, initial.businessStateHash(),
                RollingFundsStateHash.compute(initial));
    }

    private static RuntimeCommitPatch emptyPatch(TradingCoreState state, long sequence) {
        long fundsHash = RollingFundsStateHash.compute(state);
        RuntimeCommitPatch.Builder builder = RuntimeCommitPatch.builder(
                state.productLine(), sequence - 1, sequence)
                .matcherTransition(CoreMatcherTransition.unchanged(0, 0));
        return seal(builder, new RuntimeCommitPatch.SealMetadata(state.revision(), state.revision(),
                state.businessStateHash(), state.businessStateHash(), fundsHash, fundsHash, 0, null));
    }

    private static RuntimeCommitPatch patchWithTerminalOrder(TradingCoreState state, long sequence) {
        long fundsHash = RollingFundsStateHash.compute(state);
        RuntimeCommitPatch.Builder builder = RuntimeCommitPatch.builder(
                state.productLine(), sequence - 1, sequence)
                .matcherTransition(CoreMatcherTransition.unchanged(0, 0))
                .terminalIds(List.of(1L), List.of(), List.of());
        return seal(builder, new RuntimeCommitPatch.SealMetadata(state.revision(), state.revision(),
                state.businessStateHash(), state.businessStateHash(), fundsHash, fundsHash, 0, null));
    }

    private static List<RuntimeCommitPatch> captureMixedPatches(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, TradingCoreState initial,
            long... adjustments) {
        ArrayList<RuntimeCommitPatch> patches = new ArrayList<>();
        TradingCoreState previous = initial;
        for (int index = 0; index < adjustments.length; index++) {
            int delta = index + 1;
            RuntimeCommandProcessor.adjustBalance(runtime, identities, 7,
                    new BalanceAdjustmentCommand("USDT", adjustments[index]));
            int symbolId = identities.symbolId("BTC-USDT");
            runtime.putMarkPrice(new MarkPriceRuntime(symbolId, 1, 50_000 + index,
                    index + 1L, 1_000 + index));
            runtime.putReservation(RuntimeStateProjector.toRuntimeReservation(7,
                    new OrderReservation(11, "BTC-USDT", 1, ReservationKind.DERIVATIVE_MARGIN,
                            "USDT", 500, 100 + delta, 200, 10), identities));
            runtime.replaceOrder(RuntimeStateProjector.toRuntimeOrder(new CoreOrderState(
                    11, ProductLine.LINEAR_PERPETUAL, 7, "BTC-USDT", 1, CoreOrderSide.BUY,
                    50_000 + index, 10, 2 + delta, 8 - delta, false, CoreMarginMode.ISOLATED,
                    CorePositionSide.LONG, CoreOrderType.LIMIT, CoreTimeInForce.GTX, true,
                    "client-11", new UUID(0, 11), 12, 34, 1_000, 1_100, 99,
                    CoreOrderStatus.OPEN, 3), identities));
            long positionKey = identities.positionKey(7, "BTC-USDT:LONG");
            runtime.replacePosition(positionKey, new PositionRuntime(7, symbolId,
                    identities.assetId("USDT"), CoreMarginMode.ISOLATED, CorePositionSide.LONG,
                    1, 2 + delta, 100, 200 + delta, 9 + delta, 100 + delta));
            runtime.treasury().setFee(identities.assetId("USDT"), -3 + delta);
            TradingCoreState current = RuntimeStateMaterializer.materialize(runtime, identities);
            TradingRuntimeState.PreparedCommit prepared = runtime.prepareCommitPatch(
                    index + 1L, identities,
                    previous.revision(), CoreMatcherTransition.unchanged(0, 0), 0,
                    previous.businessStateHash(), current.businessStateHash(),
                    RollingFundsStateHash.compute(previous), RollingFundsStateHash.compute(current), true);
            RuntimeCommitPatch.PreparedChanges changes = prepared.prepareChanges();
            RuntimeCommitPatch patch = prepared.seal(changes, current.businessStateHash(),
                    RollingFundsStateHash.compute(current));
            patches.add(patch);
            runtime.clearChangedKeys();
            previous = current;
        }
        return List.copyOf(patches);
    }

    private static RuntimeCommitPatch seal(RuntimeCommitPatch.Builder builder,
                                           RuntimeCommitPatch.SealMetadata metadata) {
        RuntimeCommitPatch.PreparedChanges changes = builder.prepare(new RuntimeCommitPatch.PrepareMetadata(
                metadata.beforeRevision(), metadata.afterRevision(), metadata.beforeBusinessStateHash(),
                metadata.beforeFundsStateHash(), metadata.laneMask(), metadata.coreFactMetadata(),
                metadata.externalAdjustment()), new RuntimeIdentityRegistry());
        return builder.seal(changes, metadata.businessStateHash(), metadata.fundsStateHash());
    }

    private static TradingCoreState mixedInitial() {
        OrderReservation reservation = new OrderReservation(11, "BTC-USDT", 1,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 500, 100, 200, 10);
        CorePositionState position = new CorePositionState("BTC-USDT", "USDT", CoreMarginMode.ISOLATED,
                CorePositionSide.LONG, 1, 2, 100, 200, 9, 100);
        CoreUserState user = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 8,
                Map.of("USDT", new AssetBalance("USDT", 600, 300)), Map.of(11L, reservation),
                Map.of(position.key(), position), CorePositionMode.HEDGE);
        CoreOrderState order = new CoreOrderState(11, ProductLine.LINEAR_PERPETUAL, 7, "BTC-USDT", 1,
                CoreOrderSide.BUY, 50_000, 10, 2, 8, false, CoreMarginMode.ISOLATED,
                CorePositionSide.LONG, CoreOrderType.LIMIT, CoreTimeInForce.GTX, true,
                "client-11", new UUID(0, 11), 12, 34, 1_000, 1_100, 99,
                CoreOrderStatus.OPEN, 3);
        CoreTreasuryState treasury = new CoreTreasuryState(Map.of("USDT", -3L),
                Map.of("USDT", 7L), Map.of(), Map.of("BTC-USDT", 2L), Map.of());
        return new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 12, Map.of(7L, user), Map.of(11L, order),
                Map.of(), CoreRiskState.empty(), treasury);
    }

    private static void awaitLag(RuntimeCommitJournal journal, long expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (journal.lag() != expected && System.nanoTime() < deadline) Thread.onSpinWait();
        assertThat(journal.lag()).isEqualTo(expected);
    }

    private static void awaitFailure(RuntimeCommitJournal journal) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try {
                journal.current();
            } catch (IllegalStateException expected) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("projection journal did not fail before deadline");
    }

    private static void restoreProperty(String property, String value) {
        if (value == null) System.clearProperty(property); else System.setProperty(property, value);
    }
}
