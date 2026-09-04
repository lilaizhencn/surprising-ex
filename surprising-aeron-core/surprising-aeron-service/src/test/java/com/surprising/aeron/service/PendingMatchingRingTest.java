package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.service.state.RuntimeFundsDelta;
import com.surprising.aeron.service.state.RuntimeProjectionPoint;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingMatchingRingTest {

    @Test
    void keepsTheLowWatermarkWhileAllowingIndependentPartitionCompletion() {
        PendingMatchingRing ring = new PendingMatchingRing(3, 1, 4);
        PendingMatching first = pending(7, UUID.randomUUID(), 1001);
        PendingMatching replacement = first.withCommand(command(first.command().header().commandId(), 1002));
        PendingMatching second = pending(8, UUID.randomUUID(), 1003);
        PendingMatching third = pending(9, UUID.randomUUID(), 1004);
        PendingMatching fourth = pending(10, UUID.randomUUID(), 1005);

        ring.put(first);
        assertThat(ring.contexts().required(7).pending()).isSameAs(first);
        ring.put(replacement);
        ring.put(second);
        ring.put(third);
        ring.put(fourth);

        assertThat(ring.capacity()).isEqualTo(4);
        assertThat(ring.size()).isEqualTo(4);
        assertThat(ring.firstSequence()).isEqualTo(7);
        assertThat(ring.get(7)).isSameAs(replacement);
        assertThat(ring.findByCommandId(second.command().header().commandId())).isSameAs(second);
        assertThat(ring.remove(8)).isSameAs(second);
        assertThat(ring.remove(9)).isSameAs(third);
        assertThat(ring.remove(10)).isSameAs(fourth);
        assertThat(ring.firstSequence()).isEqualTo(7);
        assertThatThrownBy(() -> ring.put(pending(11, UUID.randomUUID(), 1006)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sequence window");
        assertThat(ring.remove(7)).isSameAs(replacement);
        assertThat(ring.contexts().claimed(7)).isFalse();
        ring.put(pending(11, UUID.randomUUID(), 1006));
        ring.put(pending(12, UUID.randomUUID(), 1007));
        ring.put(pending(13, UUID.randomUUID(), 1008));
        ring.put(pending(14, UUID.randomUUID(), 1009));
        assertThat(ring.snapshot().keySet()).containsExactly(11L, 12L, 13L, 14L);
        assertThat(ring.firstSequence()).isEqualTo(11);
        ring.clear();
        assertThat(ring.snapshot()).isEmpty();
    }

    @Test
    void advancesEachMatcherSubmissionShardWithoutScanningThePendingRing() {
        PendingMatchingRing ring = new PendingMatchingRing(4, 2, 4);
        PendingMatching shardZeroFirst = pending(1, UUID.randomUUID(), 1001);
        PendingMatching shardOne = pending(2, UUID.randomUUID(), 1002);
        PendingMatching shardZeroSecond = pending(3, UUID.randomUUID(), 1003);
        ring.put(shardZeroFirst);
        ring.put(shardOne);
        ring.put(shardZeroSecond);
        ring.registerSubmission(1, 0);
        ring.registerSubmission(2, 1);
        ring.registerSubmission(3, 0);

        assertThat(ring.submissionHead(0)).isSameAs(shardZeroFirst);
        assertThat(ring.submissionHead(1)).isSameAs(shardOne);
        assertThat(ring.isSubmissionHead(3, 0)).isFalse();
        ring.completeSubmission(1);
        assertThat(ring.submissionHead(0)).isSameAs(shardZeroSecond);
        assertThat(ring.isSubmissionHead(3, 0)).isTrue();
        ring.remove(2);
        assertThat(ring.submissionHead(1)).isNull();
    }

    @Test
    void onlyPublishesReadyCommandsAtTheDeterministicSubmissionHead() {
        PendingMatchingRing ring = new PendingMatchingRing(2, 1, 4);
        PendingMatching first = pending(1, UUID.randomUUID(), 1001);
        PendingMatching second = pending(2, UUID.randomUUID(), 1002);
        ring.put(first);
        ring.put(second);

        ring.markReady(2);
        assertThat(ring.pollReadyHead()).isNull();
        ring.markReady(1);
        ring.markReady(1);
        assertThat(ring.pollReadyHead()).isSameAs(first);

        ring.markReady(1);
        assertThat(ring.remove(1)).isSameAs(first);
        assertThat(ring.pollReadyHead()).isSameAs(second);
        assertThat(ring.pollReadyHead()).isNull();
    }

    @Test
    void advancesSettlementDispatchIndependentlyFromTheGlobalCommitHead() {
        PendingMatchingRing ring = new PendingMatchingRing(4, 1, 4);
        PendingMatching first = pending(1, UUID.randomUUID(), 1001);
        PendingMatching second = pending(2, UUID.randomUUID(), 1002);
        PendingMatching third = pending(3, UUID.randomUUID(), 1003);
        ring.put(first);
        ring.put(second);
        ring.put(third);

        assertThat(ring.dispatchHead()).isSameAs(first);
        ring.completeDispatch(1);
        assertThat(ring.dispatchHead()).isSameAs(second);
        assertThat(ring.firstSequence()).isEqualTo(1);
        ring.remove(2);
        assertThat(ring.dispatchHead()).isSameAs(third);
        ring.remove(1);
        assertThat(ring.firstSequence()).isEqualTo(3);
        ring.completeDispatch(3);
        assertThat(ring.dispatchHead()).isNull();
    }

    private static PendingMatching pending(long sequence, UUID commandId, long userId) {
        return new PendingMatching(sequence, PendingMatching.Operation.PLACE,
                command(commandId, userId), new RuntimeProjectionPoint(
                        0, TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL)),
                1, 1, RuntimeFundsDelta.empty());
    }

    private static CoreMessage command(UUID commandId, long userId) {
        return new CoreMessage(CoreMessageHeader.command(
                CoreMessageType.PROBE_INCREMENT, commandId, ProductLine.LINEAR_PERPETUAL,
                CommandSource.GATEWAY, 1, 1, userId, 1_000, 1), new byte[]{1});
    }
}
