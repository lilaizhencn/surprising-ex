package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class MatcherPipelineGroupTest {

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void completesOneSymbolShardWhileAnotherShardIsBlocked() throws Exception {
        String previous = System.getProperty("surprising.aeron.matching-engines");
        System.setProperty("surprising.aeron.matching-engines", "4");
        DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter(false);
        MatcherPipelineGroup pipelines = new MatcherPipelineGroup(4, 16, false);
        try {
            pipelines.start(adapter::activateShard);
            String firstSymbol = "SYMBOL-0";
            int firstShard = adapter.matcherShardId(firstSymbol);
            String secondSymbol = null;
            int secondShard = -1;
            for (int index = 1; index < 1_000; index++) {
                String candidate = "SYMBOL-" + index;
                int candidateShard = adapter.matcherShardId(candidate);
                if (candidateShard != firstShard) {
                    secondSymbol = candidate;
                    secondShard = candidateShard;
                    break;
                }
            }
            assertThat(secondSymbol).isNotNull();
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CoreMatchingOrder first = order(1, firstSymbol);
            CoreMatchingOrder second = order(2, secondSymbol);

            pipelines.submit(firstShard, 1, () -> {
                firstStarted.countDown();
                await(releaseFirst);
                return adapter.executeWithEvidenceSync(1, UUID.randomUUID(), 1, 1, 1_000,
                        () -> adapter.place(101, first));
            });
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            pipelines.submit(secondShard, 2, () -> adapter.executeWithEvidenceSync(
                    2, UUID.randomUUID(), 2, 1, 1_001, () -> adapter.place(102, second)));

            assertThat(pipelines.await(2, TimeUnit.SECONDS.toNanos(5)).accepted()).isTrue();
            assertThat(pipelines.poll(1)).isNull();
            releaseFirst.countDown();
            assertThat(pipelines.await(1, TimeUnit.SECONDS.toNanos(5)).accepted()).isTrue();
        } finally {
            pipelines.closeShards(adapter::closeShard);
            if (previous == null) System.clearProperty("surprising.aeron.matching-engines");
            else System.setProperty("surprising.aeron.matching-engines", previous);
        }
    }

    private static CoreMatchingOrder order(long orderId, String symbol) {
        return new CoreMatchingOrder(orderId, symbol, CoreOrderSide.BUY,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, 100, 1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("matcher wait interrupted", exception);
        }
    }
}
