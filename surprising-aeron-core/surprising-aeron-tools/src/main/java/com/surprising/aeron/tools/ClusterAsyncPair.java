package com.surprising.aeron.tools;

import com.surprising.aeron.protocol.CoreResponse;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One window slot: price prerequisite, maker terminal, then taker terminal. Never blocks a callback. */
final class ClusterAsyncPair {
    private ClusterAsyncPair() { }

    static CompletableFuture<Void> start(CompletableFuture<Void> price,
            Supplier<CompletableFuture<CoreResponse>> maker,
            Supplier<CompletableFuture<CoreResponse>> taker,
            BiConsumer<CoreResponse, Long> makerTerminal,
            BiConsumer<CoreResponse, Long> takerTerminal, LongSupplier nanoTime) {
        return price.thenCompose(ignored -> timed(maker, makerTerminal, nanoTime))
                .thenCompose(ignored -> timed(taker, takerTerminal, nanoTime));
    }

    private static CompletableFuture<Void> timed(Supplier<CompletableFuture<CoreResponse>> submit,
            BiConsumer<CoreResponse, Long> terminal, LongSupplier nanoTime) {
        long started = nanoTime.getAsLong();
        return submit.get().thenAccept(response ->
                terminal.accept(response, Math.max(1, nanoTime.getAsLong() - started)));
    }
}
