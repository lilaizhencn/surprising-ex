package com.surprising.aeron.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.client.AeronCluster;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.Test;

class SurprisingAeronClientConcurrencyTest {

    @Test
    void serializesAeronOperationsAndRejectsWorkAfterCloseWithinBounds() throws Exception {
        BlockingClusterOperations boundary = new BlockingClusterOperations();
        SurprisingAeronClient client = new SurprisingAeronClient(
                ProductLine.SPOT, Duration.ofSeconds(1), boundary);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var offer = executor.submit(() -> client.offer(message(1)));
            assertThat(boundary.offerEntered.await(1, TimeUnit.SECONDS)).isTrue();
            var close = executor.submit(client::close);
            boundary.closeEntered.await(100, TimeUnit.MILLISECONDS);
            var keepAlive = executor.submit(client::keepAlive);
            var poll = executor.submit(() -> client.pollEgress(10));

            boundary.releaseOffer.countDown();
            assertThat(offer.get(1, TimeUnit.SECONDS)).isPositive();
            close.get(1, TimeUnit.SECONDS);
            awaitCompletion(keepAlive);
            awaitCompletion(poll);

            assertThat(boundary.observedSecondVectorLength).hasValueGreaterThanOrEqualTo(0);
            assertThat(boundary.concurrentEntry).isFalse();
            assertThat(boundary.truncatedIngress).isFalse();
            assertThat(boundary.operationsAfterClose).hasValue(0);
        } finally {
            boundary.releaseOffer.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            client.close();
        }

        CountingClusterOperations throughputBoundary = new CountingClusterOperations();
        SurprisingAeronClient throughputClient = new SurprisingAeronClient(
                ProductLine.SPOT, Duration.ofSeconds(1), throughputBoundary);
        var throughputExecutor = Executors.newFixedThreadPool(8);
        long started = System.nanoTime();
        try {
            int iterations = 200;
            for (int index = 0; index < iterations; index++) {
                int correlationId = index + 10;
                throughputExecutor.submit(() -> throughputClient.offer(message(correlationId)));
                throughputExecutor.submit(throughputClient::keepAlive);
                throughputExecutor.submit(() -> throughputClient.pollEgress(10));
            }
            throughputExecutor.shutdown();
            assertThat(throughputExecutor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(elapsedMillis).isLessThan(2_000);
            assertThat(throughputBoundary.operations).hasValue(iterations * 3);
            assertThat(throughputBoundary.concurrentEntry).isFalse();
            assertThat(throughputBoundary.minimumIngressLength.get()).isGreaterThanOrEqualTo(CoreProtocol.HEADER_LENGTH);

            throughputClient.close();
            int operationsAtClose = throughputBoundary.operations.get();
            assertThatIllegalStateException().isThrownBy(() -> throughputClient.offer(message(999)));
            assertThatIllegalStateException().isThrownBy(throughputClient::keepAlive);
            assertThatIllegalStateException().isThrownBy(() -> throughputClient.pollEgress(10));
            assertThat(throughputBoundary.operations).hasValue(operationsAtClose);
        } finally {
            throughputExecutor.shutdownNow();
            throughputClient.close();
        }
    }

    @Test
    void faithfulUnserializedBoundaryReproducesNegativeEightSecondVectorLength() throws Exception {
        BlockingClusterOperations boundary = new BlockingClusterOperations();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var offer = executor.submit(() -> boundary.offer(
                    new org.agrona.concurrent.UnsafeBuffer(new byte[CoreProtocol.HEADER_LENGTH]),
                    0, CoreProtocol.HEADER_LENGTH));
            assertThat(boundary.offerEntered.await(1, TimeUnit.SECONDS)).isTrue();
            var keepAlive = executor.submit(boundary::sendKeepAlive);
            keepAlive.get(1, TimeUnit.SECONDS);
            boundary.releaseOffer.countDown();
            offer.get(1, TimeUnit.SECONDS);

            assertThat(boundary.observedSecondVectorLength).hasValue(-Long.BYTES);
            assertThat(boundary.truncatedIngress).isTrue();
        } finally {
            boundary.releaseOffer.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void awaitCompletion(java.util.concurrent.Future<?> future) throws Exception {
        try {
            future.get(1, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException expectedWhenCloseWins) {
            assertThat(expectedWhenCloseWins.getCause()).isInstanceOf(IllegalStateException.class);
        }
    }

    private static CoreMessage message(long correlationId) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                new UUID(0, correlationId), ProductLine.SPOT, CommandSource.GATEWAY,
                1, correlationId, 7, 1_000, correlationId), CoreProtocol.probePayload(1));
    }

    private static class CountingClusterOperations implements SurprisingAeronClient.ClusterOperations {
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger operations = new AtomicInteger();
        final AtomicInteger minimumIngressLength = new AtomicInteger(Integer.MAX_VALUE);
        final AtomicBoolean concurrentEntry = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public long offer(DirectBuffer buffer, int offset, int length) {
            enter();
            try {
                minimumIngressLength.accumulateAndGet(length, Math::min);
                return 1;
            } finally {
                exit();
            }
        }

        @Override
        public int pollEgress() {
            enter();
            try {
                return 0;
            } finally {
                exit();
            }
        }

        @Override
        public boolean sendKeepAlive() {
            enter();
            try {
                return true;
            } finally {
                exit();
            }
        }

        @Override
        public void close() {
            enter();
            try {
                closed.set(true);
            } finally {
                exit();
            }
        }

        void enter() {
            if (active.incrementAndGet() > 1) concurrentEntry.set(true);
            operations.incrementAndGet();
        }

        void exit() {
            active.decrementAndGet();
        }
    }

    private static final class BlockingClusterOperations extends CountingClusterOperations {
        private final CountDownLatch offerEntered = new CountDownLatch(1);
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseOffer = new CountDownLatch(1);
        private final AtomicLong encodedIngressLength = new AtomicLong();
        private final AtomicLong observedSecondVectorLength = new AtomicLong(Long.MAX_VALUE);
        private final AtomicInteger operationsAfterClose = new AtomicInteger();
        private final AtomicBoolean truncatedIngress = new AtomicBoolean();

        @Override
        public long offer(DirectBuffer buffer, int offset, int length) {
            enterBoundary();
            try {
                encodedIngressLength.set(Math.addExact(AeronCluster.SESSION_HEADER_LENGTH, length));
                offerEntered.countDown();
                if (!releaseOffer.await(1, TimeUnit.SECONDS)) throw new IllegalStateException("offer was not released");
                long secondVectorLength = encodedIngressLength.get() - AeronCluster.SESSION_HEADER_LENGTH;
                observedSecondVectorLength.set(secondVectorLength);
                if (secondVectorLength < 0) truncatedIngress.set(true);
                return 1;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", exception);
            } finally {
                exit();
            }
        }

        @Override
        public boolean sendKeepAlive() {
            enterBoundary();
            try {
                encodedIngressLength.set(AeronCluster.SESSION_HEADER_LENGTH - Long.BYTES);
                return true;
            } finally {
                exit();
            }
        }

        @Override
        public int pollEgress() {
            enterBoundary();
            try {
                return 0;
            } finally {
                exit();
            }
        }

        @Override
        public void close() {
            enterBoundary();
            try {
                closed.set(true);
                closeEntered.countDown();
            } finally {
                exit();
            }
        }

        private void enterBoundary() {
            enter();
            if (closed.get()) operationsAfterClose.incrementAndGet();
        }
    }
}
