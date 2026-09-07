package com.surprising.aeron.tools;

import java.util.concurrent.CompletableFuture;
import java.util.function.LongFunction;

/** Shares only an in-progress update for a symbol; completed updates expire by source timestamp. */
final class ClusterMarkPriceGate {
    private CompletableFuture<Void> pending;
    private long publishedMillis;

    synchronized CompletableFuture<Void> refresh(long now, LongFunction<CompletableFuture<Void>> publish) {
        if (pending != null && !pending.isDone()) return pending;
        if (publishedMillis != 0 && now >= publishedMillis && now - publishedMillis < 1_000) {
            return CompletableFuture.completedFuture(null);
        }
        pending = publish.apply(now).thenRun(() -> published(now));
        return pending;
    }

    private synchronized void published(long timestamp) {
        publishedMillis = timestamp;
    }
}
