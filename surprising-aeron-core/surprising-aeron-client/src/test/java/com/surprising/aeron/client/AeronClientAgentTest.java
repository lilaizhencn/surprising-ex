package com.surprising.aeron.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.product.api.ProductLine;
import io.aeron.Publication;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AeronClientAgentTest {

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
        AeronClientPool.Session session = session(message -> {
            offers.incrementAndGet();
            return 1;
        });
        UUID commandId = UUID.randomUUID();

        try (AeronClientPool pool = pool(Duration.ofMillis(20), () -> session)) {
            CoreCommandOutcome outcome = pool.commandOutcomeAsync(CoreMessageType.APPLY_MARK_PRICE, commandId, 9,
                    new byte[0]).get(2, TimeUnit.SECONDS);

            assertThat(outcome).isEqualTo(new CoreCommandOutcome.ResultUnknown(commandId));
            assertThat(offers).hasValue(1);
        }
    }

    @Test
    void returnsTypedNegativeAdmissionWithoutRetry() throws Exception {
        AtomicInteger offers = new AtomicInteger();
        long[] rawResults = {Publication.BACK_PRESSURED, Publication.NOT_CONNECTED, Publication.ADMIN_ACTION,
                Publication.CLOSED, Publication.MAX_POSITION_EXCEEDED, -99};
        CoreCommandOutcome.NotAcceptedReason[] reasons = {
                CoreCommandOutcome.NotAcceptedReason.CLIENT_BACKPRESSURED,
                CoreCommandOutcome.NotAcceptedReason.NOT_CONNECTED,
                CoreCommandOutcome.NotAcceptedReason.ADMIN_ACTION,
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
                @Override public void close() { }
            };
        };

        try (AeronClientPool pool = pool(Duration.ofSeconds(1), factory)) {
            assertThat(recovered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(opened.get()).isGreaterThanOrEqualTo(4);
        }
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
            @Override public void close() { }
        };
    }
}
