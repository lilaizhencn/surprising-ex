package com.surprising.aeron.service.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.CoreTreasuryState;
import com.surprising.aeron.service.state.CoreUserState;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherResult;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommandType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeterministicExchangeCoreAdapterTest {

    @Test
    void nativeCancellationPreservesEvidenceForPartialFillsAndRepeatedCancellation() {
        try (var direct = new DeterministicExchangeCoreAdapter();
             var composed = new DeterministicExchangeCoreAdapter()) {
            var order = new CoreMatchingOrder(901, "CANCEL-EVIDENCE", CoreOrderSide.BUY,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 10);
            int shard = direct.matcherShardId(order.symbol());
            int otherShard = composed.matcherShardId(order.symbol());
            var placement = new java.util.UUID(7, 1);
            direct.placeWithEvidence(shard, 1, placement, 1000, 7, order);
            composed.executeShardWithEvidenceSync(otherShard, 1, placement, 901, 1000,
                    () -> composed.place(7, order));
            var taker = new CoreMatchingOrder(902, order.symbol(), CoreOrderSide.SELL,
                    CoreOrderType.LIMIT, CoreTimeInForce.IOC, 90, 4);
            direct.placeWithEvidence(shard, 2, new java.util.UUID(8, 2), 2000, 8, taker);
            composed.executeShardWithEvidenceSync(otherShard, 2, new java.util.UUID(8, 2), 902, 2000,
                    () -> composed.place(8, taker));
            for (int sequence = 3; sequence <= 4; sequence++) {
                var id = new java.util.UUID(7, sequence);
                var actual = direct.cancelWithEvidence(shard, sequence, id, 901, sequence * 1000L, 7, order.symbol());
                var expected = composed.executeShardWithEvidenceSync(otherShard, sequence, id, 901, sequence * 1000L,
                        () -> composed.cancelForContinuation(7, 901, order.symbol()));
                assertThat(actual.accepted()).isEqualTo(sequence == 3);
                assertThat(actual.accepted()).isEqualTo(expected.accepted());
                assertThat(actual.resultCode()).isEqualTo(expected.resultCode());
                assertThat(actual.outcome()).isEqualTo(expected.outcome());
                assertEvidenceEqual(actual, expected);
                assertThat(actual.matcherEvents()).isEqualTo(expected.matcherEvents());
            }
            assertThat(direct.place(7, new CoreMatchingOrder(903, order.symbol(), CoreOrderSide.BUY,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1)).nativeMatcherResult().timestamp()).isZero();
        }
    }

    @Test
    void nativePlacementHonorsPoisonAndValidatesEvidenceBeforeMatching() {
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            var order = bid(901, 90);
            int shard = adapter.matcherShardId(order.symbol());
            assertThatThrownBy(() -> adapter.placeWithEvidence(shard, 0, new java.util.UUID(0, 1),
                    1000, 7, order)).isInstanceOf(IllegalArgumentException.class);
            var placed = adapter.placeWithEvidence(shard, 1, new java.util.UUID(0, 1), 1000, 7, order);
            assertThat(placed.accepted()).isTrue();
            assertThat(placed.nativeMatcherSequence()).isEqualTo(1);
            adapter.poisonFromOwner("test divergence");
            assertThatThrownBy(() -> adapter.placeWithEvidence(shard, 2, new java.util.UUID(0, 2),
                    2000, 7, bid(902, 90))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("matcher is poisoned");
        }
    }

    @Test
    void nativePlacementEvidenceMatchesComposedPathWithoutChangingEarlierResults() {
        try (var direct = new DeterministicExchangeCoreAdapter();
             var composed = new DeterministicExchangeCoreAdapter()) {
            CoreMatchingResult first = null;
            for (int i = 1; i <= 4; i++) {
                var order = new CoreMatchingOrder(i, "EVIDENCE-USDT",
                        i == 2 ? CoreOrderSide.SELL : CoreOrderSide.BUY,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1);
                var id = new java.util.UUID(7, i);
                int shard = direct.matcherShardId(order.symbol());
                int otherShard = composed.matcherShardId(order.symbol());
                long user = i == 2 ? 8 : 7;
                var actual = direct.placeWithEvidence(shard, i, id, 1000 + i, user, order);
                var expected = composed.executeShardWithEvidenceSync(otherShard, i, id, i, 1000 + i,
                        () -> composed.place(user, order));
                assertThat(actual.accepted()).isEqualTo(expected.accepted());
                assertThat(actual.resultCode()).isEqualTo(expected.resultCode());
                assertThat(actual.outcome()).isEqualTo(expected.outcome());
                assertEvidenceEqual(actual, expected);
                assertThat(actual.matcherEvents()).isEqualTo(expected.matcherEvents());
                assertThat(actual.nativeMatcherResult().timestamp()).isEqualTo(1000 + i);
                if (first == null) first = actual;
            }
            assertThat(first.nativeCoreSequence()).isEqualTo(1);
            assertThat(first.matcherEvents()).isEmpty();
            assertThat(direct.place(7, new CoreMatchingOrder(5, "EVIDENCE-USDT", CoreOrderSide.BUY,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1)).nativeMatcherResult().timestamp()).isZero();
        }
    }

    @Test
    void registeredSymbolLookupDoesNotAcquireRegistrationMonitor() throws Exception {
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            int expected = adapter.matcherShardId("REGISTERED-USDT");
            synchronized (adapter) {
                var result = CompletableFuture.supplyAsync(() -> adapter.matcherShardId("REGISTERED-USDT"));
                assertThat(result.get(2, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(expected);
            }
            var calls = new ArrayList<CompletableFuture<Integer>>();
            for (int i = 0; i < 32; i++) calls.add(CompletableFuture.supplyAsync(() -> adapter.matcherShardId("NEW-USDT")));
            for (var result : calls) assertThat(result.get(2, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(adapter.matcherShardId("NEW-USDT"));
        }
    }

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void controlEvidenceChecksNativeSequenceWithinItsActualOrderBookPartition() {
        String previous = System.getProperty("surprising.aeron.matching-engines");
        System.setProperty("surprising.aeron.matching-engines", "2");
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            String first = "BOOK-A", candidate = "BOOK-B";
            for (int attempt = 0; adapter.matcherShardId(first) == adapter.matcherShardId(candidate); attempt++) {
                if (attempt == 128) throw new AssertionError("could not select a second partition");
                candidate = "BOOK-" + attempt;
            }
            String second = candidate;
            CoreMatchingResult previousResult = null;
            for (long id = 1; id <= 16; id++) {
                long orderId = id;
                previousResult = adapter.executeControlWithEvidenceSync(id, new java.util.UUID(7, id), id, 1000,
                        () -> adapter.place(7, new CoreMatchingOrder(orderId, first, CoreOrderSide.BUY,
                                CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1)));
            }
            var result = adapter.executeControlWithEvidenceSync(17, new java.util.UUID(7, 17), 17, 1000,
                    () -> adapter.place(8, new CoreMatchingOrder(17, second, CoreOrderSide.BUY,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1)));
            assertThat(result.accepted()).isTrue();
            assertThat(result.nativeSequence()).isLessThan(previousResult.nativeSequence());
            assertThat(result.nativeMatcherSequence()).isGreaterThan(previousResult.nativeMatcherSequence());
        } finally {
            if (previous == null) System.clearProperty("surprising.aeron.matching-engines");
            else System.setProperty("surprising.aeron.matching-engines", previous);
        }
    }

    @Test
    void reusedCommandScopeDoesNotLeakTimestampIntoTheNextCommand() {
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            var first = adapter.executeWithEvidenceSync(1, new java.util.UUID(0, 1), 901, 1234,
                    () -> adapter.place(7, bid(901, 90)));
            var second = adapter.executeWithEvidenceSync(2, new java.util.UUID(0, 2), 902, 5678,
                    () -> adapter.place(7, bid(902, 90)));
            var unscoped = adapter.place(7, bid(903, 90));
            assertThat(first.nativeMatcherResult().timestamp()).isEqualTo(1234);
            assertThat(second.nativeMatcherResult().timestamp()).isEqualTo(5678);
            assertThat(unscoped.nativeMatcherResult().timestamp()).isZero();
            var async = adapter.executeWithEvidence(3, new java.util.UUID(0, 3), 904, 9876,
                    () -> adapter.placeAsync(7, bid(904, 90))).join();
            assertThat(async.nativeMatcherResult().timestamp()).isEqualTo(9876);
            assertThat(adapter.place(7, bid(905, 90)).nativeMatcherResult().timestamp()).isZero();
        }
    }

    @Test
    void cancelBatchStopsAtFirstFailureAndReturnsSuccessfulPrefix() {
        List<CoreOrderState> orders = List.of(order(1), order(2), order(3));
        List<Long> submissions = new ArrayList<>();
        CompletableFuture<CoreMatchingResult> first = new CompletableFuture<>();
        CompletableFuture<CoreMatchingResult> second = new CompletableFuture<>();

        CompletableFuture<DeterministicExchangeCoreAdapter.CancelBatchOutcome> outcomeFuture =
                DeterministicExchangeCoreAdapter.cancelBatchOrderedAsync(orders, order -> {
                    submissions.add(order.orderId());
                    return order.orderId() == 1 ? first : second;
                });

        assertThat(submissions).containsExactly(1L);
        first.complete(result(true, "SUCCESS"));
        assertThat(submissions).containsExactly(1L, 2L);
        second.complete(result(false, "MATCHING_INVALID_ORDER_ID"));

        DeterministicExchangeCoreAdapter.CancelBatchOutcome outcome = outcomeFuture.join();
        assertThat(submissions).containsExactly(1L, 2L);
        assertThat(outcome.successfulPrefix()).extracting(CoreMatchingResult::resultCode)
                .containsExactly("SUCCESS");
        assertThat(outcome.failedResult().resultCode()).isEqualTo("MATCHING_INVALID_ORDER_ID");
        assertThat(outcome.exception()).isNull();
        assertThat(outcome.results()).extracting(CoreMatchingResult::resultCode)
                .containsExactly("SUCCESS", "MATCHING_INVALID_ORDER_ID", "NOT_SUBMITTED");
    }

    @Test
    void marksRejectedReplacementAfterSuccessfulCancelAsMatcherStateChanged() {
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            assertThat(adapter.placeAsync(7, bid(1, 100)).join().accepted()).isTrue();
            assertThat(adapter.placeAsync(7, ask(2, 110)).join().accepted()).isTrue();

            CoreMatchingResult result = adapter.replaceOrderAsync(7, 1, "BTC-USDT", postOnlyBid(3, 120)).join();

            assertThat(result.accepted()).isFalse();
            assertThat(result.resultCode()).isNotEqualTo("SUCCESS");
            assertThat(result.matcherStateChanged()).isTrue();
        }
    }

    @Test
    void consumesImmutableMatcherResultWithoutPerCommandStateReports() {
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            assertThat(adapter.placeAsync(11, ask(101, 100)).join().accepted()).isTrue();
            assertThat(adapter.placeAsync(12, ask(102, 100)).join().accepted()).isTrue();

            CoreMatchingOrder command = new CoreMatchingOrder(201, "BTC-USDT", CoreOrderSide.BUY,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, 100, 4);
            CoreMatchingResult result = adapter.executeWithEvidence(
                    3,
                    java.util.UUID.fromString("00000000-0000-0000-0000-000000000201"),
                    command.orderId(),
                    1_000,
                    () -> adapter.placeAsync(22, command)).join();

            assertThat(result.matcherEvents()).extracting(MatcherResult.MatcherEvent::matchedOrderId)
                    .containsExactly(101L, 102L);
            assertThat(result.nativeOrderId()).isEqualTo(201L);
            assertThat(result.nativeSequence()).isPositive();
            assertThat(result.nativeMatcherResult()).isNotNull();
            assertThat(result.matcherEvents()).isSameAs(result.nativeMatcherResult().events());
            assertThat(result.marketData()).isSameAs(result.nativeMatcherResult().marketData());
        }
    }

    @Test
    void pipelinesMatcherCommandsAndAdvancesSequences() {
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter(false)) {
            CompletableFuture<CoreMatchingResult> firstNative = new CompletableFuture<>();
            CompletableFuture<CoreMatchingResult> secondNative = new CompletableFuture<>();
            AtomicInteger submissions = new AtomicInteger();

            CompletableFuture<CoreMatchingResult> first = adapter.executeWithEvidence(
                    1, java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    101, 1_000, () -> {
                        submissions.incrementAndGet();
                        return firstNative;
                    });
            CompletableFuture<CoreMatchingResult> second = adapter.executeWithEvidence(
                    2, java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"),
                    102, 1_001, () -> {
                        submissions.incrementAndGet();
                        return secondNative;
                    });

            assertThat(submissions).hasValue(2);
            assertThat(adapter.dispatchDepth()).isEqualTo(2);
            assertThat(adapter.dispatchHighWaterMark()).isEqualTo(2);
            assertThat(adapter.dispatchCapacity()).isEqualTo(adapter.topology().matcherWindowSize());
            firstNative.complete(result(true, "SUCCESS"));
            CoreMatchingResult firstResult = first.join();

            secondNative.complete(result(false, "MATCHING_INVALID_ORDER_ID"));
            CoreMatchingResult secondResult = second.join();
            assertThat(adapter.dispatchDepth()).isZero();

            assertThat(firstResult.nativeMatcherSequence()).isEqualTo(1);
            assertThat(secondResult.nativeMatcherSequence()).isEqualTo(2);
        }
    }

    @Test
    void fixedMatcherShardRejectsOutOfNativeSequenceCompletion() {
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter(false)) {
            CompletableFuture<CoreMatchingResult> firstNative = new CompletableFuture<>();
            CompletableFuture<CoreMatchingResult> secondNative = new CompletableFuture<>();
            CompletableFuture<CoreMatchingResult> first = adapter.executeWithEvidence(
                    1, java.util.UUID.fromString("00000000-0000-0000-0000-000000000021"),
                    201, 1_000, () -> firstNative);
            CompletableFuture<CoreMatchingResult> second = adapter.executeWithEvidence(
                    2, java.util.UUID.fromString("00000000-0000-0000-0000-000000000022"),
                    202, 1_001, () -> secondNative);

            secondNative.complete(nativeResult(2, 2));
            CoreMatchingResult secondResult = second.join();
            firstNative.complete(nativeResult(2, 1));

            assertThat(secondResult.nativeMatcherShardId())
                    .isEqualTo(adapter.topology().matcherShardId(2));
            assertThatThrownBy(first::join)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("native sequence is not strictly increasing");
        }
    }

    @Test
    void poisonedMatcherDiscardsAlreadySubmittedCompletionAndRejectsFutureSupplier() throws Exception {
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter(false)) {
            CompletableFuture<CoreMatchingResult> firstNative = new CompletableFuture<>();
            CompletableFuture<CoreMatchingResult> secondNative = new CompletableFuture<>();
            AtomicInteger submissions = new AtomicInteger();
            CompletableFuture<CoreMatchingResult> first = adapter.executeWithEvidence(
                    1, java.util.UUID.fromString("00000000-0000-0000-0000-000000000011"),
                    101, 1_000, () -> {
                        submissions.incrementAndGet();
                        return firstNative;
                    });
            CompletableFuture<CoreMatchingResult> second = adapter.executeWithEvidence(
                    2, java.util.UUID.fromString("00000000-0000-0000-0000-000000000012"),
                    102, 1_001, () -> {
                        submissions.incrementAndGet();
                        return secondNative;
                    });

            firstNative.complete(result(false, "EXCHANGE_CORE_FAILURE"));
            CompletableFuture<CoreMatchingResult> third = adapter.executeWithEvidence(
                    3, java.util.UUID.fromString("00000000-0000-0000-0000-000000000013"),
                    103, 1_002, () -> {
                        submissions.incrementAndGet();
                        return CompletableFuture.completedFuture(result(true, "SUCCESS"));
                    });
            assertThat(first.join().resultCode()).isEqualTo("EXCHANGE_CORE_FAILURE");
            var progressAfterFatal = matcherProgress(adapter);
            secondNative.complete(result(true, "SUCCESS"));
            assertThatThrownBy(second::join).hasCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("completion discarded after fatal divergence");
            assertThatThrownBy(third::join).hasCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("matcher is poisoned");
            assertThat(matcherProgress(adapter)).isEqualTo(progressAfterFatal);
            assertThat(submissions).hasValue(2);
        }
    }

    @Test
    void singleSymbolBookQueryAndBootstrapUseSeparateScopes() {
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            assertThat(adapter.placeAsync(7, bid(1, 100)).join().accepted()).isTrue();
            assertThat(adapter.placeAsync(8, bid(2, "ETH-USDT", 200)).join().accepted()).isTrue();

            assertThat(adapter.orderBookLevelsAsync("BTC-USDT", 30).join())
                    .extracting(value -> value.symbol()).containsOnly("BTC-USDT");
            BookBootstrapSnapshot bootstrap = adapter.orderBookBootstrapAsync(30).join();
            assertThat(bootstrap.symbols()).containsExactly("BTC-USDT", "ETH-USDT");
            assertThat(bootstrap.levels()).extracting(value -> value.symbol())
                    .containsExactly("BTC-USDT", "ETH-USDT");
            assertThatThrownBy(() -> adapter.orderBookLevelsAsync("", 30).join())
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void nativeSnapshotRoundTripRestoresTheOnlyExecutableBook() {
        TradingCoreState state = stateWithOpenBid(100);
        MatcherSnapshot snapshot;
        CoreMatchingResult beforeSnapshot;
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            beforeSnapshot = adapter.executeWithEvidence(
                    1, java.util.UUID.fromString("00000000-0000-0000-0000-000000000100"),
                    100, 1_000, () -> adapter.placeAsync(7, bid(100))).join();
            assertThat(beforeSnapshot.accepted()).isTrue();
            snapshot = adapter.snapshotAsync(
                    91, 1, state.businessStateHash(), state, activeOrders(state)).join();
        }

        byte[] encoded = MatcherSnapshotCodec.encode(snapshot);
        MatcherSnapshot decoded = MatcherSnapshotCodec.decode(encoded);
        assertThat(decoded.matcherShardProgress()).isEqualTo(snapshot.matcherShardProgress());
        assertThat(decoded.symbols()).containsExactlyEntriesOf(snapshot.symbols());
        assertThat(decoded.users()).containsExactlyElementsOf(snapshot.users());
        assertThat(decoded.modules()).hasSize(
                decoded.matchingEngineCount() + decoded.riskEngineCount());
        assertThat(decoded.modules().stream()
                .filter(module -> module.type().name().equals("MATCHING_ENGINE_ROUTER"))
                .map(module -> module.instanceId()).sorted().toList())
                .containsExactlyElementsOf(java.util.stream.IntStream.range(
                        0, decoded.matchingEngineCount()).boxed().toList());
        assertThat(decoded.modules()).zipSatisfy(snapshot.modules(), (actual, expected) -> {
            assertThat(actual.type()).isEqualTo(expected.type());
            assertThat(actual.sequence()).isEqualTo(expected.sequence());
            assertThat(actual.checksum()).isEqualTo(expected.checksum());
            assertThat(actual.data()).containsExactly(expected.data());
        });

        try (DeterministicExchangeCoreAdapter restored =
                     new DeterministicExchangeCoreAdapter(state, activeOrders(state), 1, decoded)) {
            assertThat(restored.orderBooksStateHashAsync().join()).isEqualTo(snapshot.bookStateHash());
            CoreMatchingResult afterRestore = restored.executeWithEvidence(
                    2, java.util.UUID.fromString("00000000-0000-0000-0000-000000000101"),
                    101, 1_001, () -> restored.placeAsync(8, bid(101, 90))).join();
            assertThat(afterRestore.nativeMatcherSequence()).isGreaterThan(
                    snapshot.progress(afterRestore.nativeMatcherShardId()).matcherSequence());
        }
    }

    @Test
    void snapshotPipelineCompletesPersistAndExportWithoutCallerCancellation() {
        TradingCoreState state = stateWithOpenBid(100);
        CompletableFuture<Void> persistEntered = new CompletableFuture<>();
        CompletableFuture<Void> releasePersist = new CompletableFuture<>();
        AtomicInteger persistSubmissions = new AtomicInteger();
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter(nativePersist -> {
            persistSubmissions.incrementAndGet();
            persistEntered.complete(null);
            return releasePersist.thenCompose(ignored -> nativePersist.get());
        })) {
            assertThat(adapter.placeAsync(7, bid(100)).join().accepted()).isTrue();
            CompletableFuture<MatcherSnapshot> first =
                    adapter.snapshotAsync(94, 1, state.businessStateHash(), state, activeOrders(state));

            persistEntered.join();
            assertThat(first.isDone()).isFalse();
            assertThat(first.cancel(false))
                    .as("caller cancellation must not cancel the nested persist/export operation")
                    .isFalse();
            assertThat(first.isCancelled()).isFalse();

            CompletableFuture<MatcherSnapshot> retry =
                    adapter.snapshotAsync(94, 1, state.businessStateHash(), state, activeOrders(state));
            assertThat(retry).isNotSameAs(first);
            assertThat(retry.isDone()).isFalse();
            assertThat(persistSubmissions).hasValue(1);

            releasePersist.complete(null);
            MatcherSnapshot completed = first.join();

            assertThat(retry.join()).isEqualTo(completed);
            assertThat(completed.snapshotId()).isEqualTo(94);
            assertThat(completed.modules()).isNotEmpty();
            assertThat(persistSubmissions).hasValue(1);
        }
    }

    @Test
    void restoreFailsClosedWhenCoreMetadataDoesNotMatchNativeOpenOrders() {
        TradingCoreState original = stateWithOpenBid(100);
        MatcherSnapshot snapshot;
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            assertThat(adapter.placeAsync(7, bid(100)).join().accepted()).isTrue();
            snapshot = adapter.snapshotAsync(
                    92, 1, original.businessStateHash(), original, activeOrders(original)).join();
        }
        TradingCoreState divergent = stateWithOpenBid(101);
        MatcherSnapshot divergentManifest = new MatcherSnapshot(
                snapshot.productLine(), snapshot.coreShardId(), snapshot.routeVersion(), snapshot.topology(),
                snapshot.snapshotId(),
                snapshot.coreSequence(), snapshot.matcherSequence(), snapshot.matcherShardProgress(),
                divergent.businessStateHash(),
                snapshot.engineStateHash(), snapshot.bookStateHash(), snapshot.symbolRegistryHash(),
                snapshot.symbolRouteHash(), snapshot.userRegistryHash(),
                MatcherSnapshot.activeOrderHash(divergent),
                snapshot.forkGitSha(), snapshot.artifactSha256(), snapshot.matcherConfigHash(),
                snapshot.symbols(), snapshot.users(), snapshot.modules());

        assertThatThrownBy(() -> new DeterministicExchangeCoreAdapter(
                divergent, activeOrders(divergent), 1, divergentManifest))
                .isInstanceOf(FatalMatchingDivergenceException.class)
                .hasMessageContaining("Core OPEN orders do not exactly match exchange-core open orders");
    }

    @Test
    void matcherSnapshotCodecRejectsCorruption() {
        TradingCoreState state = stateWithOpenBid(100);
        byte[] encoded;
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            assertThat(adapter.placeAsync(7, bid(100)).join().accepted()).isTrue();
            encoded = MatcherSnapshotCodec.encode(adapter.snapshotAsync(
                    93, 1, state.businessStateHash(), state, activeOrders(state)).join());
        }
        encoded[encoded.length / 2] ^= 1;

        assertThatThrownBy(() -> MatcherSnapshotCodec.decode(encoded))
                .isInstanceOf(com.surprising.aeron.protocol.ProtocolException.class)
                .hasMessageContaining("checksum");
    }

    private static CoreOrderState order(long orderId) {
        return new CoreOrderState(orderId, ProductLine.SPOT, 7, "BTC-USDT",
                CoreOrderSide.BUY, 100, 1, 0, 1, false, CoreOrderStatus.OPEN, 1);
    }

    private static CoreMatchingOrder bid(long priceTicks) {
        return bid(1, priceTicks);
    }

    private static CoreMatchingOrder bid(long orderId, long priceTicks) {
        return bid(orderId, "BTC-USDT", priceTicks);
    }

    private static CoreMatchingOrder bid(long orderId, String symbol, long priceTicks) {
        return new CoreMatchingOrder(orderId, symbol, CoreOrderSide.BUY, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, priceTicks, 2);
    }

    private static CoreMatchingOrder ask(long orderId, long priceTicks) {
        return new CoreMatchingOrder(orderId, "BTC-USDT", CoreOrderSide.SELL, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, priceTicks, 2);
    }

    private static CoreMatchingOrder postOnlyBid(long orderId, long priceTicks) {
        return new CoreMatchingOrder(orderId, "BTC-USDT", CoreOrderSide.BUY, CoreOrderType.LIMIT,
                CoreTimeInForce.GTX, priceTicks, 2);
    }

    private static TradingCoreState stateWithOpenBid(long priceTicks) {
        CoreOrderState order = new CoreOrderState(1, ProductLine.SPOT, 7, "BTC-USDT",
                CoreOrderSide.BUY, priceTicks, 2, 0, 2, false, CoreOrderStatus.OPEN, 1);
        return new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(7L, CoreUserState.empty(ProductLine.SPOT, 7)), Map.of(1L, order),
                Map.of("BTC-USDT", CoreInstrument.from(ProductLine.SPOT,
                        new RegisterInstrumentCommand("BTC-USDT", ContractType.SPOT.ordinal(),
                                "BTC", "USDT", "USDT", 1, 1, 1,
                                100_000, 50_000, 0, 0, 0, -1, 0))),
                CoreRiskState.empty(), CoreTreasuryState.empty());
    }

    private static Iterable<CoreOrderState> activeOrders(TradingCoreState state) {
        return new ActiveOrderIndex(state).orders();
    }

    private static CoreMatchingResult result(boolean accepted, String resultCode) {
        return new CoreMatchingResult(accepted, resultCode);
    }

    private static void assertEvidenceEqual(CoreMatchingResult actual, CoreMatchingResult expected) {
        assertThat(actual.nativeCoreSequence()).isEqualTo(expected.nativeCoreSequence());
        assertThat(actual.nativeCommandIdMostSignificantBits())
                .isEqualTo(expected.nativeCommandIdMostSignificantBits());
        assertThat(actual.nativeCommandIdLeastSignificantBits())
                .isEqualTo(expected.nativeCommandIdLeastSignificantBits());
        assertThat(actual.nativeOrderId()).isEqualTo(expected.nativeOrderId());
        assertThat(actual.nativeSequence()).isEqualTo(expected.nativeSequence());
        assertThat(actual.nativeMatcherSequence()).isEqualTo(expected.nativeMatcherSequence());
        assertThat(actual.nativeAeronTimestamp()).isEqualTo(expected.nativeAeronTimestamp());
        assertThat(actual.nativeMatcherShardId()).isEqualTo(expected.nativeMatcherShardId());
    }

    private static CoreMatchingResult nativeResult(int symbolId, long sequence) {
        MatcherResult result = new MatcherResult(sequence, OrderCommandType.PLACE_ORDER, sequence, symbolId,
                100, 1, 100, OrderAction.BID, OrderType.GTC, 7, 1_000, 0,
                CommandResultCode.SUCCESS, List.of(), new MatcherResult.MarketData(List.of(), List.of(), 0, 0));
        return CoreMatchingResult.fromNative(result);
    }

    private static List<MatcherShardProgress> matcherProgress(
            DeterministicExchangeCoreAdapter adapter) throws Exception {
        var field = DeterministicExchangeCoreAdapter.class.getDeclaredField("matcherEvidence");
        field.setAccessible(true);
        return ((MatcherEvidenceLedger) field.get(adapter)).snapshot();
    }
}
