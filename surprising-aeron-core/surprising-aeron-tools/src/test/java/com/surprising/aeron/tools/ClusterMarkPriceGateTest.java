package com.surprising.aeron.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClusterMarkPriceGateTest {
    @Test void sharesInProgressFeedAndExpiresBySourceTimestamp() {
        var gate = new ClusterMarkPriceGate();
        var feed = new CompletableFuture<Void>();
        var calls = new AtomicInteger();
        var first = gate.refresh(1000, now -> { calls.incrementAndGet(); return feed; });
        var second = gate.refresh(1500, now -> { throw new AssertionError("duplicate feed"); });
        assertThat(second).isSameAs(first);
        assertThat(first).isNotDone();
        feed.complete(null);
        assertThat(gate.refresh(1999, now -> { throw new AssertionError("still fresh"); })).isCompleted();
        gate.refresh(2000, now -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(null); }).join();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void failedFeedDoesNotMakePriceFresh() {
        var gate = new ClusterMarkPriceGate();
        var failure = new IllegalStateException("price rejected");
        assertThatThrownBy(() -> gate.refresh(1000, now -> CompletableFuture.failedFuture(failure)).join()).hasCause(failure);
        var calls = new AtomicInteger();
        gate.refresh(1001, now -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(null); }).join();
        assertThat(calls.get()).isEqualTo(1);
    }
}
