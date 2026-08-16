package com.surprising.aeron.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AeronClientCapacityTest {

    @Test
    void usesProductionFixedCapacityDefaults() {
        assertThat(AeronClientCapacity.defaults()).isEqualTo(new AeronClientCapacity(4, 1, 256, 64, 64, 32, 32));
        assertThatIllegalArgumentException().isThrownBy(() -> new AeronClientCapacity(0, 1, 256, 64, 64, 32, 32));
        assertThatIllegalArgumentException().isThrownBy(() -> new AeronClientCapacity(4, 2, 256, 64, 64, 32, 32));
    }

    @Test
    void startsExactlyFourCommandSessionsAndOneReservedSessionOnOneDispatcher() throws Exception {
        CountDownLatch opened = new CountDownLatch(5);
        try (AeronClientPool pool = new AeronClientPool("capacity", ProductLine.SPOT,
                List.of("localhost", "localhost", "localhost"), "localhost", Duration.ofSeconds(1),
                "capacity", "epoch", AeronClientCapacity.defaults(),
                () -> {
                    opened.countDown();
                    return idleSession();
                }, true)) {
            assertThat(pool.agentThreadCount()).isEqualTo(1);
            assertThat(pool.configuredSessionCount()).isEqualTo(5);
            assertThat(opened.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void isolatesCommandAndReservedControlMailboxes() {
        AeronClientCapacity capacity = AeronClientCapacity.defaults();
        try (AeronClientPool pool = new AeronClientPool("capacity", ProductLine.SPOT,
                List.of("localhost", "localhost", "localhost"), "localhost", Duration.ofSeconds(1),
                "capacity", "epoch", capacity, () -> { throw new AssertionError("agents are paused"); }, false)) {
            for (int index = 0; index < capacity.commandMailboxCapacity(); index++) {
                assertThat(pool.commandOutcomeAsync(CoreMessageType.APPLY_MARK_PRICE, UUID.randomUUID(), 7,
                        new byte[0])).isNotCompleted();
            }
            assertBackpressured(pool.commandOutcomeAsync(CoreMessageType.APPLY_MARK_PRICE, UUID.randomUUID(), 7,
                    new byte[0]));

            for (int index = 0; index < capacity.queryMailboxCapacity(); index++) {
                assertThat(pool.controlQueryAsync(CoreMessageType.COMMAND_RESULT_QUERY, UUID.randomUUID(), 7,
                        new byte[0])).isNotCompleted();
            }
            assertThat(pool.controlQueryAsync(CoreMessageType.COMMAND_RESULT_QUERY, UUID.randomUUID(), 7,
                    new byte[0])).failsWithin(Duration.ZERO)
                    .withThrowableOfType(java.util.concurrent.ExecutionException.class)
                    .withCauseInstanceOf(CoreCommandOutcome.NotAcceptedException.class)
                    .withMessageContaining(CoreCommandOutcome.NotAcceptedReason.CLIENT_BACKPRESSURED.name());
        }
    }

    private static void assertBackpressured(java.util.concurrent.CompletableFuture<CoreCommandOutcome> outcome) {
        assertThat(outcome).isCompletedWithValueMatching(value -> value instanceof CoreCommandOutcome.NotAccepted
                && ((CoreCommandOutcome.NotAccepted) value).reason()
                == CoreCommandOutcome.NotAcceptedReason.CLIENT_BACKPRESSURED);
    }

    private static AeronClientPool.Session idleSession() {
        return new AeronClientPool.Session() {
            @Override public long offer(com.surprising.aeron.protocol.CoreMessage message) { return 1; }
            @Override public int pollEgress(int fragmentLimit) { return 0; }
            @Override public com.surprising.aeron.protocol.CoreResponse takeResponse(long correlationId) {
                return null;
            }
            @Override public RuntimeException sessionFailure() { return null; }
            @Override public boolean keepAlive() { return true; }
            @Override public void close() { }
        };
    }
}
