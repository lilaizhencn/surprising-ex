package com.surprising.aeron.service.orchestration.trade;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.matcher.MatcherPipelineGroup;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.assertj.core.api.Assertions.assertThat;

class MatcherPipelineGroupTest {

    @Test
    void matcherPublishesWithoutOwnerDrainAndTransportRetirementDoesNotTouchReusedSlot() {
        var contexts = new CommandSlotRing(1, 1);
        var slot = contexts.claim(1);
        try (var pipelines = new MatcherPipelineGroup(1, 2, true, contexts)) {
            pipelines.submit(0, 1,
                    () -> new com.surprising.aeron.service.matching.CoreMatchingResult(true, "FIRST"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!slot.hasMatchingCompletion() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(slot.hasMatchingCompletion()).isTrue();
            var result = slot.takeMatchingCompletion();
            assertThat(result.resultCode()).isEqualTo("FIRST");
            slot.result(result, 0, 1);
            assertThat(slot.submittedMatcherShard()).isEqualTo(-1);
            contexts.release(1);
            var reused = contexts.claim(2);
            assertThat(reused).isSameAs(slot);
            pipelines.drainMatchingCompletions();
            assertThat(reused.coreSequence()).isEqualTo(2);
            assertThat(reused.hasMatchingCompletion()).isFalse();
            pipelines.submit(0, 2,
                    () -> new com.surprising.aeron.service.matching.CoreMatchingResult(true, "SECOND"));
            assertThat(await(pipelines, 2, TimeUnit.SECONDS.toNanos(5)).resultCode()).isEqualTo("SECOND");
        }
    }

    @Test
    void emptyProbeDoesNotLoseALaterMatcherPublication() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pipelines = pipelines(2, 4, true, 42)) {
            try {
                pipelines.submit(1, 42, () -> {
                    entered.countDown();
                    await(release);
                    return new com.surprising.aeron.service.matching.CoreMatchingResult(true, "OK");
                });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                for (int i = 0; i < 4; i++) assertThat(pipelines.hasMatchingCompletions()).isFalse();
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!pipelines.hasMatchingCompletions() && System.nanoTime() < deadline) Thread.yield();
                assertThat(pipelines.hasMatchingCompletions()).isTrue();
                assertThat(await(pipelines, 42, TimeUnit.SECONDS.toNanos(5))).isNotNull();
                assertThat(pipelines.hasMatchingCompletions()).isFalse();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void drainsCompletedShardHeadsWithoutProbingPendingSequences() {
        MatcherPipelineGroup pipelines = pipelines(2, 4, true, 11, 12);
        try {
            pipelines.submit(0, 11, () -> new com.surprising.aeron.service.matching.CoreMatchingResult(true, "ONE"));
            pipelines.submit(1, 12, () -> new com.surprising.aeron.service.matching.CoreMatchingResult(true, "TWO"));
            ArrayList<Long> completed = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (completed.size() < 2 && System.nanoTime() < deadline) {
                for (long sequence : new long[] {11, 12}) {
                    if (!completed.contains(sequence) && pipelines.poll(sequence) != null) completed.add(sequence);
                }
                Thread.onSpinWait();
            }
            assertThat(completed).containsExactlyInAnyOrder(11L, 12L);
            // Command results precede the transport completion cursor. Keep draining until
            // both submitted cells have retired before checking the completion queue depth.
            while ((pipelines.submissionDepth() != 0 || pipelines.completionDepth() != 0)
                    && System.nanoTime() < deadline) {
                pipelines.drainMatchingCompletions();
                Thread.onSpinWait();
            }
            assertThat(pipelines.submissionDepth()).isZero();
            assertThat(pipelines.completionDepth()).isZero();
        } finally {
            pipelines.close();
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void completesOneSymbolShardWhileAnotherShardIsBlocked() throws Exception {
        String previous = System.getProperty("surprising.aeron.matching-engines");
        System.setProperty("surprising.aeron.matching-engines", "4");
        DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter(false);
        MatcherPipelineGroup pipelines = pipelines(4, 16, false, 1, 2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try {
            pipelines.start(adapter::activateShard);
            String firstSymbol = "39";
            int firstShard = adapter.matcherShardId(firstSymbol);
            String secondSymbol = null;
            int secondShard = -1;
            for (int index = 1; index < 1_000; index++) {
                String candidate = Integer.toString(100 + index);
                int candidateShard = adapter.matcherShardId(candidate);
                if (candidateShard != firstShard) {
                    secondSymbol = candidate;
                    secondShard = candidateShard;
                    break;
                }
            }
            assertThat(secondSymbol).isNotNull();
            CountDownLatch firstStarted = new CountDownLatch(1);
            CoreMatchingOrder first = order(1, firstSymbol);
            CoreMatchingOrder second = order(2, secondSymbol);

            var firstId = UUID.randomUUID();
            var secondId = UUID.randomUUID();
            var firstTarget = com.surprising.aeron.service.state.DirectMatcherTestInputs.preparePlace(
                    adapter, 1, firstId, 1000, 101, first);
            var secondTarget = com.surprising.aeron.service.state.DirectMatcherTestInputs.preparePlace(
                    adapter, 2, secondId, 1001, 102, second);
            pipelines.submit(firstShard, 1, () -> {
                firstStarted.countDown();
                await(releaseFirst);
                return adapter.placeDirect(firstShard, 1, firstId, 1000, 101, first, firstTarget);
            }, firstTarget);
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            int readyShard = secondShard;
            pipelines.submit(secondShard, 2, () -> adapter.placeDirect(
                    readyShard, 2, secondId, 1001, 102, second, secondTarget), secondTarget);

            awaitDirect(secondTarget);
            var secondResult = secondTarget;
            assertThat(secondResult.accepted()).isTrue();
            assertThat(secondResult.nativeMatcherResult().timestamp()).isEqualTo(1_001);
            assertThat(firstTarget.ready()).isFalse();
            releaseFirst.countDown();
            awaitDirect(firstTarget);
            var firstResult = firstTarget;
            assertThat(firstResult.accepted()).isTrue();
            assertThat(firstResult.nativeMatcherResult().timestamp()).isEqualTo(1_000);
        } finally {
            releaseFirst.countDown();
            pipelines.closeShards(adapter::closeShard);
            if (previous == null) System.clearProperty("surprising.aeron.matching-engines");
            else System.setProperty("surprising.aeron.matching-engines", previous);
        }
    }

    private static void awaitDirect(com.surprising.aeron.service.state.settlement.MatcherSettlementEvent event) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!event.ready()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Direct Matcher result timed out");
            Thread.onSpinWait();
        }
    }

    @Test
    void sequenceSlotRejectsDuplicateRoutesAndRecoversAfterQueueRejection() {
        var contexts = new CommandSlotRing(4, 1);
        var first = contexts.claim(1);
        var second = contexts.claim(2);
        try (var pipelines = new MatcherPipelineGroup(2, 1, true, contexts)) {
            pipelines.submit(0, 1, () -> new com.surprising.aeron.service.matching.CoreMatchingResult(true, "ONE"));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> pipelines.submit(1, 1,
                    () -> new com.surprising.aeron.service.matching.CoreMatchingResult(true, "DUPLICATE")))
                    .isInstanceOf(IllegalStateException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> pipelines.submit(0, 2,
                    () -> new com.surprising.aeron.service.matching.CoreMatchingResult(true, "FULL")))
                    .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
            assertThat(second.submittedMatcherShard()).isEqualTo(-1);
            assertThat(await(pipelines, 1, TimeUnit.SECONDS.toNanos(5))).isNotNull();
            assertThat(first.submittedMatcherShard()).isEqualTo(-1);
            pipelines.submit(1, 1, () -> new com.surprising.aeron.service.matching.CoreMatchingResult(true, "CONTINUATION"));
            assertThat(await(pipelines, 1, TimeUnit.SECONDS.toNanos(5)).resultCode()).isEqualTo("CONTINUATION");
            contexts.discard(1);
            var reused = contexts.claim(5);
            assertThat(reused.submittedMatcherShard()).isEqualTo(-1);
            pipelines.submit(0, 5, () -> new com.surprising.aeron.service.matching.CoreMatchingResult(true, "REUSED"));
            assertThat(await(pipelines, 5, TimeUnit.SECONDS.toNanos(5)).nativeCoreSequence()).isEqualTo(5);
        }
    }

    private static MatcherPipelineGroup pipelines(int shards, int capacity, boolean start, long... sequences) {
        var contexts = new CommandSlotRing(shards * capacity, 1);
        for (long sequence : sequences) contexts.claim(sequence);
        return new MatcherPipelineGroup(shards, capacity, start, contexts);
    }

    private static CoreMatchingOrder order(long orderId, String instrumentId) {
        return new CoreMatchingOrder(orderId, instrumentId, CoreOrderSide.BUY,
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
    private static com.surprising.aeron.service.matching.CoreMatchingResult await(
            MatcherPipelineGroup pipeline, long sequence, long timeout) {
        long deadline = System.nanoTime() + timeout;
        do {
            var result = pipeline.poll(sequence);
            if (result != null) return result;
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        return null;
    }
}
