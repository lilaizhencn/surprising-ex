package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.orchestration.realtime.RealtimeReadCoordinator;
import com.surprising.aeron.service.orchestration.ClusterCommandWindow;import com.surprising.aeron.service.matcher.MatcherPipelineGroup;
import com.surprising.aeron.service.matcher.MatcherCommandPipeline;
import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.LaneTopology;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.logbuffer.Header;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ClusterCommandPipelineTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void consecutiveReadyHeadsRetireInOnePollWithoutMergingCommandBoundaries(ProductLine product) throws Exception {
        var release = new CountDownLatch(1);
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var state = live.service.state();
            var entered = new CountDownLatch(1);
            var gate = state.matcherPipeline.readAtSubmissionFence(
                    state.matchingAdapter.matcherShardId("BTC-USDT"), () -> {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher timeout");
                        } catch (InterruptedException failure) { throw new AssertionError(failure); }
                        return true;
                    });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var first = live.place(11, "BTC-USDT", 81000, 80, 1, CoreOrderSide.BUY);
            var second = live.place(disjointUser(11), "BTC-USDT", 81001, 80, 1, CoreOrderSide.BUY);
            live.send(first);
            live.send(second);
            var firstPending = state.pendingMatching(state.matchingSequence(first.header().commandId()));
            var secondPending = state.pendingMatching(state.matchingSequence(second.header().commandId()));
            live.progressUntil(() -> firstPending.settlementEvent() != null
                    && firstPending.settlementEvent().dispatched()
                    && secondPending.settlementEvent() != null && secondPending.settlementEvent().dispatched());
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((!firstPending.settlementEvent().complete() || !secondPending.settlementEvent().complete())
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(firstPending.settlementEvent().complete()).isTrue();
            assertThat(secondPending.settlementEvent().complete()).isTrue();
            assertThat(live.responses).isEmpty();
            live.service.pollCommands();
            assertThat(live.responses).hasSize(2);
            assertThat(live.service.pendingCommandCount()).isZero();
            assertThat(live.responses.get(0).committedCoreSequence())
                    .isLessThan(live.responses.get(1).committedCoreSequence());
            serial.apply(first);
            serial.apply(second);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().users())
                    .isEqualTo(serial.service.state().tradingState().users());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
            assertThat(gate.join()).isTrue();
        } finally { release.countDown(); }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "core.settlementLatencyDiagnostics", matches = "true")
    void sampledSettlementTimesSurviveEventReuseAndCoverOrdinaryAndBatchOrders() throws Exception {
        var path = java.nio.file.Files.createTempFile("settlement-latency-test-", ".jfr");
        try (var recording = new jdk.jfr.Recording()) {
            recording.enable(CoreMatchingPhaseMetrics.SettlementLatency.class);
            recording.enable(CoreMatchingPhaseMetrics.OwnerHead.class);
            recording.enable(CoreMatchingPhaseMetrics.CommandBoundaryLatency.class);
            recording.enable(CoreMatchingPhaseMetrics.OwnerTurn.class);
            recording.enable(CoreMatchingPhaseMetrics.OwnerPublication.class);
            recording.enable("surprising.OwnerSettlementMerge");
            recording.start();
            for (boolean batch : new boolean[]{false, true}) {
                try (Fixture live = new Fixture(ProductLine.LINEAR_PERPETUAL)) {
                    live.setup();
                    for (int i = 0; i < 256; i++) {
                        long id = 1000 + i * 20L;
                        live.apply(batch ? live.placeBatch(11, "BTC-USDT", id)
                                : live.place(11, "BTC-USDT", id, 80, 1, CoreOrderSide.BUY));
                        live.apply(batch ? live.cancelBatch(11, id) : live.cancel(11, id));
                    }
                }
            }
            recording.stop(); recording.dump(path);
            var merges = jdk.jfr.consumer.RecordingFile.readAllEvents(path).stream()
                    .filter(e -> e.getEventType().getName().equals("surprising.OwnerSettlementMerge")).toList();
            assertThat(merges).extracting(e -> e.getString("scope")).contains("lane", "collection");
            for (var merge : merges) {
                assertThat(merge.getBoolean("completed")).isTrue();
                assertThat(merge.getLong("sequence")).isPositive();
                long total = merge.getLong("totalNanos");
                assertThat(total).isBetween(0L, TimeUnit.SECONDS.toNanos(5));
                long stages = 0;
                for (String field : List.of("publicationNanos", "terminalIndexNanos", "trimNanos",
                        "releaseNanos", "changedIndexNanos", "prepareNanos", "admissionNanos",
                        "fundsNanos", "identitiesNanos", "laneMergeNanos", "balancesNanos", "pendingNanos")) {
                    long value = merge.getLong(field);
                    assertThat(value).as(field).isBetween(0L, total);
                    stages += value;
                }
                assertThat(stages).isLessThanOrEqualTo(total);
                assertThat(merge.getInt("orderRemovals") + merge.getInt("reservationRemovals"))
                        .isEqualTo(merge.getInt("removals"));
                assertThat(merge.getInt("orderRemovalMisses") + merge.getInt("reservationRemovalMisses"))
                        .isEqualTo(merge.getInt("removalMisses"));
                assertThat(merge.getLong("orderRemovalNanos") + merge.getLong("reservationRemovalNanos"))
                        .isEqualTo(merge.getLong("removalNanos"));
                long publication = 0;
                for (String field : List.of("usersNanos", "ordersNanos", "reservationsNanos", "positionsNanos", "removalsNanos")) {
                    assertThat(merge.getLong(field)).as(field).isNotNegative();
                    publication += merge.getLong(field);
                }
                assertThat(publication).isLessThanOrEqualTo(merge.getLong("publicationNanos"));
            }
            var boundaries = jdk.jfr.consumer.RecordingFile.readAllEvents(path).stream()
                    .filter(e -> e.getEventType().getName().equals("surprising.CommandBoundaryLatency")).toList();
            assertThat(boundaries).extracting(e -> e.getString("stage")).contains(
                    "ownerCommitAttemptTerminal", "ownerFactPublication", "ownerTerminalBookkeeping",
                    "ownerRealtimePublication", "ownerResponseAndRetirement");
            for (var event : boundaries) {
                assertThat(event.getLong("finishedNanos") - event.getLong("startedNanos"))
                        .isEqualTo(event.getLong("elapsedNanos"));
                assertThat(event.getLong("elapsedNanos")).isBetween(0L, TimeUnit.SECONDS.toNanos(5));
                assertThat(event.getString("commandType")).isNotBlank();
            }
            var turns = jdk.jfr.consumer.RecordingFile.readAllEvents(path).stream()
                    .filter(e -> e.getEventType().getName().equals("surprising.OwnerTurn")).toList();
            assertThat(turns).isNotEmpty();
            for (var turn : turns) {
                assertThat(turn.getInt("retired") + turn.getInt("admitted")).isBetween(0, 64);
                assertThat(turn.getInt("windowAtStart")).isPositive();
            }
            var publications = jdk.jfr.consumer.RecordingFile.readAllEvents(path).stream()
                    .filter(e -> e.getEventType().getName().equals("surprising.OwnerPublication")).toList();
            assertThat(publications).isNotEmpty();
            for (var publication : publications) {
                assertThat(publication.getBoolean("completed")).isTrue();
                long parts = 0;
                for (String field : List.of("fundsNanos", "realtimeCaptureNanos", "indexesNanos", "journalNanos", "clearNanos")) {
                    assertThat(publication.getLong(field)).isNotNegative();
                    parts += publication.getLong(field);
                }
                assertThat(parts).isLessThanOrEqualTo(publication.getLong("totalNanos"));
            }
            var heads = jdk.jfr.consumer.RecordingFile.readAllEvents(path).stream()
                    .filter(e -> e.getEventType().getName().equals("surprising.OwnerHead")).toList();
            assertThat(heads).isNotEmpty();
            for (var head : heads) {
                long residence = head.getLong("retiredNanos") - head.getLong("observedNanos");
                assertThat(head.getBoolean("completed")).isTrue();
                assertThat(head.getInt("attempts")).isPositive();
                assertThat(head.getInt("unreadyAttempts")).isLessThan(head.getInt("attempts"));
                assertThat(head.getLong("attemptNanos") + head.getLong("admissionWhileWaitingNanos"))
                        .isBetween(0L, residence);
                assertThat(head.getLong("admissionAfterLaneFinishNanos"))
                        .isBetween(0L, head.getLong("admissionWhileWaitingNanos"));
                assertThat(head.getInt("admittedAfterLaneFinish"))
                        .isBetween(0, head.getInt("admittedWhileWaiting"));
                if (head.getInt("unreadyAttempts") != 0) assertThat(head.getString("firstWaitReason")).isNotBlank();
            }
            var events = jdk.jfr.consumer.RecordingFile.readAllEvents(path).stream()
                    .filter(e -> e.getEventType().getName().equals("surprising.SettlementLatency")).toList();
            assertThat(events).extracting(e -> e.getString("commandType"))
                    .contains("PLACE_ORDER", "PLACE_ORDER_BATCH");
            for (var event : events) {
                assertThat(event.getInt("lanes")).isPositive();
                assertThat(event.getLong("ownerObservedNanos") - event.getLong("lanesCompletedNanos"))
                        .isEqualTo(event.getLong("lanesCompleteToOwnerNanos"));
                var matchingHeads = heads.stream().filter(h -> h.getLong("sequence") == event.getLong("sequence")
                        && h.getLong("commandIdHigh") == event.getLong("commandIdHigh")
                        && h.getLong("commandIdLow") == event.getLong("commandIdLow")
                        && h.getString("commandType").equals(event.getString("commandType"))).toList();
                assertThat(matchingHeads).hasSize(1);
                assertThat(matchingHeads.getFirst().getLong("observedNanos"))
                        .isLessThanOrEqualTo(event.getLong("ownerObservedNanos"));
                for (String field : List.of("matcherToLastLaneStartNanos", "maxLaneExecutionNanos",
                        "matcherToLanesCompleteNanos", "lanesCompleteToOwnerNanos"))
                    assertThat(event.getLong(field)).as(field).isBetween(0L, TimeUnit.SECONDS.toNanos(5));
                assertThat(event.getLong("matcherToLanesCompleteNanos"))
                        .isGreaterThanOrEqualTo(event.getLong("matcherToLastLaneStartNanos"));
            }
        } finally { java.nio.file.Files.deleteIfExists(path); }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void restingOrdersKeepTheirAdmissionVersionThroughOrderedCommit(ProductLine product) throws Exception {
        for (boolean batch : new boolean[]{false, true}) {
            var release = new CountDownLatch(1);
            try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
                serial.applyAll(live.setup());
                var state = live.service.state();
                var entered = new CountDownLatch(1);
                var gate = state.matcherPipeline.readAtSubmissionFence(
                        state.matchingAdapter.matcherShardId("BTC-USDT"), () -> {
                            entered.countDown();
                            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher timeout"); }
                            catch (InterruptedException failure) { throw new AssertionError(failure); }
                            return true;
                        });
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                var command = batch ? live.placeBatch(11, "BTC-USDT", 1000)
                        : live.place(11, "BTC-USDT", 1000, 80, 1, CoreOrderSide.BUY);
                try {
                    live.send(command);
                    long sequence = state.matchingSequence(command.header().commandId());
                    live.progressUntil(() -> {
                        var pending = state.pendingMatching(sequence);
                        var event = batch ? pending.orderBatch.settlementEvent : pending.settlementEvent();
                        var receipt = batch
                                ? pending.orderBatch.preparedAdmittedOrders[0]
                                : pending.admittedPlaceOrder();
                        return event != null && event.dispatched() && receipt != null;
                    });
                    var pending = state.pendingMatching(sequence);
                    var admitted = batch ? pending.orderBatch.preparedAdmittedOrders[0] : null;
                    var resolved = batch ? null : pending.admittedPlaceOrder();
                    assertThat(batch ? admitted : resolved).isNotNull();
                    long resolvedOrderId = batch ? 0 : resolved.orderId();
                    var resolvedInstrument = batch ? null : resolved.instrument();
                    if (batch) assertThat(admitted.createdAtEpochMillis())
                            .isEqualTo(command.header().submittedAtEpochMillis());
                    assertThat(state.runtimeState.order(1000))
                            .as("admission stays Lane-owned until ordered settlement").isNull();
                    release.countDown();
                    live.tick();
                    assertThat(gate.join()).isTrue();
                    if (batch) assertThat(state.runtimeState.order(1000)).isEqualTo(admitted);
                    else {
                        assertThat(state.runtimeState.order(1000).orderId()).isEqualTo(resolvedOrderId);
                        assertThat(state.runtimeState.order(1000).instrument()).isSameAs(resolvedInstrument);
                    }
                    serial.apply(command);
                    assertThat(live.responses).hasSize(1);
                    assertThat(live.hash()).isEqualTo(serial.hash());
                    try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                        assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                    }
                } finally { release.countDown(); }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void matcherPublishesOrdinaryAndBatchFillsToLanesWithoutOwnerDrainingResults(ProductLine product) throws Exception {
        for (boolean batch : new boolean[]{false, true}) {
            var release = new CountDownLatch(1);
            try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
                serial.applyAll(live.setup());
                long maker = disjointUser(11);
                if (product == ProductLine.SPOT) {
                    var deposit = live.message(CoreMessageType.ADJUST_BALANCE, maker,
                            TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 100)));
                    live.apply(deposit); serial.apply(deposit);
                }
                var resting = live.place(maker, "BTC-USDT", 900, 80, 20, CoreOrderSide.SELL);
                live.apply(resting); serial.apply(resting);
                live.responses.clear();
                var state = live.service.state();
                var entered = new CountDownLatch(1);
                var gate = state.matcherPipeline.readAtSubmissionFence(state.matchingAdapter.matcherShardId("BTC-USDT"), () -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("matcher gate timeout");
                    } catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                    return true;
                });
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                var command = batch ? live.placeBatch(11, "BTC-USDT", 1000)
                        : live.place(11, "BTC-USDT", 1000, 80, 1, CoreOrderSide.BUY);
                live.send(command);
                long sequence = state.matchingSequence(command.header().commandId());
                live.progressUntil(() -> {
                    var pending = state.pendingMatching(sequence);
                    var event = batch ? pending.orderBatch.settlementEvent : pending.settlementEvent();
                    var receipt = batch
                            ? pending.orderBatch.preparedAdmittedOrders[0]
                            : pending.admittedPlaceOrder();
                    return event != null && event.dispatched() && receipt != null;
                });
                var pending = state.pendingMatching(sequence);
                var event = batch ? pending.orderBatch.settlementEvent : pending.settlementEvent();
                assertThat(event.direct()).isTrue();
                assertThat(event.ready()).isFalse();
                // Keep only the immutable receipt visible while Matcher/Lanes advance independently.
                var admittedOrder = batch ? pending.orderBatch.preparedAdmittedOrders[0] : null;
                var resolvedOrder = batch ? null : pending.admittedPlaceOrder();
                assertThat(batch ? admittedOrder : resolvedOrder).isNotNull();
                var orderBeforeSettlement = batch ? admittedOrder.snapshot() : null;
                assertThat(state.runtimeState.order(1000)).isNull();
                assertThat(state.runtimeState.reservation(1000)).isNull();
                release.countDown();
                // Do not call Owner progress/drain here: the Matcher and Lanes must finish on their own.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!event.complete() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertThat(event.complete()).isTrue();
                if (batch) assertThat(admittedOrder).isEqualTo(orderBeforeSettlement);
                else assertThat(event.admittedOrder())
                        .as("normal admission does not duplicate the Lane-owned runtime order")
                        .isNull();
                assertThat(state.runtimeState.order(1000)).isNull();
                assertThat(state.runtimeState.reservation(1000)).isNull();
                if (batch) {
                    assertThat(pending.orderBatch.nextIndex).as("submission cursor belongs to Owner").isZero();
                    assertThat(state.batches.orderBatchMatcherShard(pending.orderBatch))
                            .isEqualTo(state.matchingAdapter.matcherShardId("BTC-USDT"));
                }
                assertThat(state.laneCommandContexts.required(sequence).hasMatchingCompletion()).isFalse();
                assertThat(live.responses).isEmpty();
                assertThat(event.plan().tradeCount()).isPositive();
                for (int item = 0; item < (batch ? 20 : 1); item++) {
                    assertThat(state.identities.findClientKey(11, (batch ? "batch-" : "order-") + (1000 + item)))
                            .as("Lane retires terminal client identity before Owner drains or publishes")
                            .isNull();
                }
                live.tick(); serial.apply(command);
                assertThat(gate.join()).isTrue();
                assertThat(live.responses).hasSize(1);
                assertThat(state.terminalRetention.containsOrder(999_999, 11, (batch ? "batch-" : "order-") + 1000))
                        .as("ordered publication retains the client ID tombstone after Lane freed the identity")
                        .isTrue();
                assertThat(live.hash()).isEqualTo(serial.hash());
                assertThat(state.tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
                try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(101))) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
            } finally { release.countDown(); }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void idleProbeBeforeTheFirstLogDoesNotRequireAnActivatedCommitJournal(ProductLine product) {
        try (Fixture live = new Fixture(product)) {
            live.service.ownerCompletionSignal(() -> {});
            assertThat(live.service.ownerCompletionAvailable()).isFalse();
            assertThat(live.service.pollCommands()).isZero();
            assertThat(live.service.ownerCompletionAvailable()).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void deferredIngressPreservesDependentBatchOrderAndSnapshot(ProductLine product) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var commands = List.of(live.placeBatch(11, "BTC-USDT", 71000),
                    live.cancelBatch(11, 71000));
            for (var command : commands) {
                live.service.enqueueCommittedCommand(live.session, command,
                        command.header().submittedAtEpochMillis(), 0, null);
                serial.apply(command);
            }
            assertThat(live.responses).isEmpty();
            live.tick();
            assertThat(live.responses).hasSize(2);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(881))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /** 两个订单簿可以独立撮合，同一用户不能重复花费同一份共享资金。 */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void sharedFundsRemainOneAccountAcrossMatcherPartitions(ProductLine product) throws Exception {
        String key = "surprising.aeron.matching-engines";
        String previous = System.getProperty(key);
        System.setProperty(key, "2");
        var release = new CountDownLatch(1);
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            String firstSymbol = "BTC-USDT";
            String secondSymbol = differentMatcherSymbol(live.service.state(), firstSymbol);
            serial.applyAll(live.setup(secondSymbol));
            String asset = ContractType.valueOf(product.contractTypeCode()).isInverse() ? "BTC" : "USDT";
            // 由真实产品准入计算一笔订单冻结额；不把现货名义金额套用到衍生品保证金。
            var probe = live.place(11, firstSymbol, 900, 80, 1, CoreOrderSide.BUY);
            live.apply(probe); serial.apply(probe);
            long required = live.service.state().tradingState().user(11).balances().get(asset).lockedUnits();
            assertThat(required).isPositive();
            var cancelProbe = live.cancel(11, 900);
            live.apply(cancelProbe); serial.apply(cancelProbe);
            var withdraw = live.message(CoreMessageType.ADJUST_BALANCE, 11,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, required - 20_000)));
            live.apply(withdraw); serial.apply(withdraw);
            live.responses.clear();
            var first = live.place(11, firstSymbol, 1000, 80, 1, CoreOrderSide.BUY);
            var second = live.place(11, secondSymbol, 2000, 80, 1, CoreOrderSide.BUY);
            var entered = new CountDownLatch(1);
            var gate = live.service.state().matcherPipeline.readAtSubmissionFence(
                    live.service.state().matchingAdapter.matcherShardId(firstSymbol), () -> {
                        entered.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher gate timeout"); }
                        catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                        return true;
                    });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(first); live.send(second);
                for (int i = 0; i < 20; i++) live.service.pollCommands();
                assertThat(live.responses).isEmpty();
                assertThat(live.service.state().matchingSequence(second.header().commandId()))
                        .as("shared funds must wait for the earlier account mutation").isZero();
            } finally { release.countDown(); }
            live.tick(); serial.apply(first); serial.apply(second);
            assertThat(gate.join()).isTrue();
            assertThat(live.responses).hasSize(2);
            assertThat(live.responses.getFirst().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(live.service.state().tradingState().user(11).balances().get(asset).availableUnits()).isZero();
            assertThat(live.hash()).isEqualTo(serial.hash());
            // 取消 A 的订单后 B 可以使用释放的资金；快照恢复也必须保留这份共享余额。
            var cancel = live.cancel(11, 1000);
            live.apply(cancel); serial.apply(cancel);
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                var retry = live.place(11, secondSymbol, 3000, 80, 1, CoreOrderSide.BUY);
                live.apply(retry); serial.apply(retry);
                assertThat(CoreTestCompletion.applyAsynchronously(restored, retry, retry.header().submittedAtEpochMillis(), 0).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash()).isEqualTo(serial.hash());
                assertThat(restored.tradingState().user(11).balances().get(asset).lockedUnits()).isEqualTo(required);
            }
        } finally {
            release.countDown();
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    /** 两个不同吃单用户仍可能结算到同一做市账户，不能仅用请求 userId 判断独立。 */

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void nativeRejectionReleasesFundsInTheAccountLane(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product)) {
            live.setup();
            var adapter = live.service.state().matchingAdapter;
            adapter.matcherShardId("BTC-USDT");
            var symbolsField = adapter.getClass().getDeclaredField("symbols");
            symbolsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var symbols = (java.util.Map<String, Integer>) symbolsField.get(adapter);
            var registeredField = adapter.getClass().getDeclaredField("registeredSymbols");
            registeredField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var registered = (java.util.Set<Integer>) registeredField.get(adapter);
            // 故障注入使 native 返回真实的 ORDER_BOOK_ID 拒绝；不伪造成交或资金结果。
            int symbol = symbols.get("BTC-USDT");
            registered.add(symbol);
            var before = live.service.state().tradingState().user(11).balances();
            var request = live.place(11, "BTC-USDT", 1000, 80, 1, CoreOrderSide.BUY);
            try { live.apply(request); }
            finally { registered.remove(symbol); }
            assertThat(live.responses).hasSize(1);
            assertThat(live.responses.getFirst().commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(live.responses.getFirst().resultCode()).isEqualTo(CoreResultCode.MATCHING_REJECTED);
            assertThat(live.service.state().tradingState().user(11).balances()).isEqualTo(before);
            assertThat(live.service.state().tradingState().user(11).reservations()).isEmpty();
            assertThat(live.service.state().pendingMatchingCount()).isZero();
            var access = live.service.state().runtimeState.getClass().getDeclaredField("ownerLaneAccess");
            access.setAccessible(true);
            assertThat(access.getBoolean(live.service.state().runtimeState)).isFalse();
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                var retry = live.place(11, "BTC-USDT", 2000, 80, 1, CoreOrderSide.BUY);
                live.apply(retry);
                assertThat(CoreTestCompletion.applyAsynchronously(restored, retry,
                        retry.header().submittedAtEpochMillis(), 0).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = "SPOT", mode = EnumSource.Mode.EXCLUDE)
    void isolatedMarginChangesStayInTheAccountLane(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            for (boolean buy : new boolean[]{false, true}) {
                var order = live.message(CoreMessageType.PLACE_ORDER, buy ? 11 : disjointUser(11),
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(buy ? 1001 : 1000,
                                "BTC-USDT", buy ? CoreOrderSide.BUY : CoreOrderSide.SELL, 100, 10, false,
                                CoreMarginMode.ISOLATED, CorePositionSide.NET, CoreOrderType.LIMIT,
                                CoreTimeInForce.GTC, false, buy ? "margin-buy" : "margin-sell")));
                live.apply(order); serial.apply(order);
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
            var before = live.service.state().tradingState().user(11);
            var field = live.service.state().runtimeState.getClass().getDeclaredField("laneWorkers");
            field.setAccessible(true);
            Object[] workers = (Object[]) field.get(live.service.state().runtimeState);
            int unrelated = (live.service.state().runtimeState.topology().accountLaneId(11) + 1) % workers.length;
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            Class<?> task = com.surprising.aeron.service.lane.SettlementLaneWorker.Command.class;
            var submit = workers[unrelated].getClass().getDeclaredMethod("submit", task);
            submit.setAccessible(true);
            submit.invoke(workers[unrelated], Proxy.newProxyInstance(task.getClassLoader(), new Class<?>[]{task},
                    (proxy, method, args) -> {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lane gate timeout");
                        return null;
                    }));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                for (long units : new long[]{10, -10, Long.MAX_VALUE, -Long.MAX_VALUE}) {
                    var change = live.message(CoreMessageType.ADJUST_POSITION_MARGIN, 11,
                            TradingCommandCodec.encodeAdjustPositionMargin(new AdjustPositionMarginCommand(
                                    "BTC-USDT", CoreMarginMode.ISOLATED, CorePositionSide.NET, units)));
                    live.apply(change); serial.apply(change);
                    assertThat(live.responses.getLast().commandStatus())
                            .isEqualTo(Math.abs(units) == 10 ? ResponseStatus.APPLIED : ResponseStatus.REJECTED);
                    assertThat(release.getCount()).isOne();
                }
            } finally { release.countDown(); }
            assertThat(live.service.state().tradingState().user(11).balances()).isEqualTo(before.balances());
            assertThat(live.service.state().tradingState().user(11).positions()).isEqualTo(before.positions());
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"SPOT", "OPTION"}, mode = EnumSource.Mode.EXCLUDE)
    void leverageChangesStayInTheAccountLane(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var field = live.service.state().runtimeState.getClass().getDeclaredField("laneWorkers");
            field.setAccessible(true);
            Object[] workers = (Object[]) field.get(live.service.state().runtimeState);
            int unrelated = (live.service.state().runtimeState.topology().accountLaneId(11) + 1) % workers.length;
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            Class<?> task = com.surprising.aeron.service.lane.SettlementLaneWorker.Command.class;
            var submit = workers[unrelated].getClass().getDeclaredMethod("submit", task);
            submit.setAccessible(true);
            submit.invoke(workers[unrelated], Proxy.newProxyInstance(task.getClassLoader(), new Class<?>[]{task},
                    (proxy, method, args) -> {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lane gate timeout");
                        return null;
                    }));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                for (long leverage : new long[]{2_000_000, 2_000_000, 1_000_000, 1_000_000_000}) {
                    var change = live.message(CoreMessageType.UPDATE_LEVERAGE, 11,
                            TradingCommandCodec.encodeUpdateLeverage(new UpdateLeverageCommand(
                                    "BTC-USDT", CoreMarginMode.CROSS, leverage)));
                    live.apply(change); serial.apply(change);
                    assertThat(live.responses.getLast().commandStatus())
                            .isEqualTo(leverage == 1_000_000_000 ? ResponseStatus.REJECTED : ResponseStatus.APPLIED);
                    assertThat(release.getCount()).isOne();
                    var access = live.service.state().runtimeState.getClass().getDeclaredField("ownerLaneAccess");
                    access.setAccessible(true);
                    assertThat(access.getBoolean(live.service.state().runtimeState)).isFalse();
                }
            } finally { release.countDown(); }
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    private static String differentMatcherSymbol(TradingCoreRuntime state, String firstSymbol) {
        for (int i = 0; i < 256; i++) {
            String candidate = "PARTITION-" + i + "-USDT";
            if (state.matchingAdapter.matcherShardId(candidate) != state.matchingAdapter.matcherShardId(firstSymbol))
                return candidate;
        }
        throw new AssertionError("cannot find an independent order book partition");
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void fullIndependentWindowCompletesWithoutAnotherTimer(ProductLine product) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            String asset = ContractType.valueOf(product.contractTypeCode()).isInverse() ? "BTC" : "USDT";
            for (int i = 0; i < ClusterCommandWindow.DEFAULT_CAPACITY; i++) {
                var funds = live.message(CoreMessageType.ADJUST_BALANCE, 10000 + i,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20000)));
                live.apply(funds);
                serial.apply(funds);
            }
            var orders = new ArrayList<CoreMessage>();
            for (int i = 0; i < ClusterCommandWindow.DEFAULT_CAPACITY; i++)
                orders.add(live.place(10000 + i, "BTC-USDT", 1000 + i, 80, 1, CoreOrderSide.BUY));
            orders.forEach(live::send);
            // 满窗口本身就是确定性提交边界；后续日志回调只轮询，不再注入额外timer栅栏。
            live.progressUntil(() -> live.service.pendingCommandCount() == 0);
            serial.applyAll(orders);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().users())
                    .isEqualTo(serial.service.state().tradingState().users());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.state().snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void newAdmissionCanResumeRejectedBatchWithSuspendedCommit(ProductLine product) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            String asset = ContractType.valueOf(product.contractTypeCode()).isInverse() ? "BTC" : "USDT";
            var withdraw = live.message(CoreMessageType.ADJUST_BALANCE, 11,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, -20_000)));
            live.apply(withdraw); serial.apply(withdraw);
            live.responses.clear();
            var first = live.placeBatch(11, "BTC-USDT", 1000);
            live.send(first);
            var state = live.service.state();
            long sequence = state.matchingSequence(first.header().commandId());
            var batch = state.batches.batch(sequence);
            var pending = state.pendingMatching.get(sequence);
            // 模拟延迟激活已开始提交、但 Lane 批量准入尚未被 owner 收集的边界。
            state.batches.beginOrderBatchCommitContext(batch, pending);
            state.suspendMatchingCommitContext(pending);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!batch.placeBatchAdmissionEvent.complete() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(batch.placeBatchAdmissionEvent.complete()).isTrue();
            assertThat(batch.placeBatchAdmissionEvent.rejection()).isNotNull();
            var next = live.place(disjointUser(11), disjointSymbol("BTC-USDT"), 8000, 80, 1, CoreOrderSide.BUY);
            live.send(next);
            live.tick(); serial.apply(first); serial.apply(next);
            assertThat(live.responses).hasSize(2);
            assertThat(TradingOrderBatchCodec.decodeResult(live.responses.getFirst().data()).items())
                    .allSatisfy(item -> assertThat(item.status()).isEqualTo(ResponseStatus.REJECTED));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void batchMatcherWaitReleasesOwnerPublicationContext(ProductLine product) {
        for (boolean partialRejection : new boolean[]{false, true}) {
            try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
                serial.applyAll(live.setup());
                if (partialRejection) {
                    var resting = live.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
                    live.apply(resting); serial.apply(resting);
                }
                var batch = TradingOrderBatchCodec.decodePlaceOrderBatch(live.placeBatch(11, "BTC-USDT", 101).payload());
                var first = live.message(CoreMessageType.PLACE_ORDER_BATCH, 11,
                        TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(
                                partialRejection ? batch.orders() : batch.orders().subList(0, 1))));
                var next = live.place(disjointUser(11), disjointSymbol("BTC-USDT"),
                        disjointOrder(8000), 80, 1, CoreOrderSide.BUY);
                var state = live.service.state();
                live.responses.clear();
                live.send(first);
                long sequence = state.matchingSequence(first.header().commandId());
                live.service.pollCommands();
                assertThat(state.commits.commitPublicationDeferred()).as("batch waiting for matcher").isFalse();
                live.send(next);
                live.tick();
                assertThat(live.responses).hasSize(2).allSatisfy(response ->
                        assertThat(response.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
                assertThat(TradingOrderBatchCodec.decodeResult(live.responses.getFirst().data()).items()
                        .stream().filter(item -> item.status() == ResponseStatus.REJECTED).count())
                        .isEqualTo(partialRejection ? 1 : 0);
                serial.apply(first); serial.apply(next);
                assertThat(state.tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
                assertThat(live.hash()).isEqualTo(serial.hash());
                try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void ordinaryOrderLifecycleDoesNotAcquireLaneOwnership(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product)) {
            live.setup();
            var state = live.service.state();
            var beforeBalances = state.tradingState().user(11).balances();
            var epoch = state.runtimeState.getClass().getDeclaredField("laneHandoffEpoch");
            epoch.setAccessible(true);
            long before = epoch.getLong(state.runtimeState);
            live.apply(live.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY));
            live.apply(live.message(CoreMessageType.AMEND_ORDER, 11, TradingCommandCodec.encodeAmendOrder(
                    new AmendOrderCommand(101, 102, "amend-102", 90L, 1L, CoreTimeInForce.GTC, false))));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            live.apply(live.message(CoreMessageType.REPLACE_ORDER, 11, TradingCommandCodec.encodeReplaceOrder(
                    new ReplaceOrderCommand(102, new PlaceOrderCommand(103, "BTC-USDT",
                            CoreOrderSide.BUY, 80, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "replace-103")))));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            live.apply(live.cancel(11, 103));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            live.apply(live.placeBatch(11, "BTC-USDT", 201));
            live.apply(live.cancelBatch(11, 201));
            assertThat(TradingOrderBatchCodec.firstNonAppliedItem(live.responses.getLast(), 20)).isEqualTo(-1);
            live.apply(live.cancel(11, 999_999));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(epoch.getLong(state.runtimeState)).isEqualTo(before);
            assertThat(state.tradingState().user(11).balances()).isEqualTo(beforeBalances);
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void rejectedAmendCommitsKnownCancellationPrefix(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product)) {
            live.setup();
            long user = 11, maker = disjointUser(user);
            if (product == ProductLine.SPOT) for (long account : new long[]{user, maker})
                live.apply(live.message(CoreMessageType.ADJUST_BALANCE, account,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 100))));
            live.apply(live.place(user, "BTC-USDT", 101, 100, 1, CoreOrderSide.SELL));
            live.apply(live.place(user, "BTC-USDT", 102, 80, 1, CoreOrderSide.BUY));
            live.apply(live.place(maker, "BTC-USDT", 103, 100, 1, CoreOrderSide.SELL));
            var epoch = live.service.state().runtimeState.getClass().getDeclaredField("laneHandoffEpoch");
            epoch.setAccessible(true);
            long handoffBefore = epoch.getLong(live.service.state().runtimeState);
            live.responses.clear();
            live.apply(live.message(CoreMessageType.AMEND_ORDER, user, TradingCommandCodec.encodeAmendOrder(
                    new AmendOrderCommand(102, 104, "reject-104", 100L, 1L, CoreTimeInForce.GTX, true))));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(epoch.getLong(live.service.state().runtimeState)).isEqualTo(handoffBefore);
            var result = CoreCommandResultCodec.decode(live.responses.getLast().data());
            assertThat(result.orders()).filteredOn(order -> order.orderId() == 102)
                    .hasSize(1).allSatisfy(order -> assertThat(order.status()).isEqualTo("CANCELED"));
            var runtime = live.service.state().runtimeState;
            assertThat(runtime.order(101)).isNull();
            assertThat(runtime.reservation(101)).isNull();
            assertThat(runtime.order(102)).isNull();
            assertThat(runtime.order(104)).isNull();
            assertThat(runtime.reservation(102)).isNull();
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void placeAdmissionReturnsBorrowedOwnershipBeforeLaneWork(ProductLine product) throws Exception {
        for (boolean batch : new boolean[]{false, true}) {
            try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
                serial.applyAll(live.setup());
                var state = live.service.state();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!state.runtimeState.tryAcquireOwnerLaneAccess()) {
                    if (System.nanoTime() >= deadline) throw new AssertionError("ownership timeout");
                    Thread.yield();
                }
                var command = batch ? live.placeBatch(11, "BTC-USDT", 101)
                        : live.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
                long timestamp = command.header().submittedAtEpochMillis();
                state.applyClusterCommand(command, timestamp, 0);
                var access = state.runtimeState.getClass().getDeclaredField("ownerLaneAccess");
                access.setAccessible(true);
                assertThat(access.getBoolean(state.runtimeState)).as("batch=%s", batch).isFalse();
                long sequence = state.matchingSequence(command.header().commandId());
                assertThat(CoreTestCompletion.completeMatchingSynchronously(state, sequence, timestamp, 0).commandStatus())
                        .isEqualTo(ResponseStatus.APPLIED);
                serial.apply(command);
                assertThat(state.tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
                assertThat(live.hash()).isEqualTo(serial.hash());
                try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void laneCompletionPollPreservesCommitContextAndDoesNotWaitForUnrelatedWork(ProductLine product) throws Exception {
        for (CoreMessageType type : List.of(CoreMessageType.PLACE_ORDER, CoreMessageType.CANCEL_ORDER,
                CoreMessageType.AMEND_ORDER, CoreMessageType.CANCEL_ORDER_BATCH)) {
            try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
                serial.applyAll(live.setup());
                if (type != CoreMessageType.PLACE_ORDER) {
                    var resting = type == CoreMessageType.CANCEL_ORDER_BATCH
                            ? live.placeBatch(11, "BTC-USDT", 101)
                            : live.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
                    live.apply(resting); serial.apply(resting);
                }
                CoreMessage first = switch (type) {
                    case CANCEL_ORDER -> live.cancel(11, 101);
                    case CANCEL_ORDER_BATCH -> live.cancelBatch(11, 101);
                    case AMEND_ORDER -> live.message(type, 11, TradingCommandCodec.encodeAmendOrder(
                            new AmendOrderCommand(101, 102, "replace-102", 81L, 1L, CoreTimeInForce.GTC, false)));
                    default -> live.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
                };
                var state = live.service.state();
                long timestamp = first.header().submittedAtEpochMillis();
                state.applyClusterCommand(first, timestamp, 0);
                long sequence = state.matchingSequence(first.header().commandId());
                var matching = CoreTestCompletion.awaitMatchingResult(state, sequence);
                assertThat(matching).isNotNull();
                var field = state.runtimeState.getClass().getDeclaredField("laneWorkers");
                field.setAccessible(true);
                Object[] workers = (Object[]) field.get(state.runtimeState);
                var entered = new CountDownLatch(workers.length);
                var release = new CountDownLatch(1);
                Class<?> task = com.surprising.aeron.service.lane.SettlementLaneWorker.Command.class;
                var submit = workers[0].getClass().getDeclaredMethod("submit", task);
                submit.setAccessible(true);
                CoreMessage next = live.place(disjointUser(11), disjointSymbol("BTC-USDT"),
                        disjointOrder(8000), 80, 1, CoreOrderSide.BUY);
                CoreResponse firstResponse = null;
                var firstPending = state.pendingMatching(sequence);
                var firstEvent = firstPending.orderBatch != null
                        ? firstPending.orderBatch.itemSettlementEvent : firstPending.settlementEvent();
                try {
                    for (Object worker : workers) submit.invoke(worker, Proxy.newProxyInstance(task.getClassLoader(),
                            new Class<?>[]{task}, (proxy, method, args) -> {
                                entered.countDown();
                                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lane release timeout");
                                return null;
                    }));
                    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                    // Dispatched only means the event entered the route.  Observe completion
                    // after the barrier is established so this assertion does not race that
                    // final Lane transition.
                    boolean alreadySettled = firstEvent != null && firstEvent.direct()
                            && firstEvent.dispatched() && firstEvent.complete();
                    firstResponse = state.completeMatching(sequence, matching, timestamp, 0);
                    if (alreadySettled) {
                        assertThat(firstResponse).as("%s already settled", type).isNotNull();
                        assertThat(firstResponse.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                        assertThat(state.pendingMatching(sequence)).isNull();
                    } else if (firstResponse == null) {
                        for (int i = 0; i < 3; i++) {
                            CoreResponse polled = state.completeMatching(sequence, matching, timestamp, 0);
                            if (polled != null) {
                                firstResponse = polled;
                                assertThat(polled.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                                assertThat(state.pendingMatching(sequence)).isNull();
                                break;
                            }
                            assertThat(state.commits.commitPublicationDeferred()).isFalse();
                            assertThat(state.laneCommandContexts.required(sequence).hasCommitContext()).isTrue();
                        }
                    } else {
                        assertThat(firstResponse.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                        assertThat(state.pendingMatching(sequence)).isNull();
                    }
                    state.applyClusterCommand(next, next.header().submittedAtEpochMillis(), 0);
                } finally { release.countDown(); }
                if (firstResponse == null)
                    assertThat(CoreTestCompletion.completeMatchingSynchronously(state, sequence, timestamp, 0).commandStatus())
                            .isEqualTo(ResponseStatus.APPLIED);
                long nextSequence = state.matchingSequence(next.header().commandId());
                assertThat(CoreTestCompletion.completeMatchingSynchronously(state, nextSequence, next.header().submittedAtEpochMillis(), 0).commandStatus())
                        .isEqualTo(ResponseStatus.APPLIED);
                serial.apply(first); serial.apply(next);
                assertThat(serial.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(state.tradingState().users()).as("%s account parity", type)
                        .isEqualTo(serial.service.state().tradingState().users());
                assertThat(live.hash()).as("%s hash parity", type).isEqualTo(serial.hash());
                try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
            }
        }
    }
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void triggerChildUsesOwningLaneAndPreservesOrderIdCollision(ProductLine product) throws Exception {
        for (boolean internalScan : new boolean[]{false, true}) {
        if (internalScan && product == ProductLine.SPOT) continue; // Spot has no mark-price risk scan.
        for (int matchedQuantity : new int[]{0, 1, 10}) {
            boolean fill = matchedQuantity != 0;
            try (Fixture live = new Fixture(product)) {
                live.setup();
                long maker = 11, user = disjointUser(maker);
                if (product == ProductLine.SPOT) live.apply(live.message(CoreMessageType.ADJUST_BALANCE, maker,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 100))));
                live.apply(live.place(maker, "BTC-USDT", 101, 100, 10, CoreOrderSide.SELL));
                live.apply(live.place(user, "BTC-USDT", 102, 100, 10, CoreOrderSide.BUY));
                // 子订单编号冲突时必须沿用准入阶段选定的编号，不能重新推导或回查 Lane。
                live.apply(live.place(user, "BTC-USDT", 18003, 80, 1, CoreOrderSide.BUY));
                if (fill) live.apply(live.place(maker, "BTC-USDT", 103, 100, matchedQuantity, CoreOrderSide.BUY));
                var trigger = new CoreTriggerOrderStateView(9001, product, user, "trigger-9001", "oco-9001", "BTC-USDT",
                        CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL,
                        100, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.IOC, fill ? 100 : 110, Math.max(1, matchedQuantity),
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                        0, 0, 0, "", "test", 0, 0, 0, 0, 1, 0, 0);
                live.apply(live.message(CoreMessageType.PLACE_TRIGGER_ORDER, user, CoreTriggerOrderCodec.encodeState(trigger)));
                var sibling = new CoreTriggerOrderStateView(9002, product, user, "trigger-9002", "oco-9001", "BTC-USDT",
                        CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL,
                        200, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.IOC, 200, 1,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                        0, 0, 0, "", "test", 0, 0, 0, 0, 1, 0, 0);
                live.apply(live.message(CoreMessageType.PLACE_TRIGGER_ORDER, user, CoreTriggerOrderCodec.encodeState(sibling)));
                live.responses.clear();
                var handoff = com.surprising.aeron.service.state.TradingRuntimeState.class.getDeclaredField("laneHandoffEpoch");
                handoff.setAccessible(true);
                long beforeHandoff = handoff.getLong(live.service.state().runtimeState);
                if (internalScan) scanTriggers(live, 1);
                else live.apply(live.message(CoreMessageType.EXECUTE_TRIGGER_ORDER, 0,
                        CoreTriggerOrderCodec.encodeExecute(9001, 1, 100, TIME)));
                assertThat(handoff.getLong(live.service.state().runtimeState))
                        .as("trigger claim/reserve/settle stays on permanent Account Lane")
                        .isEqualTo(beforeHandoff);
                assertThat(live.responses).isNotEmpty().allSatisfy(r ->
                        assertThat(r.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
                var terminal = live.service.state().runtimeState.triggerOrder(9001);
                assertThat(terminal.status()).isEqualTo(CoreTriggerOrderStatus.TRIGGERED);
                assertThat(terminal.placedOrderId()).isEqualTo(18005);
                assertThat(live.service.state().runtimeState.triggerOrder(9002).status())
                        .isEqualTo(CoreTriggerOrderStatus.CANCELED);
                try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
                live.apply(live.cancel(user, 18003));
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
        }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void nativeTriggerRejectionReleasesReservationAndCompletesOnItsLane(ProductLine product) throws Exception {
        for (boolean internalScan : new boolean[]{false}) {
        if (internalScan && product == ProductLine.SPOT) continue; // Spot has no mark-price risk scan.
        for (int matchedQuantity : new int[]{1}) {
            boolean fill = matchedQuantity != 0;
            try (Fixture live = new Fixture(product)) {
                live.setup();
                long maker = 11, user = disjointUser(maker);
                if (product == ProductLine.SPOT) live.apply(live.message(CoreMessageType.ADJUST_BALANCE, maker,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 100))));
                live.apply(live.place(maker, "BTC-USDT", 101, 100, 10, CoreOrderSide.SELL));
                live.apply(live.place(user, "BTC-USDT", 102, 100, 10, CoreOrderSide.BUY));
                // 子订单编号冲突时必须沿用准入阶段选定的编号，不能重新推导或回查 Lane。
                live.apply(live.place(user, "BTC-USDT", 18003, 80, 1, CoreOrderSide.BUY));
                if (fill) live.apply(live.place(maker, "BTC-USDT", 103, 100, matchedQuantity, CoreOrderSide.BUY));
                var trigger = new CoreTriggerOrderStateView(9001, product, user, "trigger-9001", "oco-9001", "BTC-USDT",
                        CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL,
                        100, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.GTX, fill ? 100 : 110, Math.max(1, matchedQuantity),
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                        0, 0, 0, "", "test", 0, 0, 0, 0, 1, 0, 0);
                live.apply(live.message(CoreMessageType.PLACE_TRIGGER_ORDER, user, CoreTriggerOrderCodec.encodeState(trigger)));
                var sibling = new CoreTriggerOrderStateView(9002, product, user, "trigger-9002", "oco-9001", "BTC-USDT",
                        CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL,
                        200, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.IOC, 200, 1,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                        0, 0, 0, "", "test", 0, 0, 0, 0, 1, 0, 0);
                live.apply(live.message(CoreMessageType.PLACE_TRIGGER_ORDER, user, CoreTriggerOrderCodec.encodeState(sibling)));
                live.responses.clear();
                var handoff = com.surprising.aeron.service.state.TradingRuntimeState.class.getDeclaredField("laneHandoffEpoch");
                handoff.setAccessible(true);
                long beforeHandoff = handoff.getLong(live.service.state().runtimeState);
                if (internalScan) scanTriggers(live, 1);
                else live.apply(live.message(CoreMessageType.EXECUTE_TRIGGER_ORDER, 0,
                        CoreTriggerOrderCodec.encodeExecute(9001, 1, 100, TIME)));
                assertThat(handoff.getLong(live.service.state().runtimeState))
                        .as("trigger claim/reserve/settle stays on permanent Account Lane")
                        .isEqualTo(beforeHandoff);
                assertThat(live.responses).isNotEmpty().allSatisfy(r ->
                        assertThat(r.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
                assertThat(live.service.state().runtimeState.reservation(18005)).isNull();
                assertThat(live.service.state().runtimeState.order(18005).status())
                        .isEqualTo(com.surprising.aeron.service.state.model.CoreOrderStatus.REJECTED);
                var terminal = live.service.state().runtimeState.triggerOrder(9001);
                assertThat(terminal.status()).isEqualTo(CoreTriggerOrderStatus.TRIGGER_FAILED);
                assertThat(terminal.placedOrderId()).isZero();
                assertThat(live.service.state().runtimeState.triggerOrder(9002).status())
                        .isEqualTo(CoreTriggerOrderStatus.CANCELED);
                try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
                live.apply(live.cancel(user, 18003));
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
        }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void triggerChildFailureClosesOrRollsBackOcoOnItsLane(boolean overflow) throws Exception {
        try (Fixture live = new Fixture(ProductLine.SPOT)) {
            live.setup();
            long user = 11;
            for (long id : new long[]{9101, 9102}) {
                var trigger = new CoreTriggerOrderStateView(id, ProductLine.SPOT, user, "failure-" + id,
                        "failure-oco", "BTC-USDT", overflow ? CoreOrderSide.BUY : CoreOrderSide.SELL,
                        CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL,
                        100, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.IOC, 100,
                        overflow && id == 9101 ? Long.MAX_VALUE : 1,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                        0, 0, 0, "", "test", 0, 0, 0, 0, 1, 0, 0);
                live.apply(live.message(CoreMessageType.PLACE_TRIGGER_ORDER, user, CoreTriggerOrderCodec.encodeState(trigger)));
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
            var runtime = live.service.state().runtimeState;
            var epoch = runtime.getClass().getDeclaredField("laneHandoffEpoch");
            epoch.setAccessible(true);
            long before = epoch.getLong(runtime);
            var accountsBefore = live.service.state().tradingState().users();
            live.responses.clear();
            live.apply(live.message(CoreMessageType.EXECUTE_TRIGGER_ORDER, user,
                    CoreTriggerOrderCodec.encodeExecute(9101, 1, 100, TIME)));
            assertThat(epoch.getLong(runtime)).isEqualTo(before);
            assertThat(live.responses.getLast().commandStatus())
                    .isEqualTo(overflow ? ResponseStatus.REJECTED : ResponseStatus.APPLIED);
            assertThat(runtime.triggerOrder(9101).status())
                    .isEqualTo(overflow ? CoreTriggerOrderStatus.PENDING : CoreTriggerOrderStatus.TRIGGER_FAILED);
            assertThat(runtime.triggerOrder(9102).status())
                    .isEqualTo(overflow ? CoreTriggerOrderStatus.PENDING : CoreTriggerOrderStatus.CANCELED);
            assertThat(runtime.order(18203)).isNull();
            assertThat(live.service.state().identities.findClientKey(user, "TRIGGER:9101")).isNull();
            assertThat(live.service.state().tradingState().users()).isEqualTo(accountsBefore);
            try (var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, live.service.captureSnapshot(101))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentIngressContinuesWhileEarlierCommitPrefixWaitsForLanes(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var first = live.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
            var second = live.place(disjointUser(11), "BTC-USDT", disjointOrder(101), 81, 1, CoreOrderSide.BUY);
            var runtime = live.service.state().runtimeState;
            var field = runtime.getClass().getDeclaredField("laneWorkers");
            field.setAccessible(true);
            Object[] workers = (Object[]) field.get(runtime);
            var entered = new CountDownLatch(workers.length);
            var release = new CountDownLatch(1);
            Class<?> task = com.surprising.aeron.service.lane.SettlementLaneWorker.Command.class;
            var submit = workers[0].getClass().getDeclaredMethod("submit", task);
            submit.setAccessible(true);
            try {
                for (Object worker : workers) submit.invoke(worker, Proxy.newProxyInstance(task.getClassLoader(),
                        new Class<?>[]{task}, (proxy, method, args) -> {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lane release timeout");
                            return null;
                        }));
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(first);
                live.service.pollCommands();
                live.send(second);
                assertThat(live.service.commandWindowSize()).isEqualTo(2);
                assertThat(live.responses).isEmpty();
            } finally { release.countDown(); }
            live.tick(); serial.apply(first); serial.apply(second);
            assertThat(live.responses).hasSize(2).allSatisfy(response ->
                    assertThat(response.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void singleItemPlaceBatchKeepsAccountOwnershipOnLane(ProductLine product) throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
                serial.applyAll(live.setup());
                long maker = disjointUser(11);
                if (scenario == 1) {
                    if (product == ProductLine.SPOT) {
                        var funds = live.message(CoreMessageType.ADJUST_BALANCE, maker,
                                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 20_000)));
                        live.apply(funds); serial.apply(funds);
                    }
                    var liquidity = live.place(maker, "BTC-USDT", 7900, 80, 1, CoreOrderSide.SELL);
                    live.apply(liquidity); serial.apply(liquidity);
                }
                var item = new PlaceOrderCommand(8000, "BTC-USDT", CoreOrderSide.BUY, 80,
                        scenario == 2 ? Long.MAX_VALUE : 1, false, CoreMarginMode.CROSS,
                        CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "single-batch");
                var batch = live.message(CoreMessageType.PLACE_ORDER_BATCH, 11,
                        TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(item))));
                var runtime = live.service.state().runtimeState;
                var epoch = runtime.getClass().getDeclaredField("laneHandoffEpoch");
                epoch.setAccessible(true);
                long before = epoch.getLong(runtime);
                live.responses.clear();
                live.apply(batch); serial.apply(batch);
                assertThat(epoch.getLong(runtime)).as("single-item admission/settlement never takes Lane ownership")
                        .isEqualTo(before);
                var result = TradingOrderBatchCodec.decodeResult(live.responses.getFirst().data()).items();
                assertThat(result).hasSize(1);
                assertThat(result.getFirst().status()).isEqualTo(
                        scenario == 2 ? ResponseStatus.REJECTED : ResponseStatus.APPLIED);
                assertThat(result.getFirst().executions()).hasSize(scenario == 1 ? 1 : 0);
                assertThat(live.hash()).isEqualTo(serial.hash());
                try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
            }
        }
    }

    @Test
    void deferredSequentialBatchUsesOriginalLogTimeEvenWhenLaterIngressIsAfterExpiry() {
        try (Fixture live = new Fixture(ProductLine.LINEAR_DELIVERY);
             Fixture serial = new Fixture(ProductLine.LINEAR_DELIVERY)) {
            serial.applyAll(live.setup());
            var item = new PlaceOrderCommand(1000, "BTC-USDT", CoreOrderSide.BUY, 80, 1,
                    false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                    CoreTimeInForce.GTC, false, "before-expiry");
            var batch = live.message(CoreMessageType.PLACE_ORDER_BATCH, 11,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(item))));
            var original = live.place(disjointUser(11), disjointSymbol("BTC-USDT"), 2000, 80, 1, CoreOrderSide.BUY);
            var h = original.header();
            var later = new CoreMessage(CoreMessageHeader.command(h.messageType(), h.commandId(),
                    ProductLine.LINEAR_DELIVERY, CommandSource.OPERATIONS, 990, h.sourceSequence(),
                    h.userId(), TIME + 200_000, h.correlationId()), original.payloadUnsafe());
            live.send(batch); live.send(later); live.tick();
            serial.apply(batch); serial.apply(later);
            assertThat(TradingOrderBatchCodec.decodeResult(live.responses.getFirst().data()).items())
                    .singleElement().satisfies(result -> assertThat(result.status()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash());
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void sameSymbolIndependentOrdersRemainInFlightAndRecoverLikeSerialExecution(ProductLine product) {
        sameSymbolIndependentOrders(product, false);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void sameSymbolIndependentBatchesRemainInFlightAndRecoverLikeSerialExecution(ProductLine product) {
        sameSymbolIndependentOrders(product, true);
    }

    private void sameSymbolIndependentOrders(ProductLine product, boolean batch) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product);
             Fixture replay = new Fixture(product, Cluster.Role.FOLLOWER)) {
            var setup = live.setup(); serial.applyAll(setup); replay.applyAll(setup);
            var first = batch ? live.placeBatch(11, "BTC-USDT", 100)
                    : live.place(11, "BTC-USDT", 100, 80, 1, CoreOrderSide.BUY);
            var second = batch ? live.placeBatch(disjointUser(11), "BTC-USDT", 200)
                    : live.place(disjointUser(11), "BTC-USDT", 200, 81, 1, CoreOrderSide.BUY);
            live.send(first); live.send(second);
            assertThat(live.service.commandWindowSize()).isEqualTo(2);
            assertThat(live.responses).isEmpty();
            live.tick(); serial.apply(first); serial.apply(second);
            replay.send(first); replay.send(second); replay.tick();
            assertThat(live.responses).hasSize(2).allSatisfy(r ->
                    assertThat(r.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash()).isEqualTo(replay.hash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void ordinaryCommitAndSpeculativeDispatchCannotSubmitTheSameBatchTwice(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var state = live.service.state();
            var command = live.placeBatch(11, "BTC-USDT", 8000);
            long timestamp = command.header().submittedAtEpochMillis();
            state.applyClusterCommand(command, timestamp, 0);
            long sequence = state.matchingSequence(command.header().commandId());
            var contextField = TradingCoreRuntime.class.getDeclaredField("laneCommandContexts");
            contextField.setAccessible(true);
            var contexts = (CommandSlotRing) contextField.get(state);
            var context = contexts.required(sequence);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!context.hasMatchingCompletion() && System.nanoTime() < deadline) state.drainMatchingCompletions();
            assertThat(context.hasMatchingCompletion()).isTrue();
            var runtimeField = TradingCoreRuntime.class.getDeclaredField("runtimeState");
            runtimeField.setAccessible(true);
            Object runtime = runtimeField.get(state);
            var workersField = runtime.getClass().getDeclaredField("laneWorkers");
            workersField.setAccessible(true);
            Object[] workers = (Object[]) workersField.get(runtime);
            var entered = new CountDownLatch(workers.length);
            var release = new CountDownLatch(1);
            Class<?> commandClass = com.surprising.aeron.service.lane.SettlementLaneWorker.Command.class;
            var submit = workers[0].getClass().getDeclaredMethod("submit", commandClass);
            submit.setAccessible(true);
            try {
                for (Object worker : workers) submit.invoke(worker, Proxy.newProxyInstance(commandClass.getClassLoader(),
                        new Class<?>[]{commandClass}, (p, method, args) -> {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test lane timeout");
                            return null;
                        }));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(state.completeMatching(sequence, context.takeMatchingCompletion(), timestamp, 0)).isNull();
                var batch = state.pendingMatching.get(sequence).orderBatch;
                var eventsField = batch.getClass().getDeclaredField("settlementEvent");
                eventsField.setAccessible(true);
                Object dispatched = eventsField.get(batch);
                assertThat(dispatched).isNotNull();
                var dispatch = OrderedCommitCoordinator.class.getDeclaredMethod("dispatchReadyPlaceSettlements", long.class, long.class, long.class);
                dispatch.setAccessible(true);
                dispatch.invoke(state.commits, timestamp, 0L, sequence);
                assertThat(eventsField.get(batch)).as("one Lane settlement event per batch sequence").isSameAs(dispatched);
            } finally { release.countDown(); }
            assertThat(CoreTestCompletion.completeMatchingSynchronously(state, sequence, timestamp, 0).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            serial.apply(command);
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentBatchesSharingLanesKeepSettlementSequenceOrdered(ProductLine product) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var type = ContractType.valueOf(product.contractTypeCode());
            String asset = type.isInverse() ? "BTC" : "USDT";
            var batches = new ArrayList<CoreMessage>();
            for (int i = 0; i < 64; i++) {
                String symbol = "COIN" + i + "-USDT";
                long user = 10_000 + i;
                var setup = List.of(
                        live.message(CoreMessageType.REGISTER_INSTRUMENT, 0,
                                TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(symbol,
                                        type.ordinal(), "BTC", "USDT", asset, 1, 1, type.isInverse() ? 1_000 : 1,
                                        100_000, 50_000, 0, 0, type.isDelivery() || type.isOption() ? TIME + 100_000 : 0,
                                        type.isOption() ? 0 : -1, type.isOption() ? 100 : 0))),
                        live.message(CoreMessageType.APPLY_MARK_PRICE, 0,
                                TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                                        ? new ApplyMarkPriceCommand(symbol, 100, 100, 100, 1, TIME)
                                        : new ApplyMarkPriceCommand(symbol, 100, 1, TIME))),
                        live.message(CoreMessageType.ADJUST_BALANCE, user,
                                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))));
                live.applyAll(setup); serial.applyAll(setup);
            }
            for (int i = 0; i < 64; i++) batches.add(live.placeBatch(10_000 + i, "COIN" + i + "-USDT", 10_000 + i * 20));
            live.responses.clear();
            for (var command : batches) live.send(command);
            live.tick(); serial.applyAll(batches);
            assertThat(live.responses).hasSize(64).allSatisfy(r -> assertThat(r.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash());
        }
    }
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void cancellationFencesRemovedLiquidityWithoutBlockingTheWholeSymbol(ProductLine product) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var resting = live.place(11, "BTC-USDT", 4100, 80, 1, CoreOrderSide.BUY);
            live.apply(resting); serial.apply(resting);
            var cancel = live.cancel(11, 4100);
            long other = disjointUser(11);
            var independent = live.place(other, "BTC-USDT", 4200, 80, 1, CoreOrderSide.BUY);
            var window = new ClusterCommandWindow();
            var state = live.service.state();
            assertThat(state.prepareClusterPipelineScope(cancel, window)).isTrue();
            window.add(null, cancel, TIME, 1);
            assertThat(state.prepareClusterPipelineScope(independent, window)).isTrue();
            assertThat(window.conflicts()).as("same-side different-account order").isFalse();
            assertThat(state.prepareClusterPipelineScope(
                    live.place(other, "BTC-USDT", 4201, 81, 1, CoreOrderSide.SELL), window)).isTrue();
            assertThat(window.conflicts()).as("sell cannot consume the removed buy").isFalse();
            assertThat(state.prepareClusterPipelineScope(
                    live.place(other, "BTC-USDT", 4202, 80, 1, CoreOrderSide.SELL), window)).isTrue();
            // Matching-range/counterparty fencing is Matcher-owned now; Owner sees only
            // the submitting account Lane and exact order identities.
            assertThat(window.conflicts()).as("crossing sell is not an Owner ingress dependency").isFalse();
            assertThat(state.prepareClusterPipelineScope(
                    live.place(11, "BTC-USDT", 4203, 79, 1, CoreOrderSide.BUY), window)).isTrue();
            assertThat(window.conflicts()).as("same account must observe released funds").isTrue();
            assertThat(state.prepareClusterPipelineScope(cancel, window)).isTrue();
            assertThat(window.conflicts()).as("same order identity remains fenced").isTrue();
            live.responses.clear();
            live.send(cancel); live.send(independent); live.tick();
            serial.apply(cancel); serial.apply(independent);
            assertThat(live.responses).hasSize(2)
                    .allSatisfy(result -> assertThat(result.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void scopeRefreshAndAdmissionShareOneDecodedBatch(ProductLine product) throws Exception {
        try (var fixture = new Fixture(product)) {
            fixture.setup();
            var window = new ClusterCommandWindow();
            var command = fixture.placeBatch(11, "BTC-USDT", 5000);
            var state = fixture.service.state();
            assertThat(state.prepareClusterPipelineScope(command, window)).isTrue();
            var decoded = window.decoded(command);
            assertThat(state.prepareClusterPipelineScope(command, window)).isTrue();
            assertThat(window.decoded(command)).isSameAs(decoded);
            state.applyClusterCommand(command, TIME, 1000, decoded);
            var pending = state.pendingMatching(state.matchingSequence(command.header().commandId()));
            assertThat(pending.decodedCommand()).isSameAs(decoded);
            var completed = CoreTestCompletion.completeMatchingSynchronously(state, pending.sequence(), TIME, 1000);
            assertThat(completed.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(TradingOrderBatchCodec.decodeResult(completed.data()).items()).hasSize(20);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void prefixCommitReturnsWhileIndependentSuffixMatcherIsStillBlocked(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product)) {
            live.setup();
            var a = live.place(11, "BTC-USDT", 1000, 80, 1, CoreOrderSide.BUY);
            var b = live.place(disjointUser(11), disjointSymbol("BTC-USDT"), 2000, 80, 1, CoreOrderSide.BUY);
            var c = live.place(11, "BTC-USDT", 3000, 79, 1, CoreOrderSide.BUY);
            live.send(a);
            var pending = live.service.state().pendingMatching(live.service.state().matchingSequence(a.header().commandId()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!pending.isMatchingSubmitted() && System.nanoTime() < deadline)
                live.service.state().drainMatchingCompletions();
            assertThat(pending.isMatchingSubmitted()).isTrue();
            var field = TradingCoreRuntime.class.getDeclaredField("matcherPipeline"); field.setAccessible(true);
            var matcher = (MatcherPipelineGroup) field.get(live.service.state());
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var blocked = matcher.readAtSubmissionFence(0, () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("suffix matcher timeout"); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                return 1;
            });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(b); live.send(c);
                live.progressUntil(() -> live.responses.size() == 1);
                assertThat(blocked.isDone()).isFalse();
                assertThat(live.responses).hasSize(1);
                assertThat(live.service.commandWindowSize()).isEqualTo(2);
            } finally { release.countDown(); }
            live.tick();
            assertThat(blocked.join()).isOne();
            assertThat(live.responses).hasSize(3);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void dependencyDrainsOnlyItsOrderedPrefixAndKeepsIndependentSuffix(ProductLine product) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product);
             Fixture replay = new Fixture(product, Cluster.Role.FOLLOWER)) {
            var setup = live.setup(); serial.applyAll(setup); replay.applyAll(setup);
            // Real fills in both independent batches; the suffix must not enter the prefix's realtime export.
            LaneTopology topology = live.service.state().runtimeState.topology();
            long used = topology.accountLaneMask(11) | topology.accountLaneMask(disjointUser(11));
            int symbolNumber = 0;
            for (String symbol : List.of("BTC-USDT", disjointSymbol("BTC-USDT"))) {
                long seller = 100;
                while ((topology.accountLaneMask(seller) & used) != 0) seller++;
                used |= topology.accountLaneMask(seller);
                ContractType type = ContractType.valueOf(product.contractTypeCode());
                String asset = product == ProductLine.SPOT || type.isInverse() ? "BTC" : "USDT";
                var deposit = live.message(CoreMessageType.ADJUST_BALANCE, seller,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000)));
                var ask = live.place(seller, symbol, 100 + symbolNumber++, 80, 20, CoreOrderSide.SELL);
                for (Fixture fixture : List.of(live, serial, replay)) { fixture.apply(deposit); fixture.apply(ask); }
            }
            live.responses.clear();
            var outbox = new com.surprising.aeron.client.RealtimeOutbox(8192, 8 * 1024 * 1024);
            CoreFaults.attachRealtime(live.service, outbox);
            var a = live.placeBatch(11, "BTC-USDT", 1000);
            var b = live.placeBatch(disjointUser(11), disjointSymbol("BTC-USDT"), 2000);
            var c = live.place(11, "BTC-USDT", 3000, 79, 1, CoreOrderSide.BUY);
            serial.apply(a); serial.apply(b); serial.apply(c);
            // Faster polling may finish multiple prefixes per callback. Observe the actual first
            // response boundary instead of requiring a scheduling-dependent intermediate size.
            int[] prefixTrades = {0};
            live.firstResponse = () -> {
                byte[] bytes;
                while ((bytes = outbox.poll()) != null) {
                    var frame = RealtimeFrameCodec.decode(bytes);
                    if (frame.kind() == RealtimeFrame.Kind.TRADE) {
                        assertThat(frame.symbol()).isEqualTo("BTC-USDT");
                        prefixTrades[0]++;
                    }
                }
                assertThat(prefixTrades[0]).isEqualTo(20);
            };
            live.send(a); live.send(b); live.send(c);
            live.tick();
            assertThat(prefixTrades[0]).isEqualTo(20);
            replay.send(a); replay.send(b); replay.send(c); replay.tick();
            assertThat(live.responses).hasSize(3).allSatisfy(r -> assertThat(r.status()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash()).isEqualTo(replay.hash());
            assertThat(live.service.state().tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
            try (TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(880))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @Test
    void nonCrossingSharedRestingBuyerDoesNotSerializeIndependentSellers() {
        try (Fixture live = new Fixture(ProductLine.SPOT); Fixture serial = new Fixture(ProductLine.SPOT)) {
            serial.applyAll(live.setup());
            long a = 11, b = disjointUser(a), shared = b + 1;
            LaneTopology topology = live.service.state().runtimeState.topology();
            while ((topology.accountLaneMask(shared) & (topology.accountLaneMask(a) | topology.accountLaneMask(b))) != 0) shared++;
            var deposit = live.message(CoreMessageType.ADJUST_BALANCE, shared,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 20_000)));
            live.apply(deposit); serial.apply(deposit);
            for (long user : new long[]{a, b}) {
                var base = live.message(CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 100)));
                live.apply(base); serial.apply(base);
            }
            var lowA = live.place(shared, "BTC-USDT", 100, 80, 1, CoreOrderSide.BUY);
            var lowB = live.place(shared, disjointSymbol("BTC-USDT"), 200, 80, 1, CoreOrderSide.BUY);
            live.apply(lowA); live.apply(lowB); serial.apply(lowA); serial.apply(lowB);
            live.responses.clear();
            var sellA = live.place(a, "BTC-USDT", 300, 102, 1, CoreOrderSide.SELL);
            var sellB = live.place(b, disjointSymbol("BTC-USDT"), 400, 102, 1, CoreOrderSide.SELL);
            live.send(sellA); live.send(sellB);
            assertThat(live.service.commandWindowSize()).isEqualTo(2);
            assertThat(live.responses).isEmpty();
            live.tick(); serial.apply(sellA); serial.apply(sellB);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().order(100).executedQuantitySteps()).isZero();
            assertThat(live.service.state().tradingState().order(200).executedQuantitySteps()).isZero();
        }
    }
    private static final long TIME = 1_700_000_000_000L;

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void continuousBatchesDispatchSnapshotAtCommittedBoundary(ProductLine product) throws Exception {
        try (Fixture f = new Fixture(product)) {
            f.setup();
            var outbox = new com.surprising.aeron.client.RealtimeOutbox(1024, 1_048_576);
            CoreFaults.attachRealtime(f.service, outbox);
            var requests = f.service.snapshotRequests();
            f.send(f.placeBatch(11, "BTC-USDT", 1000));
            requests.add(new RealtimeFrame(product, RealtimeFrame.Kind.SNAPSHOT_REQUEST,
                    11, 0, 0, TIME, 900, "", "", new byte[0]));
            assertThat(f.service.doBackgroundWork(System.nanoTime())).isZero();
            f.send(f.placeBatch(11, "BTC-USDT", 2000));
            f.progressUntil(() -> requests.isEmpty());
            assertThat(f.service.commandWindowSize()).isOne();
            assertThat(requests.isEmpty()).as("continuous input must not starve a read at the preceding commit boundary").isTrue();
            var readsField = TradingCoreRuntime.class.getDeclaredField("realtimeReads");
            readsField.setAccessible(true);
            var pendingField = RealtimeReadCoordinator.class.getDeclaredField("pendingRealtimeSnapshot");
            pendingField.setAccessible(true);
            var snapshot = (java.util.concurrent.CompletableFuture<?>) pendingField.get(readsField.get(f.service.state()));
            assertThat(snapshot).isNotNull();
            snapshot.get(2, TimeUnit.SECONDS);
            f.send(f.cancelBatch(11, 1000));
            f.progressUntil(() -> !f.service.state().realtimeSnapshotPending());
            var frames = new ArrayList<RealtimeFrame>();
            byte[] bytes;
            while ((bytes = outbox.poll()) != null) {
                var frame = RealtimeFrameCodec.decode(bytes);
                if (frame.snapshotId() == 900) frames.add(frame);
            }
            assertThat(frames).anySatisfy(frame -> assertThat(frame.kind()).isEqualTo(RealtimeFrame.Kind.SNAPSHOT_END));
            assertThat(frames.stream().filter(frame -> frame.kind() == RealtimeFrame.Kind.ORDER)).hasSize(20);
            assertThat(frames).anySatisfy(frame -> {
                assertThat(frame.kind()).isEqualTo(RealtimeFrame.Kind.USER);
                assertThat(CoreStateQueryCodec.decodeUserState(frame.payload()).reservations()).hasSize(20);
            });
            f.tick();
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentCommandsEnterBeforeMatcherCompletesAndRecoverExactly(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product);
             Fixture replay = new Fixture(product, Cluster.Role.FOLLOWER)) {
            List<CoreMessage> setup = live.setup();
            serial.applyAll(setup); replay.applyAll(setup);
            long userA = 11, userB = disjointUser(userA);
            String symbolB = disjointSymbol("BTC-USDT");
            CoreMessage first = live.place(userA, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
            CoreMessage second = live.place(userB, symbolB, disjointOrder(101), 80, 1, CoreOrderSide.BUY);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var field = TradingCoreRuntime.class.getDeclaredField("matcherPipeline"); field.setAccessible(true);
            var matcher = (MatcherPipelineGroup) field.get(live.service.state());
            var blocked = matcher.readAtSubmissionFence(0, () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("matcher test timeout");
                } catch (InterruptedException e) { throw new IllegalStateException(e); }
                return 1;
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                live.send(first); live.send(second);
                assertThat(live.service.commandWindowSize()).isEqualTo(2);
                assertThat(live.responses).isEmpty();
                assertThat(live.service.doBackgroundWork(Long.MAX_VALUE)).isZero();
            } finally { release.countDown(); }
            live.tick(); assertThat(blocked.join()).isOne();
            serial.send(first); serial.tick(); serial.send(second); serial.tick();
            replay.send(first); replay.send(second); replay.tick();
            assertThat(live.responses).hasSize(2).allSatisfy(r -> assertThat(r.status()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash()).isEqualTo(replay.hash());
            assertThat(live.service.state().tradingState().order(101).createdAtEpochMillis())
                    .isEqualTo(first.header().submittedAtEpochMillis());
            assertThat(live.service.state().tradingState().order(disjointOrder(101)).createdAtEpochMillis())
                    .isEqualTo(second.header().submittedAtEpochMillis());
            assertThat(live.service.commandWindowHighWaterMark()).isEqualTo(2);
            var cancelA = live.cancel(userA, 101);
            var cancelB = live.cancel(userB, TradingCommandCodec.decodePlaceOrder(second.payloadUnsafe()).orderId());
            live.send(cancelA); live.send(cancelB);
            assertThat(live.service.commandWindowSize()).isEqualTo(2);
            // A snapshot is itself a deterministic fence and includes both cancellations.
            byte[] snapshot = live.service.captureSnapshot(100);
            serial.send(cancelA); serial.tick(); serial.send(cancelB); serial.tick();
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(product, snapshot)) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
            assertThat(live.service.commandWindowSize()).isZero();
            live.tick(); // Stale/duplicate timer must not complete or execute commands twice.
            assertThat(live.responses).hasSize(4);
        }
    }

    @Test
    void sameAccountCannotSpendTheSameBalanceTwiceAcrossSymbols() {
        try (Fixture f = new Fixture(ProductLine.SPOT)) {
            f.setup();
            f.apply(f.message(CoreMessageType.ADJUST_BALANCE, 11,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", -19_900))));
            f.responses.clear();
            f.send(f.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY));
            f.send(f.place(11, disjointSymbol("BTC-USDT"), disjointOrder(101), 80, 1, CoreOrderSide.BUY));
            // One owner poll may publish both the first terminal and the following rejection.
            f.progressUntil(() -> !f.responses.isEmpty());
            f.tick();
            assertThat(f.responses).hasSize(2);
            assertThat(f.responses.get(0).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(f.responses.get(1).commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            var balance = f.service.state().tradingState().user(11).balances().get("USDT");
            assertThat(balance.lockedUnits()).isEqualTo(80);
            assertThat(balance.availableUnits()).isEqualTo(20);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void rejectedBatchAndIndependentOrdinaryOrderKeepProgressAndFunds(ProductLine product) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            String asset = ContractType.valueOf(product.contractTypeCode()).isInverse() ? "BTC" : "USDT";
            var withdraw = live.message(CoreMessageType.ADJUST_BALANCE, 11,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, -20_000)));
            live.apply(withdraw); serial.apply(withdraw); live.responses.clear();
            var rejected = live.placeBatch(11, "BTC-USDT", 1000);
            var valid = live.place(disjointUser(11), disjointSymbol("BTC-USDT"), 2000, 80, 1, CoreOrderSide.BUY);
            live.send(rejected); live.send(valid);
            assertThat(live.service.commandWindowSize() + live.responses.size()).isEqualTo(2);
            live.tick(); serial.apply(rejected); serial.apply(valid);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.responses).hasSize(2);
            assertThat(TradingOrderBatchCodec.decodeResult(live.responses.getFirst().data()).items())
                    .allSatisfy(item -> assertThat(item.status()).isEqualTo(ResponseStatus.REJECTED));
            assertThat(live.responses.getLast().status()).isEqualTo(ResponseStatus.APPLIED);
            var cancel = live.cancel(disjointUser(11), 2000);
            live.apply(cancel); serial.apply(cancel);
            assertThat(live.hash()).isEqualTo(serial.hash());
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void cancellationBehindSlowIndependentPlaceAdmissionResumesInSubmissionOrder(ProductLine product) throws Exception {
        try (Fixture f = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(f.setup());
            long other = disjointUser(11);
            long restingId = disjointOrder(101);
            CoreMessage resting = f.place(other, disjointSymbol("BTC-USDT"), restingId, 80, 1, CoreOrderSide.BUY);
            f.apply(resting); serial.apply(resting);
            CoreMessage place = f.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
            CoreMessage cancel = f.cancel(other, restingId);
            var runtimeField = TradingCoreRuntime.class.getDeclaredField("runtimeState");
            runtimeField.setAccessible(true);
            Object runtime = runtimeField.get(f.service.state());
            var workersField = runtime.getClass().getDeclaredField("laneWorkers");
            workersField.setAccessible(true);
            Object[] workers = (Object[]) workersField.get(runtime);
            CountDownLatch entered = new CountDownLatch(workers.length), release = new CountDownLatch(1);
            Class<?> commandClass = com.surprising.aeron.service.lane.SettlementLaneWorker.Command.class;
            var submit = workers[0].getClass().getDeclaredMethod("submit", commandClass);
            submit.setAccessible(true);
            try {
                for (Object worker : workers) {
                    Object command = Proxy.newProxyInstance(commandClass.getClassLoader(), new Class<?>[]{commandClass},
                            (p, method, args) -> {
                                entered.countDown();
                                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test lane timeout");
                                return null;
                            });
                    submit.invoke(worker, command);
                }
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                f.send(place); f.send(cancel);
                assertThat(f.service.commandWindowSize()).isEqualTo(2);
                assertThat(f.service.state().pendingMatching(f.service.state().matchingSequence(cancel.header().commandId()))
                        .isMatchingSubmitted()).isFalse();
            } finally { release.countDown(); }
            var pending = f.service.state().pendingMatching(f.service.state().matchingSequence(cancel.header().commandId()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!pending.isMatchingSubmitted() && System.nanoTime() < deadline) {
                f.service.state().drainMatchingCompletions();
                Thread.onSpinWait();
            }
            assertThat(pending.isMatchingSubmitted()).as("deferred cancellation must resume after place admission").isTrue();
            f.tick(); serial.apply(place); serial.apply(cancel);
            assertThat(f.hash()).isEqualTo(serial.hash());
            assertThat(f.responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(ResponseStatus.APPLIED));
            try (TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(product, f.service.captureSnapshot(700))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(f.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentFillsSettleBothSidesAndMatchSerialFunds(ProductLine product) {
        independentFills(product, false);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentBatchFillsSettleBothSidesAndMatchSerialFunds(ProductLine product) {
        independentFills(product, true);
    }

    private void independentFills(ProductLine product, boolean batch) {
        independentFills(product, batch, false);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void collidingMakerMasksStillAllowIndependentBatchFillsAndRecovery(ProductLine product) {
        independentFills(product, true, true);
    }

    private void independentFills(ProductLine product, boolean batch, boolean collidingMakers) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            var setup = live.setup(); serial.applyAll(setup);
            LaneTopology topology = live.service.state().runtimeState.topology();
            long buyers = topology.accountLaneMask(11) | topology.accountLaneMask(disjointUser(11));
            long makerA = 77;
            while ((topology.accountLaneMask(makerA) & buyers) != 0) makerA++;
            long occupied = buyers | topology.accountLaneMask(makerA);
            long makerB = makerA + 1;
            if (collidingMakers) {
                while (topology.accountLaneMask(makerB) != topology.accountLaneMask(makerA)) makerB++;
            } else {
                while ((topology.accountLaneMask(makerB) & occupied) != 0) makerB++;
            }
            String asset = product == ProductLine.SPOT || ContractType.valueOf(product.contractTypeCode()).isInverse()
                    ? "BTC" : "USDT";
            String secondSymbol = disjointSymbol("BTC-USDT");
            var liquidity = List.of(
                    live.message(CoreMessageType.ADJUST_BALANCE, makerA,
                            TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))),
                    live.message(CoreMessageType.ADJUST_BALANCE, makerB,
                            TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))),
                    live.place(makerA, "BTC-USDT", 201, batch ? 80 : 100, batch ? 40 : 4, CoreOrderSide.SELL),
                    live.place(makerB, secondSymbol, 202, batch ? 80 : 100, batch ? 40 : 4, CoreOrderSide.SELL));
            live.applyAll(liquidity); serial.applyAll(liquidity);
            live.responses.clear();
            var outbox = new com.surprising.aeron.client.RealtimeOutbox(1024, 1_048_576);
            CoreFaults.attachRealtime(live.service, outbox);
            var first = batch ? live.placeBatch(11, "BTC-USDT", 1000)
                    : live.place(11, "BTC-USDT", 301, 100, 2, CoreOrderSide.BUY);
            var second = batch ? live.placeBatch(disjointUser(11), secondSymbol, 2000)
                    : live.place(disjointUser(11), secondSymbol, disjointOrder(301), 100, 2, CoreOrderSide.BUY);
            live.send(first); live.send(second);
            assertThat(live.service.commandWindowSize()).isEqualTo(2);
            live.tick(); serial.applyAll(List.of(first, second));
            assertThat(live.responses).hasSize(2).allSatisfy(r -> assertThat(r.status()).isEqualTo(ResponseStatus.APPLIED));
            int publicTrades = 0, executions = 0, begin = 0, end = 0;
            byte[] frameBytes;
            while ((frameBytes = outbox.poll()) != null) {
                var frame = RealtimeFrameCodec.decode(frameBytes);
                switch (frame.kind()) {
                    case TRADE -> publicTrades++;
                    case EXECUTION -> executions++;
                    case COMMIT_BEGIN -> begin++;
                    case COMMIT_END -> end++;
                    default -> { }
                }
            }
            assertThat(publicTrades).isEqualTo(batch ? 40 : 2);
            assertThat(executions).isEqualTo(batch ? 80 : 4);
            // 持续Owner按命令建立确定性提交边界，两条命令各有一组完整推送。
            assertThat(begin).isEqualTo(2); assertThat(end).isEqualTo(2);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().order(201).executedQuantitySteps()).isEqualTo(batch ? 20 : 2);
            assertThat(live.service.state().tradingState().order(202).executedQuantitySteps()).isEqualTo(batch ? 20 : 2);
            try (TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void amendSettlesAndHandlesRejectedReplacementWithoutOwnerContinuation(ProductLine product) throws Exception {
        for (boolean batch : new boolean[] {false, true})
        for (boolean rejected : new boolean[] {false, true}) {
            try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
                serial.applyAll(live.setup());
                var place = live.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
                live.apply(place); serial.apply(place);
                if (rejected) {
                    if (product == ProductLine.SPOT) {
                        var funds = live.message(CoreMessageType.ADJUST_BALANCE, disjointUser(11),
                                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 1)));
                        live.apply(funds); serial.apply(funds);
                    }
                    var maker = live.place(disjointUser(11), "BTC-USDT", 201, 100, 1, CoreOrderSide.SELL);
                    live.apply(maker); serial.apply(maker);
                    assertThat(live.service.state().tradingState().order(201)).isNotNull();
                }
                var state = live.service.state();
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var gate = state.matcherPipeline.readAtSubmissionFence(0, () -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher gate timeout");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return 1;
                });
                var replacement = new AmendOrderCommand(101, 102, "direct-amend", rejected ? 100L : 90L, 1L,
                        rejected ? CoreTimeInForce.GTX : CoreTimeInForce.GTC, rejected);
                var amend = batch
                        ? live.message(CoreMessageType.AMEND_ORDER_BATCH, 11,
                                TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(replacement))))
                        : live.message(CoreMessageType.AMEND_ORDER, 11, TradingCommandCodec.encodeAmendOrder(replacement));
                try {
                    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                    live.send(amend);
                    var pending = state.pendingMatching(state.matchingSequence(amend.header().commandId()));
                    assertThat(pending).isNotNull();
                    var event = batch ? pending.orderBatch.itemSettlementEvent : pending.settlementEvent();
                    assertThat(event).isNotNull();
                    assertThat(event.dispatched()).isTrue();
                    assertThat(event.ready()).isFalse();
                    release.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!event.complete() && System.nanoTime() < deadline) Thread.onSpinWait();
                    assertThat(event.complete()).as("Lane completes without an Owner turn").isTrue();
                    assertThat(state.pendingMatching(pending.sequence())).isSameAs(pending);
                } finally { release.countDown(); }
                assertThat(gate.join()).isOne();
                live.tick(); serial.apply(amend);
                var response = live.responses.getLast();
                var status = batch ? TradingOrderBatchCodec.decodeResult(response.data()).items().getFirst().status()
                        : response.commandStatus();
                assertThat(status).as("batch=%s rejected=%s", batch, rejected)
                        .isEqualTo(rejected ? ResponseStatus.REJECTED : ResponseStatus.APPLIED);
                assertThat(live.hash()).isEqualTo(serial.hash());
                assertThat(state.tradingState().order(101)).isNull();
                if (rejected) assertThat(state.tradingState().order(102)).isNull();
                try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void cancelBatchSettlesOnLaneWithoutAnOwnerTurnAfterMatcherFinishes(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var place = live.placeBatch(11, "BTC-USDT", 101);
            live.apply(place); serial.apply(place);
            var state = live.service.state();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var gate = state.matcherPipeline.readAtSubmissionFence(0, () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher gate timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return 1;
            });
            var cancel = live.cancelBatch(11, 101);
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(cancel);
                var pending = state.pendingMatching(state.matchingSequence(cancel.header().commandId()));
                assertThat(pending).isNotNull();
                var event = pending.orderBatch.itemSettlementEvent;
                assertThat(event).isNotNull();
                assertThat(event.dispatched()).isTrue();
                assertThat(event.ready()).isFalse();
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                // No pollCommands/tick: Matcher must wake Lane itself.
                while (!event.complete() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertThat(event.complete()).isTrue();
                assertThat(pending.orderBatch.cancelEvent).isNull();
                assertThat(pending.orderBatch.preparedResponse)
                        .as("Lane encodes the final batch result without an Owner turn").isNotNull();
                assertThat(TradingOrderBatchCodec.decodeResult(pending.orderBatch.preparedResponse,
                        pending.orderBatch.preparedResponseLength).items())
                        .allSatisfy(item -> assertThat(item.status()).isEqualTo(ResponseStatus.APPLIED));
                assertThat(state.pendingMatching(pending.sequence())).isSameAs(pending);
            } finally { release.countDown(); }
            assertThat(gate.join()).isOne();
            live.tick(); serial.apply(cancel);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().order(101)).isNull();
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void cancelSettlesOnLaneWithoutAnOwnerTurnAfterMatcherFinishes(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var place = live.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
            live.apply(place); serial.apply(place);
            var state = live.service.state();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var gate = state.matcherPipeline.readAtSubmissionFence(0, () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher gate timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return 1;
            });
            var cancel = live.cancel(11, 101);
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(cancel);
                var pending = state.pendingMatching(state.matchingSequence(cancel.header().commandId()));
                assertThat(pending).isNotNull();
                var event = pending.settlementEvent();
                assertThat(event).isNotNull();
                assertThat(event.dispatched()).isTrue();
                assertThat(event.ready()).isFalse();
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                // No pollCommands/tick: Matcher must wake Lane itself.
                while (!event.complete() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertThat(event.complete()).isTrue();
                assertThat(pending.cancelEvent()).isNull();
                assertThat(state.pendingMatching(pending.sequence())).isSameAs(pending);
            } finally { release.countDown(); }
            assertThat(gate.join()).isOne();
            live.tick(); serial.apply(cancel);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().order(101)).isNull();
        }
    }

    @Test
    void queryAndSessionCloseDrainTheFinalPartialWindowAndRetryIsIdempotent() {
        try (Fixture f = new Fixture(ProductLine.SPOT)) {
            f.setup();
            CoreMessage place = f.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
            f.send(place);
            f.service.onSessionClose(f.session, TIME + 1, io.aeron.cluster.codecs.CloseReason.CLIENT_ACTION);
            f.tick();
            assertThat(f.service.commandWindowSize()).isZero();
            long hash = f.hash();
            f.send(place); f.tick();
            assertThat(f.hash()).isEqualTo(hash);
            assertThat(f.responses).hasSize(2);
            CoreMessage cancel = f.cancel(11, 101); f.send(cancel);
            f.apply(f.message(CoreMessageType.PROBE_INCREMENT, 11, CoreProtocol.probePayload(1)));
            assertThat(f.service.commandWindowSize()).isZero();
            assertThat(f.service.state().tradingState().user(11).balances().get("USDT").lockedUnits()).isZero();
        }
    }

    @Test
    void normalizedSymbolsCompleteWithoutSchedulingTimers() {
        try (Fixture f = new Fixture(ProductLine.SPOT)) {
            f.setup();
            f.send(f.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY));
            f.send(f.place(disjointUser(11), "btc-usdt", disjointOrder(101), 80, 1, CoreOrderSide.BUY));
            assertThat(f.service.commandWindowSize()).isEqualTo(2);
            assertThat(f.responses).isEmpty();
            int scheduled = f.scheduledTimers;
            assertThat(scheduled).isZero();
            f.tick();
            int afterTick = f.scheduledTimers;
            assertThat(afterTick).isZero();
            f.send(f.cancel(11, 101));
            assertThat(f.scheduledTimers).isEqualTo(afterTick);
            f.tick();
        }
    }

    private static long disjointUser(long user) {
        var topology = LaneTopology.productionDefault();
        long lane = topology.accountLaneMask(user);
        for (long next = user + 1;; next++) {
            if (topology.accountLaneMask(next) != lane) return next;
        }
    }
    private static long disjointOrder(long id) {
        return Math.incrementExact(id);
    }
    private static String disjointSymbol(String symbol) {
        return "ALT0-USDT".equals(symbol) ? "ALT1-USDT" : "ALT0-USDT";
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void blockedBatchRetainsDecodeUntilItsDependencyCommits(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var state = live.service.state();
            var gate = state.matcherPipeline.readAtSubmissionFence(0, () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher gate timeout"); }
                catch (InterruptedException error) { throw new IllegalStateException(error); }
                return 1;
            });
            var first = live.place(11, "BTC-USDT", 50000, 80, 1, CoreOrderSide.BUY);
            var second = live.placeBatch(11, "BTC-USDT", 51000);
            var window = live.service.commandWindow();
            var decodedField = ClusterCommandWindow.class.getDeclaredField("decoded");
            decodedField.setAccessible(true);
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(first); live.send(second);
                Object decoded = decodedField.get(window);
                assertThat(decoded).isNotNull();
                for (int i = 0; i < 100; i++) {
                    live.service.pollCommands();
                    assertThat(decodedField.get(window)).isSameAs(decoded);
                }
                assertThat(live.responses).isEmpty();
                assertThat(live.service.commandWindowSize()).isOne();
            } finally { release.countDown(); }
            live.tick(); serial.apply(first); serial.apply(second);
            assertThat(gate.join()).isOne();
            assertThat(decodedField.get(window)).isNull();
            assertThat(live.responses).hasSize(2);
            assertThat(live.hash()).isEqualTo(serial.hash());
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentSettlementsDispatchAheadWithoutPublishingTheSuffix(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var state = live.service.state();
            var matcherEntered = new CountDownLatch(1);
            var releaseMatcher = new CountDownLatch(1);
            var releaseLanes = new CountDownLatch(1);
            var matcherGate = state.matcherPipeline.readAtSubmissionFence(0, () -> {
                matcherEntered.countDown();
                try { if (!releaseMatcher.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher gate timeout"); }
                catch (InterruptedException error) { throw new IllegalStateException(error); }
                return 1;
            });
            var first = live.placeBatch(11, "BTC-USDT", 60000);
            var second = live.placeBatch(disjointUser(11), disjointSymbol("BTC-USDT"), 61000);
            try {
                assertThat(matcherEntered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(first); live.send(second);
                long firstSequence = state.matchingSequence(first.header().commandId());
                long secondSequence = state.matchingSequence(second.header().commandId());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while ((!state.pendingMatching(firstSequence).isMatchingSubmitted()
                        || !state.pendingMatching(secondSequence).isMatchingSubmitted()) && System.nanoTime() < deadline)
                    live.service.pollCommands();
                assertThat(state.pendingMatching(secondSequence).isMatchingSubmitted()).isTrue();
                // Sequenced direct mailboxes are already queued while Matcher is blocked.
                // A later Lane blocker would sit behind those mailboxes and cannot run yet.
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (state.commits.dispatchedSettlementInFlight < 2 && System.nanoTime() < deadline)
                    live.service.pollCommands();
                assertThat(state.commits.dispatchedSettlementInFlight).isEqualTo(2);
                assertThat(live.responses).isEmpty();
                assertThat(state.firstPendingMatchingSequence()).isEqualTo(firstSequence);
            } finally { releaseMatcher.countDown(); releaseLanes.countDown(); }
            live.tick(); serial.apply(first); serial.apply(second);
            assertThat(matcherGate.join()).isOne();
            assertThat(live.responses).hasSize(2).allSatisfy(r -> assertThat(r.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL",
            "LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    void internalTriggerPagesExpireTrailAndRecoverWithoutAccountHandoff(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product)) {
            live.setup();
            long maker = 11, user = disjointUser(maker);
            live.apply(live.place(maker, "BTC-USDT", 501, 100, 10, CoreOrderSide.SELL));
            live.apply(live.place(user, "BTC-USDT", 502, 100, 10, CoreOrderSide.BUY));
            for (long id : new long[]{8990, 9001, 9002, 9003, 9004, 9005, 9010}) {
                boolean expired = id == 8990, trailing = id == 9010;
                var trigger = new CoreTriggerOrderStateView(id, product, user, "scan-" + id,
                        expired || trailing ? "" : "scan-oco", "BTC-USDT", CoreOrderSide.SELL,
                        trailing ? CoreTriggerOrderType.TRAILING_STOP : CoreTriggerOrderType.TAKE_PROFIT,
                        CoreTriggerCondition.GREATER_OR_EQUAL, expired ? 90 : id == 9001 ? 100 : 200,
                        0, trailing ? 100_000 : 0, trailing ? 120 : 0, 0, 0,
                        CoreOrderType.LIMIT, CoreTimeInForce.IOC, 110, 1,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                        0, 0, 0, "", "scan", expired ? TIME + 500 : 0, 0, 0, 0, 1, 0, 0);
                live.apply(live.message(CoreMessageType.PLACE_TRIGGER_ORDER, user, CoreTriggerOrderCodec.encodeState(trigger)));
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
            var runtime = live.service.state().runtimeState;
            var epoch = runtime.getClass().getDeclaredField("laneHandoffEpoch");
            epoch.setAccessible(true);
            long before = epoch.getLong(runtime);
            long funds = com.surprising.aeron.service.state.FundsStateHash.compute(live.service.state().tradingState());
            live.sequence += 1000; // Commands occur after the expired order and the new mark timestamp.
            live.apply(live.message(CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(product == ProductLine.OPTION
                            ? new ApplyMarkPriceCommand("BTC-USDT", 100, 100, 100, 2, TIME + 1000)
                            : new ApplyMarkPriceCommand("BTC-USDT", 100, 2, TIME + 1000))));
            boolean resumed = false;
            for (int n = 0; n < 100 && runtime.firstIncompleteRiskScan() != null; n++) {
                var command = live.message(CoreMessageType.CONTINUE_RISK_SCAN, 0,
                        TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(1)));
                // The same snapshot must resume an incomplete OCO page in standalone replay as in live Lane execution.
                var scan = runtime.riskScan(live.service.state().identities.findSymbolId("BTC-USDT"));
                if (!resumed && scan.triggerOcoOrderId() != 0) {
                    try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(501))) {
                        live.apply(command);
                        assertThat(restored.apply(command).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                        assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                    }
                    resumed = true;
                } else live.apply(command);
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(epoch.getLong(runtime)).isEqualTo(before);
            }
            assertThat(resumed).as("cross-command OCO continuation exercised").isTrue();
            assertThat(runtime.firstIncompleteRiskScan()).isNull();
            assertThat(runtime.triggerOrder(8990).status()).isEqualTo(CoreTriggerOrderStatus.EXPIRED);
            assertThat(runtime.triggerOrder(9001).status()).as("%s", runtime.triggerOrder(9001)).isEqualTo(CoreTriggerOrderStatus.TRIGGERED);
            for (long id = 9002; id <= 9005; id++)
                assertThat(runtime.triggerOrder(id).status()).isEqualTo(CoreTriggerOrderStatus.CANCELED);
            assertThat(runtime.triggerOrder(9010).status()).isEqualTo(CoreTriggerOrderStatus.TRIGGERED);
            assertThat(runtime.triggerOrder(9010).highestPriceTicks()).isEqualTo(120);
            assertThat(runtime.triggerOrder(9010).activatedAtEpochMillis()).isEqualTo(TIME + 1000);
            assertThat(com.surprising.aeron.service.state.FundsStateHash.compute(live.service.state().tradingState()))
                    .isEqualTo(funds);
        }
    }

    private static void scanTriggers(Fixture live, int budget) {
        boolean option = live.product == ProductLine.OPTION;
        live.apply(live.message(CoreMessageType.APPLY_MARK_PRICE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(option
                        ? new ApplyMarkPriceCommand("BTC-USDT", 100, 100, 100, 2, TIME)
                        : new ApplyMarkPriceCommand("BTC-USDT", 100, 2, TIME))));
        for (int i = 0; i < 100 && live.service.state().runtimeState.firstIncompleteRiskScan() != null; i++) {
            live.apply(live.message(CoreMessageType.CONTINUE_RISK_SCAN, 0,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(budget))));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
        }
        assertThat(live.service.state().runtimeState.firstIncompleteRiskScan()).isNull();
    }

    private static final class Fixture implements AutoCloseable {
        final TradingOwnerTestSupport service;
        final ProductLine product;
        final List<CoreResponse> responses = new ArrayList<>();
        final ClientSession session;
        final Cluster.Role role;
        long sequence;
        int scheduledTimers;
        Runnable firstResponse;
        Fixture(ProductLine product) { this(product, Cluster.Role.LEADER); }
        Fixture(ProductLine product, Cluster.Role role) {
            this.product = product; this.role = role;
            service = new TradingOwnerTestSupport(product);
            session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                    new Class<?>[]{ClientSession.class}, (p, method, args) -> {
                        if (method.getName().equals("offer")) {
                            byte[] bytes = new byte[(int) args[2]];
                            ((org.agrona.DirectBuffer) args[0]).getBytes((int) args[1], bytes);
                            responses.add(CoreProtocol.decodeResponse(CoreMessageCodec.decode(bytes).payloadUnsafe()));
                            if (responses.size() == 1 && firstResponse != null) firstResponse.run();
                            return 1L;
                        }
                        return zero(method.getReturnType());
                    });
            Cluster cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "role" -> role;
                        case "timeUnit" -> TimeUnit.MILLISECONDS;
                        case "time", "logPosition" -> TIME;
                        case "scheduleTimer" -> { scheduledTimers++; yield true; }
                        case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                        default -> zero(method.getReturnType());
                    });
            service.onStart(cluster, null);
        }
        List<CoreMessage> setup() { return setup(disjointSymbol("BTC-USDT")); }
        List<CoreMessage> setup(String secondSymbol) {
            ContractType type = ContractType.valueOf(product.contractTypeCode());
            String asset = type.isInverse() ? "BTC" : "USDT";
            List<CoreMessage> commands = new ArrayList<>();
            for (String symbol : List.of("BTC-USDT", secondSymbol)) {
                commands.add(message(CoreMessageType.REGISTER_INSTRUMENT, 0,
                        TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(symbol,
                                type.ordinal(), "BTC", "USDT", asset, 1, 1, type.isInverse() ? 1_000 : 1,
                                100_000, 50_000, 0, 0, type.isDelivery() || type.isOption() ? TIME + 100_000 : 0,
                                type.isOption() ? 0 : -1, type.isOption() ? 100 : 0))));
            }
            for (String symbol : List.of("BTC-USDT", secondSymbol)) {
                commands.add(message(CoreMessageType.APPLY_MARK_PRICE, 0,
                        TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                                ? new ApplyMarkPriceCommand(symbol, 100, 100, 100, 1, TIME)
                                : new ApplyMarkPriceCommand(symbol, 100, 1, TIME))));
            }
            for (long user : new long[]{11, disjointUser(11)})
                commands.add(message(CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))));
            applyAll(commands); responses.clear(); return commands;
        }
        CoreMessage message(CoreMessageType type, long user, byte[] payload) {
            long id = ++sequence;
            return new CoreMessage(CoreMessageHeader.command(type, new UUID(990, id), product,
                    CommandSource.OPERATIONS, 990, id, user, TIME + id, id), payload);
        }
        CoreMessage place(long user, String symbol, long order, long price, long qty, CoreOrderSide side) {
            return message(CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                    new PlaceOrderCommand(order, symbol, side, price, qty, false, CoreMarginMode.CROSS,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "order-" + order)));
        }
        CoreMessage cancel(long user, long order) {
            return message(CoreMessageType.CANCEL_ORDER, user,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(order)));
        }
        CoreMessage placeBatch(long user, String symbol, long firstId) {
            List<PlaceOrderCommand> orders = new ArrayList<>();
            for (int i = 0; i < 20; i++) orders.add(new PlaceOrderCommand(firstId+i, symbol,
                    CoreOrderSide.BUY, 80, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "batch-"+(firstId+i)));
            return message(CoreMessageType.PLACE_ORDER_BATCH, user,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)));
        }
        CoreMessage cancelBatch(long user, long firstId) {
            List<CancelOrderCommand> orders = new ArrayList<>();
            for (int i = 0; i < 20; i++) orders.add(new CancelOrderCommand(firstId+i));
            return message(CoreMessageType.CANCEL_ORDER_BATCH, user,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(orders)));
        }
        void send(CoreMessage command) {
            byte[] bytes = CoreMessageCodec.encode(command);
            service.onSessionMessage(role == Cluster.Role.LEADER ? session : null, command.header().submittedAtEpochMillis(),
                    new UnsafeBuffer(bytes), 0, bytes.length,
                    new Header(0, 0).buffer(new UnsafeBuffer(new byte[64])).offset(0).initialTermId(0).positionBitsToShift(16));
        }
        void progressUntil(java.util.function.BooleanSupplier done) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!done.getAsBoolean() && System.nanoTime() < deadline) {
                service.onSessionOpen(null, TIME + 1);
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
            assertThat(done.getAsBoolean()).as("bounded asynchronous progress").isTrue();
        }
        void tick() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do {
                service.pollCommands();
                if (service.pendingCommandCount() == 0) return;
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("asynchronous commands did not complete");
        }
        void apply(CoreMessage command) { send(command); tick(); }
        void applyAll(List<CoreMessage> commands) { commands.forEach(this::apply); }
        long hash() { return service.state().tradingState().businessStateHash(); }
        public void close() { service.onTerminate(null); }
        private static Object zero(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == long.class) return 0L;
            if (type == int.class) return 0;
            return null;
        }
    }
}
