package com.surprising.aeron.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.state.RuntimeCommitJournal;
import com.surprising.aeron.service.state.RuntimeFundsAccumulator;
import com.surprising.aeron.service.state.RuntimeFundsDelta;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.product.api.ProductLine;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LaneCommandContextRingTest {

    @Test
    void suspendedSequencesTransferFundsWithoutSharingTheActiveBuffer() {
        var ring = new LaneCommandContextRing(4, 4);
        var first = ring.claim(1);
        var second = ring.claim(2);
        var active = new RuntimeFundsAccumulator();
        active.add(7, com.surprising.aeron.service.state.FundsPosting.OwnerKind.USER, 11,
                com.surprising.aeron.service.state.FundsPosting.Subledger.AVAILABLE, -100);
        var firstExpected = active.toDelta();
        first.suspendCommitContext(List.of(11L), List.of(1L), active, true, false);
        assertThat(active.toDelta()).isSameAs(RuntimeFundsDelta.empty());
        active.add(8, com.surprising.aeron.service.state.FundsPosting.OwnerKind.USER, 22,
                com.surprising.aeron.service.state.FundsPosting.Subledger.LOCKED, 50);
        var secondExpected = active.toDelta();
        second.suspendCommitContext(List.of(22L), List.of(2L), active, false, false);
        first.takeCommitFundsTo(active);
        first.clearCommitContext();
        assertThat(active.toDelta()).usingRecursiveComparison().isEqualTo(firstExpected);
        second.takeCommitFundsTo(active);
        second.clearCommitContext();
        assertThat(active.toDelta()).usingRecursiveComparison().isEqualTo(secondExpected);
        ring.discard(1);
        ring.discard(2);
        assertThat(active.toDelta()).usingRecursiveComparison().isEqualTo(secondExpected);
    }

    @Test
    void synchronousControlLanesCannotEraseMatcherParticipantsOrWeakenAckValidation() {
        var ring=new LaneCommandContextRing(4,4);
        var context=ring.claim(1);
        assertThatThrownBy(()->context.includeControlLanes(2,15)).isInstanceOf(IllegalStateException.class);
        context.result(new CoreMatchingResult(true,"ACCEPTED").withCoreSequence(1),1,15);
        context.includeControlLanes(2,15);
        assertThat(context.expectedLaneMask()).isEqualTo(3);
        assertThatThrownBy(()->context.includeControlLanes(16,15)).isInstanceOf(IllegalStateException.class);
        context.completeLanes(2);
        assertThat(context.complete()).isFalse();
        assertThatThrownBy(()->context.completeLanes(2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->context.completeLanes(4)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->context.includeControlLanes(4,15)).isInstanceOf(IllegalStateException.class);
        context.completeLanes(1);
        assertThat(context.complete()).isTrue();
    }

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
            RuntimeFundsAccumulator funds = new RuntimeFundsAccumulator();
            RuntimeFundsAccumulator restoredFunds = new RuntimeFundsAccumulator();
            context.admission(admission);
            context.suspendCommitContext(
                    List.of(7L), List.of(11L), funds, true, false);

            assertThat(context.admission()).isSameAs(admission);
            assertThat(context.hasCommitContext()).isTrue();
            assertThat(context.commitChangedUserIds()).containsExactly(7L);
            assertThat(context.commitChangedOrderIds()).containsExactly(11L);
            context.takeCommitFundsTo(restoredFunds);
            assertThat(restoredFunds.toDelta()).isSameAs(RuntimeFundsDelta.empty());
            assertThat(context.commitSnapshotDirty()).isTrue();
            assertThat(context.commitSnapshotProvisionalOnly()).isFalse();
            context.clearCommitContext();
            assertThat(context.hasCommitContext()).isFalse();

            admission.releaseUnused();
            ring.discard(2);
        }
    }

}
