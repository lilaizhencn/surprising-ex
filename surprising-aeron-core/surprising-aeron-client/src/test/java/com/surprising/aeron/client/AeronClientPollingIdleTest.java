package com.surprising.aeron.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import io.aeron.Publication;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class AeronClientPollingIdleTest {
    @Test
    void waitingForResponseParksAndReusesTheResponseConsumer() throws Exception {
        var session = new WaitingSession();
        try (var pool = pool(session)) {
            var future = query(pool);
            await(() -> session.admitted != null);
            await(() -> session.drains.get() >= 5);
            awaitParked(session);
            assertThat(session.consumerChanges).hasValue(0);
            assertThat(future).isNotDone();
            session.release.set(true);
            assertThat(future.get(2, TimeUnit.SECONDS).status()).isEqualTo(ResponseStatus.OK);
        }
    }

    @Test
    void disconnectedMailboxParksButResumesWhenTheSessionConnects() throws Exception {
        var session = new WaitingSession();
        session.connected.set(false);
        try (var pool = pool(session)) {
            var future = query(pool);
            await(() -> session.polls.get() >= 5);
            awaitParked(session);
            assertThat(session.admitted).isNull();
            session.release.set(true);
            session.connected.set(true);
            assertThat(future.get(2, TimeUnit.SECONDS).status()).isEqualTo(ResponseStatus.OK);
        }
    }

    @Test
    void fullWindowParksAndDrainsQueuedRequestsAfterResponsesArrive() throws Exception {
        var session = new WaitingSession();
        try (var pool = pool(session)) {
            var first = query(pool);
            await(() -> session.admitted != null);
            var second = query(pool);
            awaitParked(session);
            assertThat(first).isNotDone();
            assertThat(second).isNotDone();
            session.release.set(true);
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void repeatedAdminActionParksWithoutLosingTheDeferredRequest() throws Exception {
        var session = new WaitingSession();
        session.adminAction.set(true);
        try (var pool = pool(session)) {
            var future = query(pool);
            await(() -> session.offers.get() >= 3);
            awaitParked(session);
            assertThat(future).isNotDone();
            session.release.set(true);
            session.adminAction.set(false);
            assertThat(future.get(2, TimeUnit.SECONDS).status()).isEqualTo(ResponseStatus.OK);
        }
    }

    private static CompletableFuture<CoreResponse> query(AeronClientPool pool) {
        return pool.queryAsync(CoreMessageType.STATE_HASH_QUERY, UUID.randomUUID(), 0, new byte[0]);
    }

    private static AeronClientPool pool(WaitingSession session) {
        return new AeronClientPool("poll-idle", ProductLine.SPOT,
                List.of("localhost", "localhost", "localhost"), "localhost", Duration.ofSeconds(10),
                "poll-idle", "epoch", new AeronClientCapacity(1, 1, 8, 8, 1, 1, 32),
                () -> session, true);
    }

    private static void awaitParked(WaitingSession session) throws Exception {
        // Observe the real dispatcher park, rather than relying on a CPU-speed threshold.
        await(() -> session.thread != null && session.thread.getState() == Thread.State.TIMED_WAITING);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(1);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static final class WaitingSession implements AeronClientPool.Session {
        final AtomicBoolean connected = new AtomicBoolean(true);
        final AtomicBoolean release = new AtomicBoolean();
        final AtomicBoolean adminAction = new AtomicBoolean();
        final AtomicInteger polls = new AtomicInteger();
        final AtomicInteger offers = new AtomicInteger();
        final AtomicInteger drains = new AtomicInteger();
        final AtomicInteger consumerChanges = new AtomicInteger();
        volatile Thread thread;
        volatile CoreMessage admitted;
        BiConsumer<Long, CoreResponse> previousConsumer;

        public long offer(CoreMessage message) {
            offers.incrementAndGet();
            if (adminAction.get()) return Publication.ADMIN_ACTION;
            admitted = message;
            return 1;
        }

        public int pollEgress(int limit) {
            thread = Thread.currentThread();
            polls.incrementAndGet();
            return 0;
        }

        public void drainResponses(BiConsumer<Long, CoreResponse> consumer) {
            if (previousConsumer != null && previousConsumer != consumer) consumerChanges.incrementAndGet();
            previousConsumer = consumer;
            drains.incrementAndGet();
            if (release.get() && admitted != null) {
                var message = admitted;
                admitted = null;
                consumer.accept(message.header().correlationId(), new CoreResponse(ResponseStatus.OK, 1));
            }
        }

        public RuntimeException sessionFailure() { return null; }
        public boolean connected() { return connected.get(); }
        public boolean keepAlive() { return true; }
        public void close() {}
    }
}
