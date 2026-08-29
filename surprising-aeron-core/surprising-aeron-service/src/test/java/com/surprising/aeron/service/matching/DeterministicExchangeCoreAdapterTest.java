package com.surprising.aeron.service.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.state.ActiveOrderIndex;
import com.surprising.aeron.service.state.CoreOrderState;
import com.surprising.aeron.service.state.CoreOrderStatus;
import com.surprising.aeron.service.state.CoreRiskState;
import com.surprising.aeron.service.state.CoreTreasuryState;
import com.surprising.aeron.service.state.CoreUserState;
import com.surprising.aeron.service.state.TradingCoreState;
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
    void acceptsOnlyProductionWaitStrategies() {
        assertThat(MatcherRuntimeConfiguration.waitStrategy("busy_spin"))
                .isEqualTo(exchange.core2.core.common.CoreWaitStrategy.BUSY_SPIN);
        assertThat(MatcherRuntimeConfiguration.waitStrategy("yielding"))
                .isEqualTo(exchange.core2.core.common.CoreWaitStrategy.YIELDING);
        assertThat(MatcherRuntimeConfiguration.waitStrategy("blocking"))
                .isEqualTo(exchange.core2.core.common.CoreWaitStrategy.BLOCKING);
        assertThatThrownBy(() -> MatcherRuntimeConfiguration.waitStrategy("second_step_no_wait"))
                .isInstanceOf(IllegalArgumentException.class);
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
                    1,
                    1_000,
                    () -> adapter.placeAsync(22, command)).join();

            assertThat(result.matcherEvents()).extracting(MatcherResult.MatcherEvent::matchedOrderId)
                    .containsExactly(101L, 102L);
            assertThat(result.nativeCommand().orderId()).isEqualTo(201L);
            assertThat(result.nativeCommand().nativeSequence()).isPositive();
            assertThat(result.matcherPrefix().before()).isNotZero();
            assertThat(result.matcherPrefix().after()).isNotEqualTo(result.matcherPrefix().before());
            assertThat(result.nativeMatcherResult()).isNotNull();
            assertThat(result.matcherEvents()).isSameAs(result.nativeMatcherResult().events());
            assertThat(result.marketData()).isSameAs(result.nativeMatcherResult().marketData());
        }
    }

    @Test
    void pipelinesMatcherCommandsAndChainsImmutableResultDigests() {
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter(false)) {
            CompletableFuture<CoreMatchingResult> firstNative = new CompletableFuture<>();
            CompletableFuture<CoreMatchingResult> secondNative = new CompletableFuture<>();
            AtomicInteger submissions = new AtomicInteger();

            CompletableFuture<CoreMatchingResult> first = adapter.executeWithEvidence(
                    1, java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    101, 7, 1_000, () -> {
                        submissions.incrementAndGet();
                        return firstNative;
                    });
            CompletableFuture<CoreMatchingResult> second = adapter.executeWithEvidence(
                    2, java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"),
                    102, 7, 1_001, () -> {
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

            assertThat(firstResult.matcherPrefix().before()).isNotZero();
            assertThat(firstResult.matcherPrefix().after()).isNotEqualTo(firstResult.matcherPrefix().before());
            assertThat(secondResult.matcherPrefix().before()).isEqualTo(firstResult.matcherPrefix().after());
            assertThat(secondResult.matcherPrefix().after()).isNotEqualTo(secondResult.matcherPrefix().before());
            assertThat(firstResult.nativeCommand().matcherSequence()).isEqualTo(1);
            assertThat(secondResult.nativeCommand().matcherSequence()).isEqualTo(2);
        }
    }

    @Test
    void differentMatcherShardsCanCompleteOutOfSubmissionOrder() {
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter(false)) {
            CompletableFuture<CoreMatchingResult> firstNative = new CompletableFuture<>();
            CompletableFuture<CoreMatchingResult> secondNative = new CompletableFuture<>();
            CompletableFuture<CoreMatchingResult> first = adapter.executeWithEvidence(
                    1, java.util.UUID.fromString("00000000-0000-0000-0000-000000000021"),
                    201, 7, 1_000, () -> firstNative);
            CompletableFuture<CoreMatchingResult> second = adapter.executeWithEvidence(
                    2, java.util.UUID.fromString("00000000-0000-0000-0000-000000000022"),
                    202, 7, 1_001, () -> secondNative);

            secondNative.complete(nativeResult(2, 2));
            CoreMatchingResult secondResult = second.join();
            firstNative.complete(nativeResult(1, 1));
            CoreMatchingResult firstResult = first.join();

            assertThat(firstResult.nativeCommand().matcherShardId()).isNotEqualTo(
                    secondResult.nativeCommand().matcherShardId());
            assertThat(firstResult.matcherPrefix().before()).isEqualTo(CoreMatchingResult.MatcherPrefix.initialDigest());
            assertThat(secondResult.matcherPrefix().before()).isEqualTo(CoreMatchingResult.MatcherPrefix.initialDigest());
        }
    }

    @Test
    void matcherPrefixIgnoresProcessLocalSequenceAndOptionalMarketData() {
        CoreMatchingResult result = result(true, "SUCCESS");
        CoreMatchingResult resultWithMarketData = new CoreMatchingResult(
                result.accepted(), result.resultCode(), result.cancellations(),
                result.successfulPrefixCount(), result.matcherStateChanged(), result.nativeCommand(),
                result.matcherPrefix(), result.nativeMatcherResult(), result.matcherEvents(),
                new MatcherResult.MarketData(
                        List.of(), List.of(new MatcherResult.Level(100, 2, 1)), 0, 0));
        var firstProcess = new CoreMatchingResult.NativeCommand(
                7, "00000000-0000-0000-0000-000000000007", 101, 3, 41, 9, 1_000);
        var restoredProcess = new CoreMatchingResult.NativeCommand(
                7, "00000000-0000-0000-0000-000000000007", 101, 3, 1, 9, 1_000);

        long firstDigest = MatcherPrefixDigest.next(MatcherPrefixDigest.initial(), firstProcess, result);
        long restoredDigest = MatcherPrefixDigest.next(
                MatcherPrefixDigest.initial(), restoredProcess, resultWithMarketData);

        assertThat(restoredDigest).isEqualTo(firstDigest);
    }

    @Test
    void poisonedMatcherRejectsCommandsSubmittedAfterTheFatalCompletion() {
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter(false)) {
            CompletableFuture<CoreMatchingResult> firstNative = new CompletableFuture<>();
            CompletableFuture<CoreMatchingResult> secondNative = new CompletableFuture<>();
            AtomicInteger submissions = new AtomicInteger();
            CompletableFuture<CoreMatchingResult> first = adapter.executeWithEvidence(
                    1, java.util.UUID.fromString("00000000-0000-0000-0000-000000000011"),
                    101, 7, 1_000, () -> {
                        submissions.incrementAndGet();
                        return firstNative;
                    });
            CompletableFuture<CoreMatchingResult> second = adapter.executeWithEvidence(
                    2, java.util.UUID.fromString("00000000-0000-0000-0000-000000000012"),
                    102, 7, 1_001, () -> {
                        submissions.incrementAndGet();
                        return secondNative;
                    });

            firstNative.complete(result(false, "EXCHANGE_CORE_FAILURE"));
            CompletableFuture<CoreMatchingResult> third = adapter.executeWithEvidence(
                    3, java.util.UUID.fromString("00000000-0000-0000-0000-000000000013"),
                    103, 7, 1_002, () -> {
                        submissions.incrementAndGet();
                        return CompletableFuture.completedFuture(result(true, "SUCCESS"));
                    });
            secondNative.complete(result(true, "SUCCESS"));

            assertThat(first.join().resultCode()).isEqualTo("EXCHANGE_CORE_FAILURE");
            assertThat(second.join().resultCode()).isEqualTo("SUCCESS");
            assertThatThrownBy(third::join).hasCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("matcher is poisoned");
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
                    100, 1, 1_000, () -> adapter.placeAsync(7, bid(100))).join();
            assertThat(beforeSnapshot.accepted()).isTrue();
            snapshot = adapter.snapshotAsync(
                    91, 1, state.businessStateHash(), state, activeOrders(state)).join();
        }

        byte[] encoded = MatcherSnapshotCodec.encode(snapshot);
        MatcherSnapshot decoded = MatcherSnapshotCodec.decode(encoded);
        assertThat(decoded.matcherShardProgress()).isEqualTo(snapshot.matcherShardProgress());
        assertThat(decoded.progress(beforeSnapshot.nativeCommand().matcherShardId()).prefixDigest())
                .isEqualTo(beforeSnapshot.matcherPrefix().after());
        assertThat(decoded.symbols()).containsExactlyEntriesOf(snapshot.symbols());
        assertThat(decoded.users()).containsExactlyElementsOf(snapshot.users());
        assertThat(decoded.modules()).hasSize(
                decoded.matchingEngineCount() + decoded.riskEngineCount());
        assertThat(decoded.modules().stream()
                .filter(module -> module.type().name().equals("MATCHING_ENGINE_ROUTER"))
                .map(module -> module.instanceId()).sorted().toList())
                .containsExactly(0, 1, 2, 3);
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
                    101, 1, 1_001, () -> restored.placeAsync(8, bid(101, 90))).join();
            assertThat(afterRestore.matcherPrefix().before()).isEqualTo(
                    snapshot.progress(afterRestore.nativeCommand().matcherShardId()).prefixDigest());
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
                MatcherSnapshot.instrumentRegistryHash(divergent), MatcherSnapshot.activeOrderHash(divergent),
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
        return new CoreOrderState(orderId, ProductLine.SPOT, 7, "BTC-USDT", 1,
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
        CoreOrderState order = new CoreOrderState(1, ProductLine.SPOT, 7, "BTC-USDT", 1,
                CoreOrderSide.BUY, priceTicks, 2, 0, 2, false, CoreOrderStatus.OPEN, 1);
        return new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(7L, CoreUserState.empty(ProductLine.SPOT, 7)), Map.of(1L, order), Map.of(),
                CoreRiskState.empty(), CoreTreasuryState.empty());
    }

    private static Iterable<CoreOrderState> activeOrders(TradingCoreState state) {
        return new ActiveOrderIndex(state).orders();
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
}
