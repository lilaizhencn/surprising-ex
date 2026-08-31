package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.state.AccountLaneView;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LaneCommandContextRingTest {

    @Test
    void commandContextDoesNotRetainAPerCommandMatchingFuture() {
        assertThat(Arrays.stream(LaneCommandContextRing.Context.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == java.util.concurrent.CompletableFuture.class)).isFalse();
    }

    @Test
    void aggregatesExactlyOneAckPerExpectedLaneAndReleasesTheResultReference() {
        LaneCommandContextRing ring = new LaneCommandContextRing(4, 4);
        LaneCommandContextRing.Context context = ring.claim(1);
        CoreMatchingResult result = new CoreMatchingResult(true, "ACCEPTED").withCoreSequence(1);
        context.result(result, 0b101, 0b1111);

        context.completeLane(0, 1, 1, 1);
        assertThat(context.complete()).isFalse();
        context.completeLane(2, 1, 1, 1);

        assertThat(context.complete()).isTrue();
        assertThat(context.completedLaneMask()).isEqualTo(0b101);
        assertThat(context.matchingResult()).isSameAs(result);
        context.validate(new AccountLaneView(0, 1, 1, 0, 1, 1,
                1, 0, 4, 0, "account-lane-0"));
        assertThatThrownBy(() -> context.validate(new AccountLaneView(2, 2, 1, 0, 1, 1,
                1, 0, 4, 0, "account-lane-2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revision or hash mismatch");
        ring.release(1);
        assertThat(ring.inFlight()).isZero();
        assertThat(ring.highWaterMark()).isEqualTo(1);
    }

    @Test
    void failsClosedForDuplicateUnexpectedOrOutOfRangeAck() {
        LaneCommandContextRing.Context context = new LaneCommandContextRing(4, 4).claim(1);
        CoreMatchingResult result = new CoreMatchingResult(true, "ACCEPTED").withCoreSequence(1);
        context.result(result, 0b11, 0b1111);
        context.completeLane(0, 1, 1, 1);

        assertThatThrownBy(() -> context.completeLane(0, 1, 1, 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> context.completeLane(2, 1, 1, 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unexpected");
        assertThatThrownBy(() -> context.completeLane(4, 1, 1, 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid");
    }

    @Test
    void storesMatchingContinuationWithoutPerCommandMapsAndKeepsTheFirstCompletion() {
        LaneCommandContextRing ring = new LaneCommandContextRing(4, 4);
        LaneCommandContextRing.Context context = ring.claim(3);
        LaneCommandContextRing.SubmissionToken token = ring.beginMatchingSubmission(3);
        CoreMatchingResult first = new CoreMatchingResult(true, "SUCCESS").withCoreSequence(3);
        CoreMatchingResult duplicate = new CoreMatchingResult(false, "LATE").withCoreSequence(3);

        context.publishMatchingCompletion(first);
        context.publishMatchingCompletion(duplicate);

        assertThat(context.acceptsMatchingSubmission(token)).isTrue();
        assertThat(context.takeMatchingCompletion()).isSameAs(first);
        assertThat(context.takeMatchingCompletion()).isNull();
        context.resetMatchingContinuation();
        assertThat(context.acceptsMatchingSubmission(token)).isFalse();
        ring.discard(3);
    }

    @Test
    void lateForeignCompletionCannotOccupyAReclaimedRingSlot() throws Exception {
        LaneCommandContextRing ring = new LaneCommandContextRing(2, 2);
        MatchingCompletionQueue completions = new MatchingCompletionQueue(2);
        LaneCommandContextRing.Context oldContext = ring.claim(1);
        LaneCommandContextRing.SubmissionToken oldToken = ring.beginMatchingSubmission(1);
        java.util.concurrent.CompletableFuture<CoreMatchingResult> oldFuture =
                new java.util.concurrent.CompletableFuture<>();
        CoreProbeState.registerMatchingCallback(oldContext, oldToken, completions, oldFuture);

        ring.discard(1);
        assertThat(completions.poll(1)).isNull();
        LaneCommandContextRing.Context newContext = ring.claim(3);
        LaneCommandContextRing.SubmissionToken newToken = ring.beginMatchingSubmission(3);
        assertThat(newContext).isSameAs(oldContext);
        assertThat(newToken.tokenId()).isGreaterThan(oldToken.tokenId());
        assertThat(oldToken.coreSequence()).isEqualTo(1);
        assertThat(newToken.coreSequence()).isEqualTo(3);

        java.util.concurrent.atomic.AtomicReference<Throwable> callbackFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread matcherThread = new Thread(
                () -> oldFuture.complete(new CoreMatchingResult(true, "LATE_OLD").withCoreSequence(1)),
                "late-matcher-completion");
        matcherThread.setUncaughtExceptionHandler((thread, failure) -> callbackFailure.set(failure));
        matcherThread.start();
        matcherThread.join();

        assertThat(completions.inFlightSubmissions()).isZero();
        assertThat(completions.depth()).isZero();
        assertThat(completions.highWaterMark()).isZero();
        assertThat(completions.available(1)).isFalse();
        assertThat(completions.available(3)).isFalse();
        assertThat(completions.consumeOverflow()).isFalse();
        assertThat(callbackFailure.get()).isNull();
        assertThat(newContext.acceptsMatchingSubmission(oldToken)).isFalse();
        assertThat(newContext.acceptsMatchingSubmission(newToken)).isTrue();
        assertThat(newContext.hasMatchingCompletion()).isFalse();
        ring.discard(3);
        assertThat(ring.inFlight()).isZero();
    }

}
