package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import org.junit.jupiter.api.Test;

class LaneCommandContextRingTest {

    @Test
    void aggregatesExactlyOneAckPerExpectedLaneAndReleasesTheResultReference() {
        LaneCommandContextRing ring = new LaneCommandContextRing(4);
        LaneCommandContextRing.Context context = ring.claim(1);
        CoreMatchingResult result = new CoreMatchingResult(true, "ACCEPTED").withCoreSequence(1);
        context.result(result, 0b101, 0b1111);

        context.acknowledge(ack(1, 0, 3, 5));
        assertThat(context.complete()).isFalse();
        context.acknowledge(ack(1, 2, 7, 11));

        assertThat(context.complete()).isTrue();
        assertThat(context.ackLaneMask()).isEqualTo(0b101);
        assertThat(context.feeUnits()).isEqualTo(10);
        assertThat(context.insuranceUnits()).isEqualTo(16);
        assertThat(context.matchingResult()).isSameAs(result);
        ring.release(1);
        assertThat(ring.inFlight()).isZero();
        assertThat(ring.highWaterMark()).isEqualTo(1);
    }

    @Test
    void failsClosedForDuplicateUnexpectedOrOutOfRangeAck() {
        LaneCommandContextRing.Context context = new LaneCommandContextRing(4).claim(1);
        context.result(new CoreMatchingResult(true, "ACCEPTED").withCoreSequence(1), 0b11, 0b1111);
        context.acknowledge(ack(1, 0, 1, 1));

        assertThatThrownBy(() -> context.acknowledge(ack(1, 0, 1, 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> context.acknowledge(ack(1, 2, 1, 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unexpected");
    }

    private static AccountLaneAck ack(long sequence, int laneId, long fee, long insurance) {
        return new AccountLaneAck(sequence, laneId, 1, 1, 1,
                fee, insurance, 0, 0, 0, 0);
    }
}
