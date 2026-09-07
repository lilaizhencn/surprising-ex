package com.surprising.aeron.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ResponseStatus;
import io.aeron.Publication;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClusterOfferRetryTest {
    @Test void retriesOnlyDefinitelyUnacceptedTransientOffers() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();
        CoreResponse terminal = new CoreResponse(ResponseStatus.APPLIED, 1, 1);
        var result = ClusterOfferRetry.submit(() -> switch (attempts.getAndIncrement()) {
            case 0 -> CompletableFuture.failedFuture(rejection(Publication.ADMIN_ACTION));
            case 1 -> CompletableFuture.failedFuture(rejection(Publication.BACK_PRESSURED));
            default -> CompletableFuture.completedFuture(terminal);
        }, retries::incrementAndGet).join();
        assertThat(result).isSameAs(terminal);
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(retries.get()).isEqualTo(2);
    }

    @Test void doesNotRetryUnknownOutcomesOrDisconnectedSessions() {
        for (RuntimeException failure : new RuntimeException[]{
                new IllegalStateException("outcome unknown"), rejection(Publication.NOT_CONNECTED)}) {
            AtomicInteger attempts = new AtomicInteger();
            var result = ClusterOfferRetry.submit(() -> {
                attempts.incrementAndGet();
                return CompletableFuture.failedFuture(failure);
            }, () -> { throw new AssertionError("must not retry"); });
            assertThatThrownBy(result::join).hasCause(failure);
            assertThat(attempts.get()).isEqualTo(1);
        }
    }

    @Test void respectsRetryDeadline() {
        var failure = rejection(Publication.ADMIN_ACTION);
        var result = ClusterOfferRetry.submit(() -> CompletableFuture.failedFuture(failure),
                () -> { throw new AssertionError("deadline expired"); }, 0);
        assertThatThrownBy(result::join).hasCause(failure);
    }

    private static CoreCommandOutcome.NotAcceptedException rejection(long code) {
        return new CoreCommandOutcome.NotAcceptedException(CoreCommandOutcome.notAccepted(code));
    }
}
