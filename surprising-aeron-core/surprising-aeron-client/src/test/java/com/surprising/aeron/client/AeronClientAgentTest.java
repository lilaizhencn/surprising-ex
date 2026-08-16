package com.surprising.aeron.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.product.api.ProductLine;
import io.aeron.Publication;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AeronClientAgentTest {

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
        try (AeronClientPool pool = pool(Duration.ofSeconds(1), () -> session(message -> {
            offers.incrementAndGet();
            return Publication.ADMIN_ACTION;
        }))) {
            CoreCommandOutcome outcome = pool.commandOutcomeAsync(CoreMessageType.APPLY_MARK_PRICE,
                    UUID.randomUUID(), 9, new byte[0]).get(2, TimeUnit.SECONDS);

            assertThat(outcome).isInstanceOfSatisfying(CoreCommandOutcome.NotAccepted.class,
                    value -> assertThat(value.reason()).isEqualTo(CoreCommandOutcome.NotAcceptedReason.ADMIN_ACTION));
            assertThat(offers).hasValue(1);
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
