package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.state.RuntimeCommitJournal;
import com.surprising.aeron.service.state.RuntimeFundsDelta;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.product.api.ProductLine;
import java.util.Arrays;
import java.util.List;
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

        context.completeLanes(0b001);
        assertThat(context.complete()).isFalse();
        context.completeLanes(0b100);

        assertThat(context.complete()).isTrue();
        assertThat(context.completedLaneMask()).isEqualTo(0b101);
        assertThat(context.matchingResult()).isSameAs(result);
        ring.release(1);
        assertThat(ring.inFlight()).isZero();
        assertThat(ring.highWaterMark()).isEqualTo(1);
    }

    @Test
    void failsClosedForDuplicateUnexpectedOrOutOfRangeAck() {
        LaneCommandContextRing.Context context = new LaneCommandContextRing(4, 4).claim(1);
        CoreMatchingResult result = new CoreMatchingResult(true, "ACCEPTED").withCoreSequence(1);
        context.result(result, 0b11, 0b1111);
        context.completeLanes(0b01);

        assertThatThrownBy(() -> context.completeLanes(0b01))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> context.completeLanes(0b100))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unexpected");
        assertThatThrownBy(() -> context.completeLanes(0b1_0000))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unexpected");
    }

    @Test
    void storesSynchronousMatchingResultAndKeepsTheFirstCompletion() {
        LaneCommandContextRing ring = new LaneCommandContextRing(4, 4);
        LaneCommandContextRing.Context context = ring.claim(3);
        CoreMatchingResult first = new CoreMatchingResult(true, "SUCCESS").withCoreSequence(3);
        CoreMatchingResult duplicate = new CoreMatchingResult(false, "LATE").withCoreSequence(3);

        context.publishMatchingCompletion(first);
        context.publishMatchingCompletion(duplicate);

        assertThat(context.takeMatchingCompletion()).isSameAs(first);
        assertThat(context.takeMatchingCompletion()).isNull();
        context.resetMatchingContinuation();
        ring.discard(3);
    }

    @Test
    void storesMatchingRejectionInTheSequenceSlot() {
        LaneCommandContextRing ring = new LaneCommandContextRing(4, 4);
        LaneCommandContextRing.Context context = ring.claim(1);

        context.rejectMatching(CoreResultCode.MATCHING_REJECTED);

        assertThat(context.hasMatchingRejection()).isTrue();
        assertThat(context.matchingRejection()).isEqualTo(CoreResultCode.MATCHING_REJECTED);
        assertThatThrownBy(() -> context.rejectMatching(CoreResultCode.INVALID_COMMAND))
                .isInstanceOf(IllegalStateException.class);
        ring.discard(1);
    }

    @Test
    void ownsAdmissionAndSuspendedCommitStateBySequence() {
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        try (RuntimeCommitJournal journal = new RuntimeCommitJournal(
                ProductLine.LINEAR_PERPETUAL, initial, initial.businessStateHash(), 0)) {
            CoreAdmissionReservation admission = CoreAdmissionReservation.reserve(journal, null,
                    new CoreAdmissionReservation.AdmissionDemand(1));
            LaneCommandContextRing ring = new LaneCommandContextRing(4, 4);
            LaneCommandContextRing.Context context = ring.claim(2);
            context.admission(admission);
            context.suspendCommitContext(
                    List.of(7L), List.of(11L), RuntimeFundsDelta.empty(), true, false);

            assertThat(context.admission()).isSameAs(admission);
            assertThat(context.hasCommitContext()).isTrue();
            assertThat(context.commitChangedUserIds()).containsExactly(7L);
            assertThat(context.commitChangedOrderIds()).containsExactly(11L);
            assertThat(context.commitFundsDelta()).isSameAs(RuntimeFundsDelta.empty());
            assertThat(context.commitSnapshotDirty()).isTrue();
            assertThat(context.commitSnapshotProvisionalOnly()).isFalse();
            context.clearCommitContext();
            assertThat(context.hasCommitContext()).isFalse();

            admission.releaseUnused();
            ring.discard(2);
        }
    }

}
