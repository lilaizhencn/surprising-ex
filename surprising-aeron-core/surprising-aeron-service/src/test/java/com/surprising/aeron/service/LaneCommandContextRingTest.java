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
        long generation = context.beginMatchingSubmission();
        CoreMatchingResult first = new CoreMatchingResult(true, "SUCCESS").withCoreSequence(3);
        CoreMatchingResult duplicate = new CoreMatchingResult(false, "LATE").withCoreSequence(3);

        context.publishMatchingCompletion(first);
        context.publishMatchingCompletion(duplicate);

        assertThat(context.acceptsMatchingSubmission(generation)).isTrue();
        assertThat(context.takeMatchingCompletion()).isSameAs(first);
        assertThat(context.takeMatchingCompletion()).isNull();
        context.resetMatchingContinuation();
        assertThat(context.acceptsMatchingSubmission(generation)).isFalse();
        ring.discard(3);
    }

}
