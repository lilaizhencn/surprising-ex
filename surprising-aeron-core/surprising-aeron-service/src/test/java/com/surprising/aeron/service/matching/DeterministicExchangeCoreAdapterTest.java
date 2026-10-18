package com.surprising.aeron.service.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static com.surprising.aeron.service.state.DirectMatcherTestInputs.*;
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
    void directCommandRejectsMissingSettlementBeforeChangingTheBook() {
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            assertThatThrownBy(() -> adapter.placeDirect(adapter.matcherShardId("1"), 1,
                    new java.util.UUID(7, 1), 1000, 7, bid(1, 90), null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(adapter.orderBookLevelsAsync("1", 30).join()).isEmpty();
        }
    }

    @Test
    void failedDirectPrefixRetainsEarlierCancellationAndNeverSubmitsThePlacement() {
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            placeDirect(adapter, 7, bid(1, 90));
            var order = bid(3, 80);
            var id = new java.util.UUID(7, 3);
            var target = preparePlace(adapter, 3, id, 1000, 7, order, List.of(1L, 2L));
            adapter.cancelDirectPrefix(1000, 7, 1, "1");
            target.recordDirectPreCancellation(1);
            var failure = adapter.cancelDirectPrefix(1000, 7, 2, "1");
            adapter.publishDirectPrefixFailure(adapter.matcherShardId("1"), 3, id, 3, 1000, failure, target);
            assertThat(target.accepted()).isFalse();
            assertThat(target.plan().preCancellationCount()).isEqualTo(1);
            assertThat(target.outcome()).isEqualTo(MatchingResult.Outcome.FATAL_DIVERGENCE);
            assertThat(target.nativeMatches(id)).isTrue();
            assertThat(target.nativeOrderId()).isEqualTo(3);
            assertThat(adapter.orderBookLevelsAsync("1", 30).join()).isEmpty();
        }
    }

    @Test
    void depthQueriesDoNotDiscardRestingOrdersBeyondVisibleLevels() {
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            for (int level = 0; level < 151; level++) {
                assertThat(placeDirect(adapter, 11, new CoreMatchingOrder(1000 + level, "21",
                        CoreOrderSide.SELL, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                        100_000 + level, 1)).accepted()).isTrue();
            }
            assertThat(adapter.orderBookLevelsAsync("21", 50).join()).hasSize(50);
            assertThat(adapter.orderBookLevelsAsync("21", 100).join()).hasSize(100);
            var result = placeDirect(adapter, 22, new CoreMatchingOrder(2000, "21",
                    CoreOrderSide.BUY, CoreOrderType.LIMIT, CoreTimeInForce.IOC,
                    100_150, 151));
            var trades = result.matcherEvents().stream()
                    .filter(e -> e.eventType() == exchange.core2.core.common.MatcherEventType.TRADE)
                    .toList();
            assertThat(trades).hasSize(151);
            assertThat(trades.stream().mapToLong(MatcherResult.MatcherEvent::size).sum()).isEqualTo(151);
            assertThat(trades.getLast().price()).isEqualTo(100_150);
            assertThat(adapter.orderBookLevelsAsync("21", 100).join()).isEmpty();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(CoreOrderSide.class)
    void linearMarketOrderCannotSweepBeyondOneBasisPoint(CoreOrderSide side) {
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            boolean buy = side == CoreOrderSide.BUY;
            CoreOrderSide makerSide = buy ? CoreOrderSide.SELL : CoreOrderSide.BUY;
            for (int level = 0; level < 3; level++) {
                placeDirect(adapter, 11, new CoreMatchingOrder(101 + level, "21", makerSide,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                        100_000 + (buy ? 1 : -1) * level * 10, 2));
            }
            var order = new CoreMatchingOrder(201, "21", side, CoreOrderType.MARKET,
                    CoreTimeInForce.IOC, buy ? 110_000 : 90_000, 6, 100);
            var result = placeDirect(adapter, 4, new java.util.UUID(22, 4), 1000, 22, order);
            var trades = result.matcherEvents().stream()
                    .filter(e -> e.eventType() == exchange.core2.core.common.MatcherEventType.TRADE).toList();
            assertThat(trades).extracting(MatcherResult.MatcherEvent::price)
                    .containsExactly(100_000L, buy ? 100_010L : 99_990L);
            assertThat(trades.stream().mapToLong(MatcherResult.MatcherEvent::size).sum()).isEqualTo(4);
        }
    }

    @Test
    void marketSlippageRoundingDoesNotAllowAnExtraTick() {
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            placeDirect(adapter, 11, ask(101, 99));
            placeDirect(adapter, 11, ask(102, 100));
            var result = placeDirect(adapter, 22, new CoreMatchingOrder(201, "1", CoreOrderSide.BUY,
                    CoreOrderType.MARKET, CoreTimeInForce.IOC, 110, 100, 100));
            assertThat(result.matcherEvents().stream()
                    .filter(e -> e.eventType() == exchange.core2.core.common.MatcherEventType.TRADE))
                    .extracting(MatcherResult.MatcherEvent::price).containsExactly(99L);
        }
    }

    @Test
    void nativeCancellationPreservesEvidenceForPartialFillsAndRepeatedCancellation() {
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            var order = new CoreMatchingOrder(901, "31", CoreOrderSide.BUY,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 10);
            placeDirect(adapter, 7, order);
            placeDirect(adapter, 8, new CoreMatchingOrder(902, "31", CoreOrderSide.SELL,
                    CoreOrderType.LIMIT, CoreTimeInForce.IOC, 90, 4));
            var accepted = cancelDirect(adapter, 3, new java.util.UUID(7, 3), 3000, 7, 901, "31");
            assertThat(accepted.accepted()).isTrue();
            assertThat(accepted.matcherEvents()).extracting(MatcherResult.MatcherEvent::size).containsExactly(6L);
            var rejected = cancelDirect(adapter, 4, new java.util.UUID(7, 4), 4000, 7, 901, "31");
            assertThat(rejected.accepted()).isFalse();
            assertThat(rejected.outcome()).isEqualTo(MatchingResult.Outcome.REJECTED_UNCHANGED);
            assertThat(rejected.nativeCoreSequence()).isEqualTo(4);
            assertThat(rejected.nativeMatches(new java.util.UUID(7, 4))).isTrue();
            assertThat(rejected.nativeOrderId()).isEqualTo(901);
            assertThat(rejected.nativeMatcherSequence()).isGreaterThan(accepted.nativeMatcherSequence());
            assertThat(accepted.matcherEvents()).extracting(MatcherResult.MatcherEvent::size).containsExactly(6L);
        }
    }

    @Test
    void nativePlacementHonorsPoisonAndValidatesEvidenceBeforeMatching() {
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            var order = bid(901, 90);
            var id = new java.util.UUID(0, 1);
            var target = preparePlace(adapter, 1, id, 1000, 7, order);
            assertThatThrownBy(() -> adapter.placeDirect(adapter.matcherShardId("1"), 0, id,
                    1000, 7, order, target)).isInstanceOf(IllegalArgumentException.class);
            assertThat(adapter.orderBookLevelsAsync("1", 30).join()).isEmpty();
            adapter.placeDirect(adapter.matcherShardId("1"), 1, id, 1000, 7, order, target);
            assertThat(target.accepted()).isTrue();
            assertThat(target.nativeMatcherSequence()).isEqualTo(1);
            adapter.poisonFromOwner("test divergence");
            assertThatThrownBy(() -> placeDirect(adapter, 7, bid(902, 90)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("matcher is poisoned");
        }
    }

    @Test
    void directPlacementRetainsEvidenceWithoutChangingEarlierSlots() {
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            MatchingResult first = null;
            for (int i = 1; i <= 4; i++) {
                var order = new CoreMatchingOrder(i, "21", i == 2 ? CoreOrderSide.SELL : CoreOrderSide.BUY,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1);
                var id = new java.util.UUID(7, i);
                var actual = placeDirect(adapter, i, id, 1000 + i, i == 2 ? 8 : 7, order);
                assertThat(actual.accepted()).isTrue();
                assertThat(actual.nativeCoreSequence()).isEqualTo(i);
                assertThat(actual.nativeMatches(id)).isTrue();
                assertThat(actual.nativeOrderId()).isEqualTo(i);
                assertThat(actual.nativeMatcherSequence()).isEqualTo(i);
                assertThat(actual.nativeMatcherShardId()).isEqualTo(adapter.matcherShardId("21"));
                assertThat(actual.nativeMatcherResult().timestamp()).isEqualTo(1000 + i);
                if (first == null) first = actual;
            }
            assertThat(first.nativeCoreSequence()).isEqualTo(1);
            assertThat(first.matcherEvents()).isEmpty();
        }
    }

    @Test
    void registeredSymbolLookupDoesNotAcquireRegistrationMonitor() throws Exception {
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            int expected = adapter.matcherShardId("22");
            synchronized (adapter) {
                var result = CompletableFuture.supplyAsync(() -> adapter.matcherShardId("22"));
                assertThat(result.get(2, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(expected);
            }
            var calls = new ArrayList<CompletableFuture<Integer>>();
            for (int i = 0; i < 32; i++) calls.add(CompletableFuture.supplyAsync(() -> adapter.matcherShardId("23")));
            for (var result : calls) assertThat(result.get(2, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(adapter.matcherShardId("23"));
        }
    }

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void controlEvidenceChecksNativeSequenceWithinItsActualOrderBookPartition() {
        String previous = System.getProperty("surprising.aeron.matching-engines");
        System.setProperty("surprising.aeron.matching-engines", "2");
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            String first = "25", second = "26";
            for (int attempt = 0; adapter.matcherShardId(first) == adapter.matcherShardId(second); attempt++) {
                if (attempt == 128) throw new AssertionError("could not select a second partition");
                second = Integer.toString(100 + attempt);
            }
            for (long id = 1; id <= 16; id++) placeDirect(adapter, 7, bid(id, first, 90));
            placeDirect(adapter, 8, bid(17, second, 90));
            var firstNative = adapter.cancelControlOrder(1000, 7, 16, first);
            var a = adapter.completeControlCancellations(18, new java.util.UUID(7, 18), 16, 1000,
                    List.of(new DeterministicExchangeCoreAdapter.CancellationOrder(16, 7, first)), List.of(firstNative));
            var secondNative = adapter.cancelControlOrder(1000, 8, 17, second);
            var b = adapter.completeControlCancellations(19, new java.util.UUID(8, 19), 17, 1000,
                    List.of(new DeterministicExchangeCoreAdapter.CancellationOrder(17, 8, second)), List.of(secondNative));
            assertThat(a.accepted()).isTrue();
            assertThat(b.accepted()).isTrue();
            assertThat(secondNative.nativeSequence()).isLessThan(firstNative.nativeSequence());
            assertThat(b.nativeMatcherSequence()).isGreaterThan(a.nativeMatcherSequence());
        } finally {
            if (previous == null) System.clearProperty("surprising.aeron.matching-engines");
            else System.setProperty("surprising.aeron.matching-engines", previous);
        }
    }

    @Test
    void reusedCommandScopeDoesNotLeakTimestampIntoTheNextCommand() {
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            var first = placeDirect(adapter, 1, new java.util.UUID(0, 1), 1234, 7, bid(901, 90));
            var second = placeDirect(adapter, 2, new java.util.UUID(0, 2), 5678, 7, bid(902, 90));
            var third = placeDirect(adapter, 3, new java.util.UUID(0, 3), 0, 7, bid(903, 90));
            assertThat(first.nativeMatcherResult().timestamp()).isEqualTo(1234);
            assertThat(second.nativeMatcherResult().timestamp()).isEqualTo(5678);
            assertThat(third.nativeMatcherResult().timestamp()).isZero();
        }
    }

    @Test
    void cancelBatchStopsAtFirstFailureAndReturnsSuccessfulPrefix() {
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            placeDirect(adapter, 7, bid(1, 90));
            placeDirect(adapter, 7, bid(3, 80));
            var result = adapter.cancelControlOrders(4, new java.util.UUID(7, 4), 0, 1000, List.of(
                    new DeterministicExchangeCoreAdapter.CancellationOrder(1, 7, "1"),
                    new DeterministicExchangeCoreAdapter.CancellationOrder(2, 7, "1"),
                    new DeterministicExchangeCoreAdapter.CancellationOrder(3, 7, "1")));
            assertThat(result.accepted()).isFalse();
            assertThat(result.successfulPrefixCount()).isEqualTo(1);
            assertThat(result.matcherStateChanged()).isTrue();
            assertThat(result.cancellations()).extracting(CoreCancellationResult::resultCode)
                    .containsExactly("SUCCESS", "MATCHING_UNKNOWN_ORDER_ID", "NOT_SUBMITTED");
            assertThat(cancelDirect(adapter, 5, new java.util.UUID(7, 5), 1000, 7, 3, "1").accepted()).isTrue();
        }
    }

    @Test
    void rejectedPostOnlyPlacementRetainsSuccessfulDirectCancellationPrefix() {
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            placeDirect(adapter, 7, bid(1, 100));
            placeDirect(adapter, 8, ask(2, 110));
            var command = postOnlyBid(3, 120);
            var id = new java.util.UUID(7, 3);
            var target = preparePlace(adapter, 3, id, 1000, 7, command, List.of(1L));
            assertThat(adapter.cancelDirectPrefix(1000, 7, 1, "1").resultCode())
                    .isEqualTo(CommandResultCode.SUCCESS);
            target.recordDirectPreCancellation(1);
            adapter.placeDirect(adapter.matcherShardId("1"), 3, id, 1000, 7, command, target);
            assertThat(target.accepted()).isFalse();
            assertThat(target.matcherStateChanged()).isTrue();
            assertThat(target.plan().preCancellationCount()).isEqualTo(1);
            assertThat(target.outcome()).isEqualTo(MatchingResult.Outcome.KNOWN_PREFIX_APPLIED);
        }
    }

    @Test
    void consumesImmutableMatcherResultWithoutPerCommandStateReports() {
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            placeDirect(adapter, 11, ask(101, 100));
            placeDirect(adapter, 12, ask(102, 100));
            var command = new CoreMatchingOrder(201, "1", CoreOrderSide.BUY,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, 100, 4);
            var result = placeDirect(adapter, 22, command);
            assertThat(result.matcherEvents()).extracting(MatcherResult.MatcherEvent::matchedOrderId)
                    .containsExactly(101L, 102L);
            assertThat(result.nativeOrderId()).isEqualTo(201);
            assertThat(result.nativeMatcherResult().sequence()).isPositive();
            assertThat(result.matcherEvents()).isSameAs(result.nativeMatcherResult().events());
            assertThat(result.marketData()).isSameAs(result.nativeMatcherResult().marketData());
        }
    }

    @Test
    void matcherEvidenceAdvancesSequences() {
        var ledger = new MatcherEvidenceLedger(com.surprising.aeron.service.state.LaneTopology.productionDefault());
        var first = ledger.bind(1, new java.util.UUID(0, 1), 101, 1000,
                ledger.nextSequence(0), 0, 0, nativeResult(2, 1));
        var second = ledger.bind(2, new java.util.UUID(0, 2), 102, 1001,
                ledger.nextSequence(0), 0, 0, nativeResult(2, 2));
        assertThat(first.nativeMatcherSequence()).isEqualTo(1);
        assertThat(second.nativeMatcherSequence()).isEqualTo(2);
    }

    @Test
    void fixedMatcherShardRejectsOutOfNativeSequenceCompletion() {
        var ledger = new MatcherEvidenceLedger(com.surprising.aeron.service.state.LaneTopology.productionDefault());
        ledger.bind(2, new java.util.UUID(0, 2), 202, 1001,
                ledger.nextSequence(0), 0, 0, nativeResult(2, 2));
        assertThatThrownBy(() -> ledger.bind(1, new java.util.UUID(0, 1), 201, 1000,
                ledger.nextSequence(0), 0, 0, nativeResult(2, 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("native sequence is not strictly increasing");
    }

    @Test
    void poisonRejectsPreparedDirectCommandWithoutAdvancingEvidence() throws Exception {
        try (var adapter = new DeterministicExchangeCoreAdapter()) {
            var command = bid(1, 90);
            var id = new java.util.UUID(0, 1);
            var prepared = preparePlace(adapter, 1, id, 1000, 7, command);
            adapter.poisonFromOwner("fatal divergence");
            var progress = matcherProgress(adapter);
            assertThatThrownBy(() -> adapter.placeDirect(adapter.matcherShardId("1"), 1, id, 1000,
                    7, command, prepared)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("matcher is poisoned");
            assertThat(prepared.resultPrepared()).isFalse();
            assertThat(matcherProgress(adapter)).isEqualTo(progress);
        }
    }

    @Test
    void singleSymbolBookQueryAndBootstrapUseSeparateScopes() {
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            assertThat(placeDirect(adapter, 7, bid(1, 100)).accepted()).isTrue();
            assertThat(placeDirect(adapter, 8, bid(2, "2", 200)).accepted()).isTrue();

            assertThat(adapter.orderBookLevelsAsync("1", 30).join())
                    .extracting(value -> value.instrumentId()).containsOnly("1");
            BookBootstrapSnapshot bootstrap = adapter.orderBookBootstrapAsync(30).join();
            assertThat(bootstrap.symbols()).containsExactly("1", "2");
            assertThat(bootstrap.levels()).extracting(value -> value.instrumentId())
                    .containsExactly("1", "2");
            assertThatThrownBy(() -> adapter.orderBookLevelsAsync("", 30).join())
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "1", "1023", "1024", "10000", "10127", "2147483647"})
    void nativeSnapshotRoundTripRestoresTheOnlyExecutableBook(String instrumentId) {
        TradingCoreState state = stateWithOpenBid(100, instrumentId);
        MatcherSnapshot snapshot;
        int bookHash;
        MatchingResult beforeSnapshot;
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            beforeSnapshot = placeDirect(adapter, 1, new java.util.UUID(0, 100), 1000, 7,
                    bid(1, instrumentId, 100));
            assertThat(beforeSnapshot.accepted()).isTrue();
            snapshot = adapter.snapshotAsync(
                    91, 1, state.businessStateHash(), state, activeOrders(state)).join();
            bookHash = adapter.orderBooksStateHashAsync().join();
        }

        byte[] encoded = MatcherSnapshotCodec.encode(snapshot);
        MatcherSnapshot decoded = MatcherSnapshotCodec.decode(encoded);
        assertThat(decoded.symbols()).containsEntry(instrumentId, Integer.parseInt(instrumentId));
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
            assertThat(restored.orderBooksStateHashAsync().join()).isEqualTo(bookHash);
            var afterRestore = placeDirect(restored, 2, new java.util.UUID(0, 101), 1001, 8,
                    bid(101, instrumentId, 90));
            assertThat(afterRestore.accepted()).isTrue();
            assertThat(restored.orderBookLevelsAsync(instrumentId, 30).join()).hasSize(2);
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
            assertThat(placeDirect(adapter, 7, bid(100)).accepted()).isTrue();
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
            assertThat(placeDirect(adapter, 7, bid(100)).accepted()).isTrue();
            snapshot = adapter.snapshotAsync(
                    92, 1, original.businessStateHash(), original, activeOrders(original)).join();
        }
        TradingCoreState divergent = stateWithOpenBid(101);
        MatcherSnapshot divergentManifest = new MatcherSnapshot(
                snapshot.productLine(), snapshot.topology(), snapshot.snapshotId(),
                snapshot.coreSequence(), snapshot.matcherSequence(), snapshot.matcherShardProgress(),
                divergent.businessStateHash(),
                snapshot.engineStateHash(),
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
            assertThat(placeDirect(adapter, 7, bid(100)).accepted()).isTrue();
            encoded = MatcherSnapshotCodec.encode(adapter.snapshotAsync(
                    93, 1, state.businessStateHash(), state, activeOrders(state)).join());
        }
        encoded[encoded.length / 2] ^= 1;

        assertThatThrownBy(() -> MatcherSnapshotCodec.decode(encoded))
                .isInstanceOf(com.surprising.aeron.protocol.ProtocolException.class)
                .hasMessageContaining("checksum");
    }

    private static CoreOrderState order(long orderId) {
        return new CoreOrderState(orderId, ProductLine.SPOT, 7, "1",
                CoreOrderSide.BUY, 100, 1, 0, 1, false, CoreOrderStatus.OPEN, 1);
    }

    private static CoreMatchingOrder bid(long priceTicks) {
        return bid(1, priceTicks);
    }

    private static CoreMatchingOrder bid(long orderId, long priceTicks) {
        return bid(orderId, "1", priceTicks);
    }

    private static CoreMatchingOrder bid(long orderId, String instrumentId, long priceTicks) {
        return new CoreMatchingOrder(orderId, instrumentId, CoreOrderSide.BUY, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, priceTicks, 2);
    }

    private static CoreMatchingOrder ask(long orderId, long priceTicks) {
        return new CoreMatchingOrder(orderId, "1", CoreOrderSide.SELL, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, priceTicks, 2);
    }

    private static CoreMatchingOrder postOnlyBid(long orderId, long priceTicks) {
        return new CoreMatchingOrder(orderId, "1", CoreOrderSide.BUY, CoreOrderType.LIMIT,
                CoreTimeInForce.GTX, priceTicks, 2);
    }

    private static TradingCoreState stateWithOpenBid(long priceTicks) {
        return stateWithOpenBid(priceTicks, "1");
    }

    private static TradingCoreState stateWithOpenBid(long priceTicks, String instrumentId) {
        CoreOrderState order = new CoreOrderState(1, ProductLine.SPOT, 7, instrumentId,
                CoreOrderSide.BUY, priceTicks, 2, 0, 2, false, CoreOrderStatus.OPEN, 1);
        return new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(7L, CoreUserState.empty(ProductLine.SPOT, 7)), Map.of(1L, order),
                Map.of(instrumentId, CoreInstrument.from(ProductLine.SPOT,
                        new RegisterInstrumentCommand(instrumentId, ContractType.SPOT.ordinal(),
                                "BTC", "USDT", "USDT", 1, 1, 1,
                                100_000, 50_000, 0, 0, 0, -1, 0))),
                CoreRiskState.empty(), CoreTreasuryState.empty());
    }

    private static Iterable<CoreOrderState> activeOrders(TradingCoreState state) {
        return state.orders().values().stream().filter(order -> order.status() == com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN).toList();
    }

    private static CoreMatchingResult result(boolean accepted, String resultCode) {
        return new CoreMatchingResult(accepted, resultCode);
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
