package com.surprising.aeron.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TradingRuntimeOwnershipTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void rejectedForeignCloseDoesNotPoisonTheOwnerLifecycle(ProductLine product) throws Exception {
        try (var runtime = new TradingCoreRuntime(product)) {
            runtime.activate();
            long initialHash = runtime.stateHash();
            var rejected = new AtomicReference<Throwable>();
            Thread thread = Thread.ofPlatform().start(() -> {
                try { runtime.close(); }
                catch (Throwable failure) { rejected.set(failure); }
            });
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
            assertThat(rejected.get()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("another thread");
            assertThat(runtime.closed).as("a foreign caller must not claim shutdown").isFalse();
            assertThat(runtime.stateHash()).isEqualTo(initialHash);
            runtime.close();
            assertThat(runtime.closed).isTrue();
        }
    }
}
