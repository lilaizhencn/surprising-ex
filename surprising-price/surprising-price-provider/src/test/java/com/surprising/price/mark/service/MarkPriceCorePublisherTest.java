package com.surprising.price.mark.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MarkPriceCorePublisherTest {

    @Test
    void keepsOnlyTheLatestEventAfterAFailedSend() throws Exception {
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicReference<MarkPriceEvent> sent = new AtomicReference<>();
        CountDownLatch firstAttempt = new CountDownLatch(1);
        CountDownLatch secondAttempt = new CountDownLatch(1);
        MarkPriceCorePublisher.Transport transport = event -> {
            if (first.compareAndSet(true, false)) {
                firstAttempt.countDown();
                return false;
            }
            sent.set(event);
            secondAttempt.countDown();
            return true;
        };

        try (MarkPriceCorePublisher publisher = new MarkPriceCorePublisher(transport)) {
            publisher.publish(event("BTC-USDT", 1));
            assertThat(firstAttempt.await(1, TimeUnit.SECONDS)).isTrue();
            publisher.publish(event("BTC-USDT", 2));
            assertThat(secondAttempt.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(sent.get().sequence()).isEqualTo(2L);
            assertThat(publisher.pendingCount()).isZero();
        }
    }

    @Test
    void doesNotImmediatelyRetryAFailedSend() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch firstAttempt = new CountDownLatch(1);
        MarkPriceCorePublisher.Transport transport = event -> {
            attempts.incrementAndGet();
            firstAttempt.countDown();
            return false;
        };

        try (MarkPriceCorePublisher publisher = new MarkPriceCorePublisher(transport)) {
            publisher.publish(event("BTC-USDT", 1));
            assertThat(firstAttempt.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(attempts).hasValue(1);
            assertThat(publisher.pendingCount()).isEqualTo(1);
        }
    }

    @Test
    void doesNotReplaceNewerEventWithAnOlderConcurrentPublication() throws Exception {
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        AtomicReference<MarkPriceEvent> sent = new AtomicReference<>();
        CountDownLatch sendFinished = new CountDownLatch(1);
        MarkPriceCorePublisher.Transport transport = event -> {
            sendStarted.countDown();
            try {
                assertThat(releaseSend.await(1, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
            sent.set(event);
            sendFinished.countDown();
            return true;
        };

        try (MarkPriceCorePublisher publisher = new MarkPriceCorePublisher(transport)) {
            publisher.publish(event("BTC-USDT", 2));
            assertThat(sendStarted.await(1, TimeUnit.SECONDS)).isTrue();
            publisher.publish(event("BTC-USDT", 1));
            releaseSend.countDown();
            assertThat(sendFinished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(sent.get().sequence()).isEqualTo(2L);
        }
    }

    @Test
    void closesTransportAndRejectsPublicationAfterClose() {
        AtomicBoolean transportClosed = new AtomicBoolean();
        AtomicInteger attempts = new AtomicInteger();
        MarkPriceCorePublisher.Transport transport = new MarkPriceCorePublisher.Transport() {
            @Override
            public boolean trySend(MarkPriceEvent event) {
                attempts.incrementAndGet();
                return true;
            }

            @Override
            public void close() {
                transportClosed.set(true);
            }
        };

        MarkPriceCorePublisher publisher = new MarkPriceCorePublisher(transport);
        publisher.close();
        publisher.publish(event("BTC-USDT", 1));

        assertThat(transportClosed).isTrue();
        assertThat(attempts).hasValue(0);
        assertThat(publisher.pendingCount()).isZero();
    }

    @Test
    void usesBoundedQueueForDrainTasks() throws Exception {
        try (MarkPriceCorePublisher publisher = new MarkPriceCorePublisher(event -> true)) {
            var field = MarkPriceCorePublisher.class.getDeclaredField("executor");
            field.setAccessible(true);
            var executor = (ThreadPoolExecutor) field.get(publisher);
            assertThat(executor.getQueue()).isInstanceOf(ArrayBlockingQueue.class);
            assertThat(executor.getQueue().remainingCapacity()).isEqualTo(1);
        }
    }

    private static MarkPriceEvent event(String symbol, long sequence) {
        Instant now = Instant.now();
        return new MarkPriceEvent(ProductLine.LINEAR_PERPETUAL, symbol, 1L, 100_000_000L, 100L,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), null, null, null, null, null, null,
                null, 0L, null, 0L, null, null, sequence, PriceStatus.HEALTHY, now, now);
    }
}
