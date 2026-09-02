package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MatcherCommandPipelineTest {

    @Test
    void keepsABoundedWindowAndPublishesCompletionsInCommandOrder() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try (MatcherCommandPipeline pipeline = new MatcherCommandPipeline(4)) {
            pipeline.submit(1, () -> {
                firstStarted.countDown();
                await(releaseFirst);
                return new CoreMatchingResult(true, "ONE");
            });
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            pipeline.submit(2, () -> new CoreMatchingResult(true, "TWO"));
            pipeline.submit(3, () -> new CoreMatchingResult(true, "THREE"));
            pipeline.submit(4, () -> new CoreMatchingResult(true, "FOUR"));

            assertThat(pipeline.inFlight()).isEqualTo(4);
            assertThat(pipeline.submissionHighWaterMark()).isEqualTo(4);
            assertThatThrownBy(() -> pipeline.submit(5, () -> new CoreMatchingResult(true, "FIVE")))
                    .isInstanceOf(RejectedExecutionException.class)
                    .hasMessageContaining("full");

            releaseFirst.countDown();
            assertThat(pipeline.await(1, TimeUnit.SECONDS.toNanos(5)).resultCode()).isEqualTo("ONE");
            assertThat(pipeline.await(2, TimeUnit.SECONDS.toNanos(5)).resultCode()).isEqualTo("TWO");
            assertThat(pipeline.await(3, TimeUnit.SECONDS.toNanos(5)).resultCode()).isEqualTo("THREE");
            assertThat(pipeline.await(4, TimeUnit.SECONDS.toNanos(5)).resultCode()).isEqualTo("FOUR");
            assertThat(pipeline.inFlight()).isZero();
            assertThat(pipeline.completionHighWaterMark()).isPositive();
        }
    }

    @Test
    void propagatesMatcherFailureToTheOwner() {
        try (MatcherCommandPipeline pipeline = new MatcherCommandPipeline(2)) {
            pipeline.submit(1, () -> {
                throw new IllegalStateException("matcher failed");
            });

            assertThatThrownBy(() -> pipeline.await(1, TimeUnit.SECONDS.toNanos(5)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("matcher failed");
        }
    }

    @Test
    void wakesAfterRepeatedIdleAndBurstCyclesWithoutLosingACompletion() {
        try (MatcherCommandPipeline pipeline = new MatcherCommandPipeline(8)) {
            for (long cycle = 1; cycle <= 100; cycle++) {
                long first = cycle * 2 - 1;
                long second = cycle * 2;
                pipeline.submit(first, () -> new CoreMatchingResult(true, "FIRST"));
                pipeline.submit(second, () -> new CoreMatchingResult(true, "SECOND"));

                assertThat(pipeline.await(first, TimeUnit.SECONDS.toNanos(5)).resultCode())
                        .isEqualTo("FIRST");
                assertThat(pipeline.await(second, TimeUnit.SECONDS.toNanos(5)).resultCode())
                        .isEqualTo("SECOND");
                assertThat(pipeline.inFlight()).isZero();
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(200));
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test matcher release timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test matcher interrupted", exception);
        }
    }
}
