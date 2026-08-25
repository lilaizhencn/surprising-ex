package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.product.api.ProductLine;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TradingCoreRuntimeAuthorityTest {

    @Test
    void materializesCompatibilityStateFromAuthoritativeRuntime() {
        try (TradingCoreRuntime runtime = new TradingCoreRuntime(
                ProductLine.SPOT, TradingCoreState.empty(ProductLine.SPOT))) {
            runtime.runtimeStateForConstruction().setMetadata(ProductLine.SPOT, 7);

            assertThat(runtime.snapshotState().revision()).isEqualTo(7);
        }
    }

    @Test
    void protectsAuthoritativeRuntimeFromNonOwnerThreads() throws InterruptedException {
        try (TradingCoreRuntime runtime = new TradingCoreRuntime(
                ProductLine.SPOT, TradingCoreState.empty(ProductLine.SPOT))) {
            runtime.bindOwner();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    runtime.runtimeStateForConstruction();
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            other.start();
            other.join();

            assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> {
                if (failure.get() == null) throw new AssertionError("missing owner-thread rejection");
                throw failure.get();
            }).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void doesNotExposeImmutableOutcomeToRuntimeMutationAdapters() {
        assertThatThrownBy(() -> Class.forName(
                "com.surprising.aeron.service.state.RuntimeStateDeltaApplier"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName(
                "com.surprising.aeron.service.state.RuntimePlaceOrderDeltaApplier"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName(
                "com.surprising.aeron.service.state.RuntimeCancelOrderDeltaApplier"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
