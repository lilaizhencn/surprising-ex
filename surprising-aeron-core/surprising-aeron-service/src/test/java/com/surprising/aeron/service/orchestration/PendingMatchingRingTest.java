package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.service.state.RuntimeFundsDelta;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingMatchingRingTest {

    @Test
    void partitionDispatchPreservesBookOrderAndGlobalCommitWatermark() {
        var ring = new PendingMatchingRing(4, 2, 4);
        var first = acquire(ring, 1, UUID.randomUUID(), 1001);
        var second = acquire(ring, 2, UUID.randomUUID(), 1002);
        var third = acquire(ring, 3, UUID.randomUUID(), 1003);
        first.clusterIndependent = second.clusterIndependent = third.clusterIndependent = true;
        first.partitionLaneMask = 1; second.partitionLaneMask = third.partitionLaneMask = 2;
        ring.put(first); ring.put(second); ring.put(third);
        ring.registerSubmission(1, 0); ring.registerSubmission(2, 1); ring.registerSubmission(3, 1);
        ring.completeSubmission(2);
        assertThat(partitionHead(ring, 1)).isSameAs(second);
        ring.completePartitionDispatchKnown(2, 1);
        assertThat(partitionHead(ring, 1)).isSameAs(third);
        ring.completePartitionDispatchKnown(3, 1);
        assertThat(partitionHead(ring, 1)).isNull();
        assertThat(ring.dispatchHead()).isSameAs(first);
        assertThat(ring.firstSequence()).isEqualTo(1);
        ring.completePartitionDispatchKnown(1, 0);
        assertThat(ring.dispatchHead()).isNull();
        ring.clear();
        var reused = acquire(ring, 5, UUID.randomUUID(), 1004);
        ring.put(reused); ring.registerSubmission(5, 1);
        assertThat(partitionHead(ring, 1)).isSameAs(reused);
    }

    @Test
    void dependentOrUnroutedCommandCannotBeBypassedByAnotherPartition() {
        var ring = new PendingMatchingRing(4, 2, 4);
        var first = acquire(ring, 1, UUID.randomUUID(), 1001);
        var second = acquire(ring, 2, UUID.randomUUID(), 1002);
        second.clusterIndependent = true;
        first.partitionLaneMask = 1;
        second.partitionLaneMask = 2;
        ring.put(first); ring.put(second); ring.registerSubmission(2, 1);
        assertThat(partitionHead(ring, 1)).isNull();
        ring.registerSubmission(1, 0);
        assertThat(partitionHead(ring, 1)).isNull();
    }

    @Test
    void collectsDispatchHeadsWithOneGlobalPrefixPass() {
        var ring = new PendingMatchingRing(4, 2, 4);
        var first = acquire(ring, 1, UUID.randomUUID(), 1001);
        var second = acquire(ring, 2, UUID.randomUUID(), 1002);
        var third = acquire(ring, 3, UUID.randomUUID(), 1003);
        first.clusterIndependent = second.clusterIndependent = third.clusterIndependent = true;
        first.partitionLaneMask = 1;
        second.partitionLaneMask = 2;
        third.partitionLaneMask = 4;
        ring.put(first); ring.put(second); ring.put(third);
        ring.registerSubmission(1, 0);
        ring.registerSubmission(2, 1);
        ring.registerSubmission(3, 0);

        CommandSlot[] heads = new CommandSlot[2];
        ring.collectPartitionDispatchHeads(3, heads);
        assertThat(heads[0]).isSameAs(first);
        assertThat(heads[1]).isSameAs(second);
        ring.completePartitionDispatchKnown(1, 0);

        ring.collectPartitionDispatchHeads(3, heads);
        assertThat(heads[0]).isSameAs(third);
        assertThat(heads[1]).isSameAs(second);
    }

    @Test
    void keepsTheLowWatermarkWhileAllowingIndependentPartitionCompletion() {
        PendingMatchingRing ring = new PendingMatchingRing(3, 1, 4);
        CommandSlot first = acquire(ring, 7, UUID.randomUUID(), 1001);
        CommandSlot replacement = first;
        CommandSlot second = acquire(ring, 8, UUID.randomUUID(), 1003);
        CommandSlot third = acquire(ring, 9, UUID.randomUUID(), 1004);
        CommandSlot fourth = acquire(ring, 10, UUID.randomUUID(), 1005);

        ring.put(first);
        assertThat(ring.get(7)).isSameAs(first);
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
        assertThatThrownBy(() -> ring.put(acquire(ring, 11, UUID.randomUUID(), 1006)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ring is full");
        assertThat(ring.remove(7)).isSameAs(replacement);
        assertThat(ring.contexts().claimed(7)).isFalse();
        ring.put(acquire(ring, 11, UUID.randomUUID(), 1006));
        ring.put(acquire(ring, 12, UUID.randomUUID(), 1007));
        ring.put(acquire(ring, 13, UUID.randomUUID(), 1008));
        ring.put(acquire(ring, 14, UUID.randomUUID(), 1009));
        assertThat(ring.snapshot().keySet()).containsExactly(11L, 12L, 13L, 14L);
        assertThat(ring.firstSequence()).isEqualTo(11);
        ring.clear();
        assertThat(ring.snapshot()).isEmpty();
    }

    @Test
    void advancesEachMatcherSubmissionShardWithoutScanningThePendingRing() {
        PendingMatchingRing ring = new PendingMatchingRing(4, 2, 4);
        CommandSlot shardZeroFirst = acquire(ring, 1, UUID.randomUUID(), 1001);
        CommandSlot shardOne = acquire(ring, 2, UUID.randomUUID(), 1002);
        CommandSlot shardZeroSecond = acquire(ring, 3, UUID.randomUUID(), 1003);
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
    void exposesOnlyTheDeterministicSubmissionHead() {
        PendingMatchingRing ring = new PendingMatchingRing(2, 1, 4);
        CommandSlot first = acquire(ring, 1, UUID.randomUUID(), 1001);
        CommandSlot second = acquire(ring, 2, UUID.randomUUID(), 1002);
        ring.put(first);
        ring.put(second);

        assertThat(ring.head()).isSameAs(first);
        assertThat(ring.remove(1)).isSameAs(first);
        assertThat(ring.head()).isSameAs(second);
        assertThat(ring.remove(2)).isSameAs(second);
        assertThat(ring.head()).isNull();
    }

    @Test
    void advancesSettlementDispatchIndependentlyFromTheGlobalCommitHead() {
        PendingMatchingRing ring = new PendingMatchingRing(4, 1, 4);
        CommandSlot first = acquire(ring, 1, UUID.randomUUID(), 1001);
        CommandSlot second = acquire(ring, 2, UUID.randomUUID(), 1002);
        CommandSlot third = acquire(ring, 3, UUID.randomUUID(), 1003);
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

    @Test
    void reusesTheSequenceContextPendingCarrierAfterTheSlotIsReleased() {
        PendingMatchingRing ring = new PendingMatchingRing(4, 1, 4);
        CommandSlot first = acquire(ring, 1, UUID.randomUUID(), 1001);
        ring.put(first);
        ring.remove(1);

        CommandSlot reused = acquire(ring, 5, UUID.randomUUID(), 1002);

        assertThat(reused).isSameAs(first);
        assertThat(reused.sequence()).isEqualTo(5);
        assertThat(reused.command().header().userId()).isEqualTo(1002);
        ring.discardPrepared(5);
    }

    private static CommandSlot acquire(PendingMatchingRing ring, long sequence, UUID commandId, long userId) {
        CoreMessage command = command(commandId, userId);
        return ring.acquire(sequence, CommandSlot.Operation.PLACE, command,
                com.surprising.aeron.protocol.CommandFingerprint.of(command), java.util.List.of(),
                RuntimeFundsDelta.empty(), DecodedMatchingCommand.decode(command), null);
    }

    private static CommandSlot partitionHead(PendingMatchingRing ring, int shard) {
        ring.readyPartitionMask(Long.MAX_VALUE);
        return ring.readyPartitionHead(shard);
    }

    private static CoreMessage command(UUID commandId, long userId) {
        return new CoreMessage(CoreMessageHeader.command(
                CoreMessageType.PROBE_INCREMENT, commandId, ProductLine.LINEAR_PERPETUAL,
                CommandSource.GATEWAY, 1, 1, userId, 1_000, 1), new byte[]{1});
    }
}
