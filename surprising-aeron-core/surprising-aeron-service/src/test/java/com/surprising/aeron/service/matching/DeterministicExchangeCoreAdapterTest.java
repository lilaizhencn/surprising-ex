package com.surprising.aeron.service.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.state.ActiveOrderIndex;
import com.surprising.aeron.service.state.CoreOrderState;
import com.surprising.aeron.service.state.CoreOrderStatus;
import com.surprising.aeron.service.state.CoreRiskState;
import com.surprising.aeron.service.state.CoreTreasuryState;
import com.surprising.aeron.service.state.CoreUserState;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeterministicExchangeCoreAdapterTest {

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
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            assertThat(adapter.placeAsync(7, bid(100)).join().accepted()).isTrue();
            snapshot = adapter.snapshotAsync(91, 1, state, activeOrders(state)).join();
        }

        byte[] encoded = MatcherSnapshotCodec.encode(snapshot);
        MatcherSnapshot decoded = MatcherSnapshotCodec.decode(encoded);
        assertThat(decoded.symbols()).containsExactlyEntriesOf(snapshot.symbols());
        assertThat(decoded.users()).containsExactlyElementsOf(snapshot.users());
        assertThat(decoded.modules()).hasSize(2);
        assertThat(decoded.modules()).zipSatisfy(snapshot.modules(), (actual, expected) -> {
            assertThat(actual.type()).isEqualTo(expected.type());
            assertThat(actual.sequence()).isEqualTo(expected.sequence());
            assertThat(actual.checksum()).isEqualTo(expected.checksum());
            assertThat(actual.data()).containsExactly(expected.data());
        });

        try (DeterministicExchangeCoreAdapter restored =
                     new DeterministicExchangeCoreAdapter(state, activeOrders(state), 1, decoded)) {
            assertThat(restored.orderBooksStateHashAsync().join()).isEqualTo(snapshot.bookStateHash());
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
                    adapter.snapshotAsync(94, 1, state, activeOrders(state));

            persistEntered.join();
            assertThat(first.isDone()).isFalse();
            assertThat(first.cancel(false))
                    .as("caller cancellation must not cancel the nested persist/export operation")
                    .isFalse();
            assertThat(first.isCancelled()).isFalse();

            CompletableFuture<MatcherSnapshot> retry =
                    adapter.snapshotAsync(94, 1, state, activeOrders(state));
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
            snapshot = adapter.snapshotAsync(92, 1, original, activeOrders(original)).join();
        }
        TradingCoreState divergent = stateWithOpenBid(101);
        MatcherSnapshot divergentManifest = new MatcherSnapshot(
                snapshot.productLine(), snapshot.coreShardId(), snapshot.routeVersion(), snapshot.snapshotId(),
                snapshot.coreSequence(), snapshot.matcherSequence(), divergent.businessStateHash(),
                snapshot.engineStateHash(), snapshot.bookStateHash(), snapshot.symbolRegistryHash(), snapshot.userRegistryHash(),
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
                    93, 1, state, activeOrders(state)).join());
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

    private static PlaceOrderCommand bid(long priceTicks) {
        return bid(1, priceTicks);
    }

    private static PlaceOrderCommand bid(long orderId, long priceTicks) {
        return bid(orderId, "BTC-USDT", priceTicks);
    }

    private static PlaceOrderCommand bid(long orderId, String symbol, long priceTicks) {
        return new PlaceOrderCommand(orderId, symbol, 1, "BTC", "USDT", "USDT",
                CoreOrderSide.BUY, priceTicks, 2, false, ReservationKind.SPOT_ASSET, "USDT", 200);
    }

    private static PlaceOrderCommand ask(long orderId, long priceTicks) {
        return new PlaceOrderCommand(orderId, "BTC-USDT", 1, "BTC", "USDT", "USDT",
                CoreOrderSide.SELL, priceTicks, 2, false, ReservationKind.SPOT_ASSET, "BTC", 0);
    }

    private static PlaceOrderCommand postOnlyBid(long orderId, long priceTicks) {
        return new PlaceOrderCommand(orderId, "BTC-USDT", 1, "BTC", "USDT", "USDT",
                CoreOrderSide.BUY, priceTicks, 2, false, CoreMarginMode.CROSS,
                CorePositionSide.NET, ReservationKind.SPOT_ASSET, "USDT", 0,
                CoreOrderType.LIMIT, CoreTimeInForce.GTX, priceTicks, true, "", 0, 0);
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
        return new CoreMatchingResult(accepted, resultCode, List.of());
    }
}
