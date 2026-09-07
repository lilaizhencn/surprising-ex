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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AeronClientAgentTest {

    @Test
    void adminActionRetriesKeepIdentityAndFifoWhilePollingEgress() throws Exception {
        var adminSeen=new CountDownLatch(1);var polled=new CountDownLatch(3);
        var allow=new AtomicBoolean();var mismatch=new AtomicBoolean();
        var first=new AtomicReference<CoreMessage>();
        var accepted=new java.util.concurrent.CopyOnWriteArrayList<CoreMessage>();
        var responses=ConcurrentHashMap.<Long>newKeySet();
        try(var pool=pool(Duration.ofSeconds(5),()->new AeronClientPool.Session() {
            public long offer(CoreMessage message) {
                first.compareAndSet(null,message);
                if(!allow.get()) {
                    if(message!=first.get())mismatch.set(true);
                    adminSeen.countDown();return Publication.ADMIN_ACTION;
                }
                accepted.add(message);responses.add(message.header().correlationId());return 1;
            }
            public int pollEgress(int limit) { if(adminSeen.getCount()==0)polled.countDown();return 0; }
            public CoreResponse takeResponse(long id) { return responses.remove(id)?new CoreResponse(ResponseStatus.APPLIED,1,1):null; }
            public RuntimeException sessionFailure(){return null;}
            public boolean keepAlive(){return true;}
            public void close(){}
        })) {
            var a=pool.commandAsync(CoreMessageType.PLACE_ORDER,UUID.randomUUID(),1,new byte[0]);
            assertThat(adminSeen.await(2,TimeUnit.SECONDS)).isTrue();
            var b=pool.commandAsync(CoreMessageType.CANCEL_ORDER,UUID.randomUUID(),2,new byte[0]);
            assertThat(polled.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(a).isNotDone();assertThat(b).isNotDone();
            allow.set(true);a.get(2,TimeUnit.SECONDS);b.get(2,TimeUnit.SECONDS);
            assertThat(mismatch).isFalse();assertThat(accepted).hasSize(2);
            assertThat(accepted.getFirst()).isSameAs(first.get());
            assertThat(accepted.get(1).header().sourceSequence()).isEqualTo(first.get().header().sourceSequence()+1);
            assertThat(pool.adminActionRetries()).isPositive();
        }
    }

    @Test
    void persistentAdminActionExpiresAsNotAcceptedAndCloseReleasesDeferredRequest() throws Exception {
        try(var pool=pool(Duration.ofMillis(50),()->session(m->Publication.ADMIN_ACTION))) {
            var result=pool.commandOutcomeAsync(CoreMessageType.PLACE_ORDER,UUID.randomUUID(),1,new byte[0]).get(2,TimeUnit.SECONDS);
            assertThat(result).isInstanceOf(CoreCommandOutcome.NotAccepted.class);
        }
        var seen=new CountDownLatch(1);
        var pool=pool(Duration.ofSeconds(5),()->session(m->{seen.countDown();return Publication.ADMIN_ACTION;}));
        var result=pool.commandOutcomeAsync(CoreMessageType.PLACE_ORDER,UUID.randomUUID(),1,new byte[0]);
        assertThat(seen.await(2,TimeUnit.SECONDS)).isTrue();pool.close();
        assertThat(result.get(2,TimeUnit.SECONDS)).isInstanceOf(CoreCommandOutcome.NotAccepted.class);
    }

    @Test
    void tryOnceStillReturnsAdminActionWithoutRetrying() {
        try(var pool=pool(Duration.ofSeconds(1),()->session(m->Publication.ADMIN_ACTION))) {
            assertThat(pool.tryCommandOnce(CoreMessageType.PLACE_ORDER,UUID.randomUUID(),1,new byte[0]))
                    .isEqualTo(AeronClientPool.TryCommandResult.UNAVAILABLE);
            assertThat(pool.adminActionRetries()).isZero();
        }
    }

    @Test
    void sourceSequenceFollowsOfferOrderWhenProducersEnqueueInReverseCreationOrder() throws Exception {
        var sent = new java.util.concurrent.CopyOnWriteArrayList<CoreMessage>();
        var firstOffered = new CountDownLatch(1);
        var bothOffered = new CountDownLatch(2);
        try (var pool = pool(Duration.ofSeconds(2), () -> session(message -> {
            sent.add(message);
            firstOffered.countDown();
            bothOffered.countDown();
            return 1;
        }))) {
            // Deterministically model producer A being descheduled after claiming its Request slot.
            var lanes = AeronClientPool.class.getDeclaredField("commandAgents");
            lanes.setAccessible(true);
            Object lane = ((Object[]) lanes.get(pool))[0];
            var create = lane.getClass().getDeclaredMethod("oneWayFutureRequest", CoreMessageType.class,
                    UUID.class, long.class, byte[].class);
            create.setAccessible(true);
            byte[] firstPayload = {1};
            Object first = create.invoke(lane, CoreMessageType.APPLY_MARK_PRICE, UUID.randomUUID(), 1L, firstPayload);
            Object second = create.invoke(lane, CoreMessageType.APPLY_MARK_PRICE, UUID.randomUUID(), 1L, new byte[]{2});
            firstPayload[0] = 9;
            var enqueue = lane.getClass().getDeclaredMethod("enqueue", first.getClass());
            enqueue.setAccessible(true);
            assertThat(enqueue.invoke(lane, second)).isEqualTo(true);
            assertThat(firstOffered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(enqueue.invoke(lane, first)).isEqualTo(true);
            assertThat(bothOffered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(sent).extracting(m -> m.header().sourceSequence()).containsExactly(1L, 2L);
            assertThat(sent.get(0).payload()).containsExactly((byte) 2);
            assertThat(sent.get(1).payload()).containsExactly((byte) 1);
        }
    }

    @Test
    void tryCommandOnceReflectsTheSinglePublicationOffer() {
        AtomicInteger offers = new AtomicInteger();
        try (AeronClientPool pool = pool(Duration.ofSeconds(1), () -> session(message -> {
            offers.incrementAndGet();
            return Publication.NOT_CONNECTED;
        }))) {
            assertThat(pool.tryCommandOnce(CoreMessageType.APPLY_MARK_PRICE, UUID.randomUUID(), 9, new byte[0]))
                    .isEqualTo(AeronClientPool.TryCommandResult.NOT_READY);
            assertThat(offers).hasValue(1);
        }
    }

    @Test
    void oneWayBatchUsesCallbacksWithoutWaitingForCommandResponses() throws Exception {
        AtomicInteger offers = new AtomicInteger();
        CountDownLatch callbacks = new CountDownLatch(3);
        Set<UUID> completed = ConcurrentHashMap.newKeySet();
        UUID[] commandIds = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        long[] userIds = {1, 2, 3};
        byte[][] payloads = {new byte[0], new byte[0], new byte[0]};
        try (AeronClientPool pool = pool(Duration.ofSeconds(1), () -> session(message -> {
            offers.incrementAndGet();
            return 1;
        }))) {
            int accepted = pool.commandBatchOneWay(CoreMessageType.APPLY_MARK_PRICE, commandIds, userIds, payloads,
                    0, commandIds.length, (commandId, result) -> {
                        assertThat(result).isEqualTo(AeronClientPool.TryCommandResult.SENT);
                        completed.add(commandId);
                        callbacks.countDown();
                    });

            assertThat(accepted).isEqualTo(3);
            assertThat(callbacks.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(completed).containsExactlyInAnyOrder(commandIds);
            assertThat(offers).hasValue(3);
        }
    }

    @Test
    void oneDispatcherOwnsEgressForEveryFixedSession() throws Exception {
        CountDownLatch polled = new CountDownLatch(2);
        Set<String> owners = ConcurrentHashMap.newKeySet();
        Set<Integer> fragmentLimits = ConcurrentHashMap.newKeySet();
        AeronClientPool.Session session = new AeronClientPool.Session() {
            @Override public long offer(CoreMessage message) { return Publication.NOT_CONNECTED; }
            @Override public int pollEgress(int fragmentLimit) {
                owners.add(Thread.currentThread().getName());
                fragmentLimits.add(fragmentLimit);
                polled.countDown();
                return 0;
            }
            @Override public CoreResponse takeResponse(long correlationId) { return null; }
            @Override public RuntimeException sessionFailure() { return null; }
            @Override public boolean keepAlive() { return true; }
            @Override public void close() { }
        };

        try (AeronClientPool pool = pool(Duration.ofSeconds(1), () -> session)) {
            assertThat(polled.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(owners).containsExactly("agent-egress-dispatcher");
            assertThat(fragmentLimits).containsExactly(32);
            assertThat(pool.agentThreadCount()).isEqualTo(1);
            assertThat(pool.configuredSessionCount()).isEqualTo(2);
        }
    }

    @Test
    void productionEgressAdapterNeverExceedsTheConfiguredFragmentLimit() {
        AtomicInteger polls = new AtomicInteger();
        int fragments = SurprisingAeronClient.pollEgressBounded(32, () -> {
            polls.incrementAndGet();
            return 10;
        });

        assertThat(fragments).isEqualTo(30);
        assertThat(polls).hasValue(3);
    }

    @Test
    void returnsUnknownAfterPositiveOfferTimeoutWithoutRetry() throws Exception {
        AtomicInteger offers = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AeronClientPool.Session session = new AeronClientPool.Session() {
            @Override public long offer(CoreMessage message) {
                offers.incrementAndGet();
                return 1;
            }
            @Override public int pollEgress(int fragmentLimit) { return 0; }
            @Override public CoreResponse takeResponse(long correlationId) { return null; }
            @Override public RuntimeException sessionFailure() { return null; }
            @Override public boolean keepAlive() { return true; }
            @Override public void close() { closes.incrementAndGet(); }
        };
        UUID commandId = UUID.randomUUID();

        try (AeronClientPool pool = pool(Duration.ofMillis(20), () -> session)) {
            CoreCommandOutcome outcome = pool.commandOutcomeAsync(CoreMessageType.APPLY_MARK_PRICE, commandId, 9,
                    new byte[0]).get(2, TimeUnit.SECONDS);

            assertThat(outcome).isEqualTo(new CoreCommandOutcome.ResultUnknown(commandId));
            assertThat(offers).hasValue(1);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (closes.get() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(closes.get()).isPositive();
        }
    }

    @Test
    void returnsTypedNegativeAdmissionWithoutRetry() throws Exception {
        AtomicInteger offers = new AtomicInteger();
        long[] rawResults = {Publication.BACK_PRESSURED, Publication.NOT_CONNECTED,
                Publication.CLOSED, Publication.MAX_POSITION_EXCEEDED, -99};
        CoreCommandOutcome.NotAcceptedReason[] reasons = {
                CoreCommandOutcome.NotAcceptedReason.CLIENT_BACKPRESSURED,
                CoreCommandOutcome.NotAcceptedReason.NOT_CONNECTED,
                CoreCommandOutcome.NotAcceptedReason.CLOSED,
                CoreCommandOutcome.NotAcceptedReason.MAX_POSITION_EXCEEDED,
                CoreCommandOutcome.NotAcceptedReason.UNKNOWN};

        for (int index = 0; index < rawResults.length; index++) {
            long rawResult = rawResults[index];
            CoreCommandOutcome.NotAcceptedReason reason = reasons[index];
            try (AeronClientPool pool = pool(Duration.ofSeconds(1), () -> session(message -> {
                offers.incrementAndGet();
                return rawResult;
            }))) {
                CoreCommandOutcome outcome = pool.commandOutcomeAsync(CoreMessageType.APPLY_MARK_PRICE,
                        UUID.randomUUID(), 9, new byte[0]).get(2, TimeUnit.SECONDS);

                assertThat(outcome).isEqualTo(new CoreCommandOutcome.NotAccepted(reason, rawResult));
            }
        }
        assertThat(offers).hasValue(rawResults.length);
    }

    @Test
    void preparedInfrastructureMessageRetainsItsStableHeader() {
        AtomicReference<CoreMessage> offered = new AtomicReference<>();
        try (AeronClientPool pool = pool(Duration.ofSeconds(1), () -> session(message -> {
            offered.set(message);
            return Publication.NOT_CONNECTED;
        }))) {
            CoreMessage message = new CoreMessage(
                    com.surprising.aeron.protocol.CoreMessageHeader.command(
                            CoreMessageType.ACK_EXPORT, UUID.randomUUID(), ProductLine.SPOT,
                            com.surprising.aeron.protocol.CommandSource.OPERATIONS,
                            0x4558504f52544552L, 17, 0, 1, 19),
                    com.surprising.aeron.protocol.CoreExportCodec.encodeAck(
                            new com.surprising.aeron.protocol.AckExportCommand(17)));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> pool.submitPrepared(message))
                    .isInstanceOf(CoreCommandOutcome.NotAcceptedException.class);

            assertThat(offered.get()).isEqualTo(message);
        }
    }

    @Test
    void reopensFixedSessionsAfterAeronFailure() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(2);
        AeronClientPool.SessionFactory factory = () -> {
            int index = opened.incrementAndGet();
            if (index <= 2) {
                return new AeronClientPool.Session() {
                    @Override public long offer(CoreMessage message) { return Publication.NOT_CONNECTED; }
                    @Override public int pollEgress(int fragmentLimit) { return 0; }
                    @Override public CoreResponse takeResponse(long correlationId) { return null; }
                    @Override public RuntimeException sessionFailure() {
                        return new IllegalStateException("session failed");
                    }
                    @Override public boolean keepAlive() { return true; }
                    @Override public void close() { }
                };
            }
            return new AeronClientPool.Session() {
                @Override public long offer(CoreMessage message) { return Publication.NOT_CONNECTED; }
                @Override public int pollEgress(int fragmentLimit) {
                    recovered.countDown();
                    return 0;
                }
                @Override public CoreResponse takeResponse(long correlationId) { return null; }
                @Override public RuntimeException sessionFailure() { return null; }
                @Override public boolean keepAlive() { return true; }
                @Override public void close() { }
            };
        };

        try (AeronClientPool pool = pool(Duration.ofSeconds(1), factory)) {
            assertThat(recovered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(opened.get()).isGreaterThanOrEqualTo(4);
        }
    }

    @Test
    void boundsQueuedQueryWhenSessionsCannotOpen() {
        try (AeronClientPool pool = pool(Duration.ofMillis(50), () -> {
            throw new IllegalStateException("cluster unavailable");
        })) {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> pool.query(
                                    CoreMessageType.USER_STATE_QUERY, UUID.randomUUID(), 9, new byte[0]))
                            .isInstanceOf(java.util.concurrent.CompletionException.class)
                            .cause()
                            .isInstanceOfSatisfying(CoreCommandOutcome.NotAcceptedException.class,
                                    exception -> assertThat(exception.rejection().reason())
                                            .isEqualTo(CoreCommandOutcome.NotAcceptedReason.NOT_CONNECTED)));
        }
    }

    @Test
    void waitsForAsyncSessionConnectionBeforeOfferingQueuedRequest() throws Exception {
        AtomicBoolean connected = new AtomicBoolean();
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger offers = new AtomicInteger();
        AeronClientPool.Session session = new AeronClientPool.Session() {
            @Override public long offer(CoreMessage message) {
                if (!connected.get()) {
                    throw new AssertionError("request offered before async session connected");
                }
                offers.incrementAndGet();
                return Publication.BACK_PRESSURED;
            }
            @Override public int pollEgress(int fragmentLimit) {
                if (polls.incrementAndGet() >= 5) {
                    connected.set(true);
                }
                return 0;
            }
            @Override public CoreResponse takeResponse(long correlationId) { return null; }
            @Override public RuntimeException sessionFailure() { return null; }
            @Override public boolean connected() { return connected.get(); }
            @Override public boolean keepAlive() { return connected.get(); }
            @Override public void close() { }
        };

        try (AeronClientPool pool = pool(Duration.ofSeconds(1), () -> session)) {
            CoreCommandOutcome outcome = pool.commandOutcomeAsync(CoreMessageType.APPLY_MARK_PRICE,
                    UUID.randomUUID(), 9, new byte[0]).get(2, TimeUnit.SECONDS);

            assertThat(outcome).isEqualTo(CoreCommandOutcome.notAccepted(Publication.BACK_PRESSURED));
            assertThat(polls.get()).isGreaterThanOrEqualTo(5);
            assertThat(offers).hasValue(1);
        }
    }

    @Test
    void reopensSessionWhenPublicationIsNotConnected() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        CountDownLatch secondSession = new CountDownLatch(1);
        AeronClientPool.SessionFactory factory = () -> {
            int index = opened.incrementAndGet();
            if (index == 1) {
                return session(message -> Publication.NOT_CONNECTED);
            }
            if (index >= 3) {
                secondSession.countDown();
            }
            return session(message -> Publication.NOT_CONNECTED);
        };

        try (AeronClientPool pool = pool(Duration.ofSeconds(1), factory)) {
            pool.commandOutcomeAsync(CoreMessageType.APPLY_MARK_PRICE, UUID.randomUUID(), 9, new byte[0])
                    .get(2, TimeUnit.SECONDS);
            assertThat(secondSession.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(opened.get()).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void retriesQueryWhenTheLeaderFailoverClosesItsPublication() {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger offers = new AtomicInteger();
        AeronClientPool.SessionFactory factory = () -> {
            if (opened.incrementAndGet() <= 2) {
                return session(message -> {
                    offers.incrementAndGet();
                    return Publication.CLOSED;
                });
            }
            AtomicReference<Long> correlation = new AtomicReference<>();
            return new AeronClientPool.Session() {
                @Override public long offer(CoreMessage message) {
                    offers.incrementAndGet();
                    correlation.set(message.header().correlationId());
                    return 1;
                }
                @Override public int pollEgress(int fragmentLimit) {
                    return correlation.get() == null ? 0 : 1;
                }
                @Override public CoreResponse takeResponse(long correlationId) {
                    Long offeredCorrelation = correlation.getAndSet(null);
                    return offeredCorrelation != null && offeredCorrelation == correlationId
                            ? new CoreResponse(ResponseStatus.OK, 42, 99)
                            : null;
                }
                @Override public RuntimeException sessionFailure() { return null; }
                @Override public boolean keepAlive() { return true; }
                @Override public void close() { }
            };
        };

        try (AeronClientPool pool = pool(Duration.ofSeconds(1), factory)) {
            CoreResponse response = pool.query(CoreMessageType.USER_STATE_QUERY, UUID.randomUUID(), 9, new byte[0]);

            assertThat(response.appliedCommandCount()).isEqualTo(42);
            assertThat(offers).hasValue(2);
        }
    }

    @Test
    void keepsEveryFixedSessionAliveWhileIdle() throws Exception {
        CountDownLatch keepAlives = new CountDownLatch(2);
        AeronClientPool.SessionFactory factory = () -> new AeronClientPool.Session() {
            @Override public long offer(CoreMessage message) { return Publication.NOT_CONNECTED; }
            @Override public int pollEgress(int fragmentLimit) { return 0; }
            @Override public CoreResponse takeResponse(long correlationId) { return null; }
            @Override public RuntimeException sessionFailure() { return null; }
            @Override public boolean keepAlive() {
                keepAlives.countDown();
                return true;
            }
            @Override public void close() { }
        };

        try (AeronClientPool pool = pool(Duration.ofSeconds(1), factory)) {
            assertThat(keepAlives.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void dispatcherFailureRejectsNewRequestsInsteadOfLeavingThemQueued() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger opened = new AtomicInteger();
        RuntimeException fatal = new IllegalStateException("fatal dispatcher failure");
        AeronClientPool.SessionFactory factory = () -> {
            if (opened.incrementAndGet() != 1) {
                return session(message -> Publication.NOT_CONNECTED);
            }
            return new AeronClientPool.Session() {
                @Override public long offer(CoreMessage message) { return Publication.NOT_CONNECTED; }
                @Override public int pollEgress(int fragmentLimit) { throw new IllegalStateException("poll failed"); }
                @Override public CoreResponse takeResponse(long correlationId) { return null; }
                @Override public RuntimeException sessionFailure() { return null; }
                @Override public boolean keepAlive() { return true; }
                @Override public void close() {
                    failed.countDown();
                    throw fatal;
                }
            };
        };
        AeronClientPool pool = pool(Duration.ofSeconds(1), factory);

        assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!pool.dispatcherStopped() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(pool.dispatcherStopped()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> pool.submitPrepared(preparedQuery(31)))
                .isSameAs(fatal);
        org.assertj.core.api.Assertions.assertThatThrownBy(pool::close).isSameAs(fatal);
    }

    @Test
    void rejectsDuplicatePreparedCorrelationWithoutOverwritingPendingRequest() throws Exception {
        CountDownLatch offered = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        try (AeronClientPool pool = pool(Duration.ofSeconds(5), () -> session(message -> {
            offered.countDown();
            return 1;
        }))) {
            Thread first = Thread.ofPlatform().start(() -> {
                try {
                    pool.submitPrepared(preparedQuery(41));
                } catch (Throwable throwable) {
                    firstFailure.set(throwable);
                }
            });
            assertThat(offered.await(2, TimeUnit.SECONDS)).isTrue();

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> pool.submitPrepared(preparedQuery(41)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("duplicate in-flight Aeron correlationId=41");

            pool.close();
            first.join(2_000L);
            assertThat(first.isAlive()).isFalse();
            assertThat(firstFailure.get()).isInstanceOf(ResultUnknownException.class);
        }
    }

    @Test
    void preparedWaitPreservesInterruptionAndStopsWaiting() throws Exception {
        CountDownLatch offered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        try (AeronClientPool pool = pool(Duration.ofSeconds(5), () -> session(message -> {
            offered.countDown();
            return 1;
        }))) {
            Thread caller = Thread.ofPlatform().start(() -> {
                try {
                    pool.submitPrepared(preparedQuery(51));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            assertThat(offered.await(2, TimeUnit.SECONDS)).isTrue();

            caller.interrupt();
            caller.join(2_000L);

            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(ResultUnknownException.class);
            assertThat(interrupted).isTrue();
        }
    }

    @Test
    void interruptedCloseStillStopsDispatcherBeforeReturning() throws Exception {
        CountDownLatch sessionsOpened = new CountDownLatch(2);
        CountDownLatch sessionsClosed = new CountDownLatch(2);
        AeronClientPool pool = pool(Duration.ofSeconds(1), () -> {
            sessionsOpened.countDown();
            return new AeronClientPool.Session() {
                @Override public long offer(CoreMessage message) { return Publication.NOT_CONNECTED; }
                @Override public int pollEgress(int fragmentLimit) { return 0; }
                @Override public CoreResponse takeResponse(long correlationId) { return null; }
                @Override public RuntimeException sessionFailure() { return null; }
                @Override public boolean keepAlive() { return true; }
                @Override public void close() { sessionsClosed.countDown(); }
            };
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        assertThat(sessionsOpened.await(2, TimeUnit.SECONDS)).isTrue();

        Thread closer = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt();
            try {
                pool.close();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        });
        closer.join(2_000L);

        assertThat(closer.isAlive()).isFalse();
        assertThat(pool.dispatcherStopped()).isTrue();
        assertThat(sessionsClosed.getCount()).isZero();
        assertThat(interruptPreserved).isTrue();
        assertThat(failure.get()).isInstanceOf(IllegalStateException.class)
                .hasMessage("Interrupted while stopping Aeron dispatcher");
    }

    private static CoreMessage preparedQuery(long correlationId) {
        return new CoreMessage(
                com.surprising.aeron.protocol.CoreMessageHeader.query(
                        CoreMessageType.EXPORT_STATUS_QUERY, UUID.randomUUID(), ProductLine.SPOT,
                        com.surprising.aeron.protocol.CommandSource.OPERATIONS,
                        0x4558504f52544552L, 0, 0, 1, correlationId),
                new byte[0]);
    }

    private static AeronClientPool pool(Duration timeout, AeronClientPool.SessionFactory sessions) {
        return new AeronClientPool("agent", ProductLine.SPOT, List.of("localhost", "localhost", "localhost"),
                "localhost", timeout, "agent", "epoch", new AeronClientCapacity(1, 1, 8, 8, 4, 4, 32),
                sessions, true);
    }

    private static AeronClientPool.Session session(java.util.function.ToLongFunction<CoreMessage> offer) {
        return new AeronClientPool.Session() {
            @Override public long offer(CoreMessage message) { return offer.applyAsLong(message); }
            @Override public int pollEgress(int fragmentLimit) { return 0; }
            @Override public CoreResponse takeResponse(long correlationId) { return null; }
            @Override public RuntimeException sessionFailure() { return null; }
            @Override public boolean keepAlive() { return true; }
            @Override public void close() { }
        };
    }
}
