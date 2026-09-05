package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.Header;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32C;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

class SurprisingClusteredServiceTest {

    @Test
    void operationalQueryDoesNotFenceLaterTradingButBusinessQueryDoes() throws Exception {
        SurprisingClusteredService service = service();
        List<byte[]> responses = new CopyOnWriteArrayList<>();
        service.onStart(cluster(), null);
        try {
            CoreProbeState state = service.state();
            preparePendingPlace(state, 18_001);
            var metrics = new CoreMessage(CoreMessageHeader.query(CoreMessageType.LANE_METRICS_QUERY,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 0, 1_000, 8), new byte[0]);
            onSessionMessage(service, responses, metrics);
            assertThat(responses).hasSize(1);
            CoreMessage later = command(CoreMessageType.PLACE_ORDER, 3, 1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(18_002, "BTC-USDT", 1,
                            CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "later")));
            onSessionMessage(service, responses, later);
            assertThat(state.matchingSequence(later.header().commandId())).isPositive();
            Field count = SurprisingClusteredService.class.getDeclaredField("pendingQueryCount");
            count.setAccessible(true);
            assertThat(count.getInt(service)).isZero();

            var query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.USER_STATE_QUERY,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 1001, 1_000, 9), new byte[0]);
            onSessionMessage(service, responses, query);
            onSessionMessage(service, responses, metrics);
            assertThat(responses).hasSize(1); // metrics must not overtake the earlier business fence
            awaitBackgroundWork(service, () -> responses.size() == 4 && state.pendingMatchingCount() == 0);
            assertThat(count.getInt(service)).isZero();
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void handsRuntimeOwnershipFromConstructionThreadToClusterServiceThread() throws Exception {
        SurprisingClusteredService service = service();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<CoreResponse> response = new AtomicReference<>();
        Thread serviceThread = new Thread(() -> {
            try {
                service.onStart(cluster(), null);
                response.set(service.state().apply(command(CoreMessageType.PROBE_INCREMENT, 1, 1001,
                        CoreProtocol.probePayload(1))));
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                service.onTerminate(null);
            }
        }, "clustered-service-owner-test");

        serviceThread.start();
        serviceThread.join();

        assertThat(failure.get()).isNull();
        assertThat(response.get()).isNotNull();
        assertThat(response.get().status()).isEqualTo(ResponseStatus.APPLIED);
    }

    @Test
    void backgroundWorkAppliesMatchingExactlyOnceWithoutAReplicatedTimer() {
        SurprisingClusteredService service = service();
        List<byte[]> responses = new CopyOnWriteArrayList<>();
        service.onStart(cluster(), null);
        try {
            assertThat(service.state().apply(timerInstrument()).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(service.state().apply(command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            CoreMessage place = command(CoreMessageType.PLACE_ORDER, 2, 1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(904, "BTC-USDT", 1,
                            CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                            false, "same-callback")));

            onSessionMessage(service, responses, place);
            service.onTimerEvent(service.state().appliedCommandCount(), 1_001);
            awaitBackgroundWork(service, () -> service.state().pendingMatchingCount() == 0
                    && responses.size() == 1);

            assertThat(service.state().pendingMatchingCount()).isZero();
            assertThat(responses).hasSize(1);
            assertThat(service.state().tradingState().order(904).status())
                    .isEqualTo(com.surprising.aeron.service.state.CoreOrderStatus.OPEN);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void deferredIngressCommitsMatchingBeforeTheFollowingFact() throws Exception {
        SurprisingClusteredService service = service();
        List<byte[]> responses = new CopyOnWriteArrayList<>();
        service.onStart(cluster(), null);
        try {
            CoreProbeState state = service.state();
            assertThat(state.apply(timerInstrument()).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.apply(command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)),
                    UUID.fromString("00000000-0000-0000-0000-000000000012"))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            CoreMessage place = command(CoreMessageType.PLACE_ORDER, 2, 1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(906, "BTC-USDT", 1, CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "session-fence")),
                    UUID.fromString("00000000-0000-0000-0000-000000000013"));
            onSessionMessage(service, responses, place);
            assertThat(state.pendingMatchingCount()).isOne();
            assertThat(responses).isEmpty();

            onSessionMessage(service, responses, command(CoreMessageType.PROBE_INCREMENT, 3, 1001,
                    CoreProtocol.probePayload(1),
                    UUID.fromString("00000000-0000-0000-0000-000000000014")));
            awaitBackgroundWork(service, () -> state.pendingMatchingCount() == 0
                    && responses.size() == 2);

            assertThat(state.pendingMatchingCount()).isZero();
            assertThat(state.tradingState().order(906).status())
                    .isEqualTo(com.surprising.aeron.service.state.CoreOrderStatus.OPEN);
            assertThat(responses).hasSize(2);
            var facts = state.exportState().pending().stream()
                    .map(message -> CoreExportCodec.decodeEvent(message.payload()))
                    .toList();
            assertThat(facts).extracting(fact -> fact.exportSequence()).isSorted();
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void loadsOneByteSnapshotFragmentsThroughBoundedSectionRecovery() {
        SurprisingClusteredService source = service();
        SurprisingClusteredService target = service();
        try {
            source.onStart(cluster(), null);
            assertThat(source.state().apply(command(CoreMessageType.PROBE_INCREMENT, 1, 1001,
                    CoreProtocol.probePayload(9))).status()).isEqualTo(ResponseStatus.APPLIED);
            byte[] snapshot = source.state().snapshot(45);
            AtomicInteger offset = new AtomicInteger();
            UnsafeBuffer buffer = new UnsafeBuffer(snapshot);
            SurprisingClusteredService.SnapshotFragmentSource fragments = (handler, fragmentLimit) -> {
                if (offset.get() == snapshot.length) return 0;
                handler.onFragment(buffer, offset.getAndIncrement(), 1, null);
                return 1;
            };
            target.onStart(cluster(), null);

            target.loadSnapshot(fragments, () -> offset.get() == snapshot.length);

            assertThat(target.state().probeValue()).isEqualTo(9);
            assertThat(target.state().stateHash()).isEqualTo(source.state().stateHash());
            assertThat(offset).hasValue(snapshot.length);
        } finally {
            source.onTerminate(null);
            target.onTerminate(null);
        }
    }

    @Test
    void emptyAndIncompleteFragmentSourcesFailBeforeStateReplacement() {
        SurprisingClusteredService service = service();
        try {
            service.onStart(cluster(), null);
            CoreProbeState before = service.state();
            assertThatThrownBy(() -> service.loadSnapshot((handler, limit) -> 0, () -> true))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
            assertThat(service.state()).isSameAs(before);

            byte[] snapshot = before.snapshot(46);
            byte[] truncated = Arrays.copyOf(snapshot, snapshot.length - 1);
            AtomicInteger delivered = new AtomicInteger();
            assertThatThrownBy(() -> service.loadSnapshot((handler, limit) -> {
                if (delivered.getAndIncrement() > 0) return 0;
                handler.onFragment(new UnsafeBuffer(truncated), 0, truncated.length, null);
                return 1;
            }, () -> delivered.get() > 0))
                    .isInstanceOf(ProtocolException.class);
            assertThat(service.state()).isSameAs(before);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void rejectsSnapshotFragmentsBeyondBoundedRecoveryBuffer() {
        SurprisingClusteredService.ensureSnapshotCapacity(CoreStateSnapshotCodec.MAX_SNAPSHOT_BYTES - 1, 1);

        assertThatThrownBy(() -> SurprisingClusteredService.ensureSnapshotCapacity(
                CoreStateSnapshotCodec.MAX_SNAPSHOT_BYTES - 1, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Aeron core snapshot exceeds maximum size");
    }

    @Test
    void doesNotReplaceStateAfterCorruptSnapshot() {
        SurprisingClusteredService service = service();
        try {
            service.onStart(cluster(), null);
            CoreProbeState before = service.state();
            byte[] snapshot = before.snapshot();
            snapshot[snapshot.length / 2] ^= 1;

            assertThatThrownBy(() -> service.restoreSnapshot(snapshot))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("checksum");
            assertThat(service.state()).isSameAs(before);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void pairedManifestMismatchFailsBeforeLiveStateReplacement() {
        SurprisingClusteredService service = service();
        try {
            service.onStart(cluster(), null);
            CoreProbeState before = service.state();
            assertThat(before.apply(command(CoreMessageType.PROBE_INCREMENT, 1, 1001,
                    CoreProtocol.probePayload(9))).status()).isEqualTo(ResponseStatus.APPLIED);
            long beforeHash = before.stateHash();
            long beforeSequence = before.appliedCommandCount();
            byte[] snapshot = before.snapshot(75);
            ByteBuffer manifest = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
            int snapshotIdOffset = SectionedCoreSnapshotCodec.ENVELOPE_LENGTH
                    + SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH + 74;
            manifest.putLong(snapshotIdOffset, manifest.getLong(snapshotIdOffset) + 1);
            CRC32C checksum = new CRC32C();
            checksum.update(snapshot, 0, snapshot.length - 16);
            manifest.putLong(snapshot.length - Long.BYTES, checksum.getValue());

            assertThatThrownBy(() -> service.restoreSnapshot(snapshot))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("snapshot id");
            assertThat(service.state()).isSameAs(before);
            assertThat(service.state().stateHash()).isEqualTo(beforeHash);
            assertThat(service.state().appliedCommandCount()).isEqualTo(beforeSequence);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void propagatesFatalMatcherDivergenceFromSnapshotCallback() {
        SurprisingClusteredService service = service();
        service.onStart(cluster(), null);
        try {
            CoreProbeState state = service.state();
            long sequence = preparePendingPlace(state, 901);
            var matcherFailure = new com.surprising.aeron.service.matching.CoreMatchingResult(
                    false, "EXCHANGE_CORE_FAILURE");
            Throwable fatal = catchThrowable(() -> state.completeMatching(sequence, matcherFailure, 2_000, 3));

            assertThat(fatal).isInstanceOf(
                    com.surprising.aeron.service.matching.FatalMatchingDivergenceException.class);
            assertThatThrownBy(() -> service.onTakeSnapshot(null)).isSameAs(fatal);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void localProgressDoesNotScheduleReplicatedClusterTimers() {
        SurprisingClusteredService service = service();
        AtomicInteger attempts = new AtomicInteger();
        AtomicLong correlationId = new AtomicLong();
        List<byte[]> responses = new CopyOnWriteArrayList<>();
        try {
            service.onStart(clusterWithTimerBackpressure(attempts, correlationId), null);
            preparePendingPlace(service.state(), 902);
            service.onSessionOpen(clientSession(responses), 1_000);

            assertThat(attempts).hasValue(0);
            assertThat(correlationId).hasValue(0);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void followerDoesNotSynthesizeHistoricalMatcherTimeoutDuringReplay() {
        SurprisingClusteredService service = service();
        service.onStart(cluster(Cluster.Role.FOLLOWER), null);
        try {
            long sequence = preparePendingPlace(service.state(), 905);
            assertThat(awaitMatching(service.state(), sequence)).isNotNull();

            service.onTimerEvent(Long.MAX_VALUE - 1, 31_000);

            assertThat(service.state().pendingMatching(sequence)).isNotNull();
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void snapshotWaitsForAsynchronousMatcherCaptureAndReleasesCommandAdmission() {
        SurprisingClusteredService service = service();
        service.onStart(cluster(), null);
        try {
            assertThat(service.captureSnapshot(7)).isNotEmpty();
            assertThat(service.state().apply(command(CoreMessageType.PROBE_INCREMENT, 1, 1001,
                    CoreProtocol.probePayload(7))).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(service.snapshotFenceNotReadyCount()).isZero();
            assertThat(service.snapshotFenceTimeoutCount()).isZero();
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void snapshotCaptureTimeoutIsFailClosedAndObservable() {
        SurprisingClusteredService service = service();
        service.onStart(cluster(), null);
        try {
            assertThatThrownBy(() -> service.captureSnapshot(9, System.nanoTime()))
                    .isInstanceOf(CoreProbeState.SnapshotFenceTimeoutException.class)
                    .hasMessage("snapshot fence timed out");
            assertThat(service.snapshotFenceTimeoutCount()).isEqualTo(1);
            assertThat(service.snapshotFenceNotReadyCount()).isZero();

            assertThat(service.captureSnapshot(10)).isNotEmpty();
            assertThat(service.snapshotFenceNotReadyCount()).isZero();
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void snapshotCallbackSurfaceDrainsQueuedMatcherCompletionBeforeCapture() {
        // Given
        SurprisingClusteredService service = service();
        service.onStart(cluster(), null);
        try {
            long pendingSequence = preparePendingPlace(service.state(), 903);
            var matchingResult = awaitMatching(service.state(), pendingSequence);
            assertThat(matchingResult).isNotNull();
            service.state().publishMatchingCompletion(pendingSequence, matchingResult);

            // When
            assertThat(service.captureSnapshot(8)).isNotEmpty();

            // Then
            assertThat(service.state().pendingMatchingCount()).isZero();
            assertThat(service.state().pendingMatching(pendingSequence)).isNull();
            assertThat(service.state().tradingState().order(903).status())
                    .isEqualTo(com.surprising.aeron.service.state.CoreOrderStatus.OPEN);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void restoresSuccessfulSnapshotRoundTripWithoutTimingPoll() {
        // Given
        SurprisingClusteredService service = service();
        try {
            service.onStart(cluster(), null);
            CoreProbeState before = service.state();
            assertThat(before.apply(command(CoreMessageType.PROBE_INCREMENT, 1, 1001,
                    CoreProtocol.probePayload(7))).status()).isEqualTo(ResponseStatus.APPLIED);
            byte[] snapshot = before.snapshot(11);

            // When
            service.restoreSnapshot(snapshot);

            // Then
            assertThat(service.state()).isNotSameAs(before);
            assertThat(service.state().probeValue()).isEqualTo(7);
            assertThat(service.state().appliedCommandCount()).isEqualTo(1);
        } finally {
            service.onTerminate(null);
        }
    }

    private static long preparePendingPlace(CoreProbeState state, long orderId) {
        assertThat(state.apply(instrument()).status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.apply(command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                .status()).isEqualTo(ResponseStatus.APPLIED);
        UUID commandId = UUID.randomUUID();
        CoreMessage place = command(CoreMessageType.PLACE_ORDER, 2, 1001,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "BTC-USDT", 1, CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "service-" + orderId)), commandId);
        assertThat(state.apply(place).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
        return state.matchingSequence(commandId);
    }

    private static TimerScenario runTimerScenario(boolean delayedCompletion) throws Exception {
        CountDownLatch timerReady = new CountDownLatch(1);
        CountDownLatch timerReturned = new CountDownLatch(1);
        CountDownLatch continueAfterTimer = new CountDownLatch(1);
        AtomicReference<CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult>>
                matchingReference = new AtomicReference<>();
        AtomicReference<com.surprising.aeron.service.matching.CoreMatchingResult> resultReference =
                new AtomicReference<>();
        FutureTask<TimerScenario> scenario = new FutureTask<>(() -> runTimerScenarioOnOwner(
                delayedCompletion, timerReady, timerReturned, continueAfterTimer, matchingReference,
                resultReference));
        Thread.ofVirtual().start(scenario);
        boolean ready = timerReady.await(10, TimeUnit.SECONDS);
        if (!ready && scenario.isDone()) scenario.get();
        assertThat(ready).isTrue();
        boolean returnedWithoutCompletion = timerReturned.await(250, TimeUnit.MILLISECONDS);
        if (!returnedWithoutCompletion) matchingReference.get().complete(resultReference.get());
        continueAfterTimer.countDown();
        TimerScenario result = scenario.get(5, TimeUnit.SECONDS);
        assertThat(returnedWithoutCompletion)
                .as("the clustered-service owner must never park waiting for a matcher completion")
                .isTrue();
        return result;
    }

    private static TimerScenario runTimerScenarioOnOwner(
            boolean delayedCompletion,
            CountDownLatch timerReady,
            CountDownLatch timerReturned,
            CountDownLatch continueAfterTimer,
            AtomicReference<CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult>>
                    matchingReference,
            AtomicReference<com.surprising.aeron.service.matching.CoreMatchingResult> resultReference)
            throws Exception {
        SurprisingClusteredService service = service();
        List<byte[]> responses = new CopyOnWriteArrayList<>();
        try {
            service.onStart(cluster(), null);
            CoreProbeState state = service.state();
            assertThat(state.apply(timerInstrument()).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.apply(command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)),
                    UUID.fromString("00000000-0000-0000-0000-000000000002"))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            CoreMessage place = command(CoreMessageType.PLACE_ORDER, 2, 1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(904, "BTC-USDT", 1, CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "timer-replay")),
                    UUID.fromString("00000000-0000-0000-0000-000000000003"));
            byte[] encoded = CoreMessageCodec.encode(place);
            service.onSessionMessage(clientSession(responses), 1_000, new UnsafeBuffer(encoded), 0,
                    encoded.length, aeronHeader());
            long sequence = state.matchingSequence(place.header().commandId());
            assertThat(sequence).isPositive();
            var accepted = awaitSubmittedMatching(state, sequence);
            assertThat(accepted).isNotNull();

            CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> matching =
                    new CompletableFuture<>();
            installIncompleteMatchingFuture(state, sequence, matching);
            matchingReference.set(matching);
            resultReference.set(accepted);
            timerReady.countDown();
            if (!delayedCompletion) matching.complete(accepted);

            service.onTimerEvent(sequence, 1_001);
            timerReturned.countDown();
            assertThat(continueAfterTimer.await(2, TimeUnit.SECONDS)).isTrue();

            if (delayedCompletion) {
                matching.complete(accepted);
                service.onTimerEvent(sequence, 1_002);
            }
            long hashAfterCompletion = state.stateHash();
            long appliedAfterCompletion = state.appliedCommandCount();
            long exportSequenceAfterCompletion = state.exportState().nextSequence();
            int exportFactsAfterCompletion = state.exportState().pendingCount();
            int responsesAfterCompletion = responses.size();

            service.onTimerEvent(sequence, 1_003);

            assertThat(state.stateHash()).isEqualTo(hashAfterCompletion);
            assertThat(state.appliedCommandCount()).isEqualTo(appliedAfterCompletion);
            assertThat(state.exportState().nextSequence()).isEqualTo(exportSequenceAfterCompletion);
            assertThat(state.exportState().pendingCount()).isEqualTo(exportFactsAfterCompletion);
            assertThat(responses).hasSize(responsesAfterCompletion);
            assertThat(responses).hasSize(1);
            CoreMessage response = CoreMessageCodec.decode(responses.getFirst());
            assertThat(CoreProtocol.decodeResponse(response.payload()).status()).isEqualTo(ResponseStatus.APPLIED);
            return new TimerScenario(state.stateHash(), state.appliedCommandCount(),
                    state.exportState().nextSequence(), state.exportState().pendingCount(), responses.size(),
                    state.pendingMatchingCount(), state.tradingState().order(904).status());
        } finally {
            service.onTerminate(null);
        }
    }

    private static com.surprising.aeron.service.matching.CoreMatchingResult awaitSubmittedMatching(
            CoreProbeState state,
            long sequence) throws Exception {
        LaneCommandContextRing.Context context = matchingContext(state, sequence);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            state.drainMatchingCompletions();
            var result = context.matchingCompletion();
            if (result != null) return result;
            Thread.onSpinWait();
        }
        throw new AssertionError("matching result was not published within five seconds");
    }

    private static void installIncompleteMatchingFuture(
            CoreProbeState state,
            long sequence,
        CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> replacement)
            throws Exception {
        LaneCommandContextRing.Context context = matchingContext(state, sequence);
        Field completionsField = CoreProbeState.class.getDeclaredField("matchingCompletions");
        completionsField.setAccessible(true);
        Object completions = completionsField.get(state);
        var clear = completions.getClass().getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(completions);
        context.resetMatchingContinuation();
        var track = CoreProbeState.class.getDeclaredMethod(
                "trackMatchingFuture", long.class, CompletableFuture.class);
        track.setAccessible(true);
        track.invoke(state, sequence, replacement);
    }

    private static LaneCommandContextRing.Context matchingContext(CoreProbeState state, long sequence)
            throws Exception {
        Field contextsField = CoreProbeState.class.getDeclaredField("laneCommandContexts");
        contextsField.setAccessible(true);
        return ((LaneCommandContextRing) contextsField.get(state)).required(sequence);
    }

    private static ClientSession clientSession(List<byte[]> responses) {
        return (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "id" -> 91L;
                    case "isClosing" -> false;
                    case "offer" -> {
                        if (arguments.length == 3) {
                            var buffer = (org.agrona.DirectBuffer) arguments[0];
                            int offset = (int) arguments[1];
                            int length = (int) arguments[2];
                            byte[] response = new byte[length];
                            buffer.getBytes(offset, response);
                            responses.add(response);
                        }
                        yield 1L;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static void onSessionMessage(
            SurprisingClusteredService service, List<byte[]> responses, CoreMessage request) {
        byte[] encoded = CoreMessageCodec.encode(request);
        service.onSessionMessage(clientSession(responses), 1_000, new UnsafeBuffer(encoded), 0,
                encoded.length, aeronHeader());
    }

    private static void awaitBackgroundWork(
            SurprisingClusteredService service, java.util.function.BooleanSupplier completed) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!completed.getAsBoolean() && System.nanoTime() < deadline) {
            if (service.doBackgroundWork(System.nanoTime()) == 0) Thread.onSpinWait();
        }
        assertThat(completed.getAsBoolean()).isTrue();
    }

    private static Header aeronHeader() {
        return new Header(0, 0).buffer(new UnsafeBuffer(new byte[64])).offset(0)
                .initialTermId(0).positionBitsToShift(16);
    }

    private static CoreMessage timerInstrument() {
        UpsertInstrumentCommand instrument = new UpsertInstrumentCommand("BTC-USDT", 1,
                ContractType.SPOT.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                100_000, 50_000, 0, 0, 0, -1, 0);
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.UPSERT_INSTRUMENT,
                UUID.fromString("00000000-0000-0000-0000-000000000001"), ProductLine.SPOT,
                CommandSource.OPERATIONS, 88, 1, 0, 1_000, 1),
                TradingCommandCodec.encodeUpsertInstrument(instrument));
    }

    private record TimerScenario(
            long stateHash,
            long appliedCommandCount,
            long nextExportSequence,
            int exportFactCount,
            int responseCount,
            int pendingMatchingCount,
            com.surprising.aeron.service.state.CoreOrderStatus orderStatus) {
    }

    private static CoreMessage instrument() {
        UpsertInstrumentCommand instrument = new UpsertInstrumentCommand("BTC-USDT", 1,
                ContractType.SPOT.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                100_000, 50_000, 0, 0, 0, -1, 0);
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.UPSERT_INSTRUMENT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 88, 1, 0, 1_000, 1),
                TradingCommandCodec.encodeUpsertInstrument(instrument));
    }

    private static CoreMessage command(CoreMessageType type, long sequence, long userId, byte[] payload) {
        return command(type, sequence, userId, payload, UUID.randomUUID());
    }

    private static CoreMessage command(
            CoreMessageType type,
            long sequence,
            long userId,
            byte[] payload,
            UUID commandId) {
        return new CoreMessage(CoreMessageHeader.command(type, commandId, ProductLine.SPOT,
                CommandSource.GATEWAY, 77, sequence, userId, 1_000, sequence), payload);
    }

    private static Cluster cluster() {
        return cluster(Cluster.Role.LEADER);
    }

    private static SurprisingClusteredService service() {
        return new SurprisingClusteredService(ProductLine.SPOT);
    }

    private static Cluster cluster(Cluster.Role role) {
        return (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "role" -> role;
                    case "logPosition" -> 7L;
                    case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                    case "timeUnit" -> TimeUnit.MILLISECONDS;
                    case "time" -> 1_000L;
                    case "scheduleTimer" -> true;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static com.surprising.aeron.service.matching.CoreMatchingResult awaitMatching(
            CoreProbeState state, long sequence) {
        com.surprising.aeron.service.matching.CoreMatchingResult result = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (result == null && System.nanoTime() < deadline) {
            result = state.takeMatchingResult(sequence);
            if (result == null) Thread.onSpinWait();
        }
        return result;
    }

    private static Cluster clusterWithTimerBackpressure(AtomicInteger attempts, AtomicLong correlationId) {
        return (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "logPosition" -> 7L;
                    case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                    case "timeUnit" -> TimeUnit.MILLISECONDS;
                    case "time" -> 1_000L;
                    case "scheduleTimer" -> {
                        correlationId.set((long) arguments[0]);
                        yield attempts.incrementAndGet() >= 3;
                    }
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
}
