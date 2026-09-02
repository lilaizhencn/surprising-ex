package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

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
        PendingMatchingRing ring = new PendingMatchingRing(3);
        PendingMatching first = pending(7, UUID.randomUUID(), 1001);
        PendingMatching replacement = first.withCommand(command(first.command().header().commandId(), 1002));
        PendingMatching second = pending(11, UUID.randomUUID(), 1003);
        PendingMatching third = pending(12, UUID.randomUUID(), 1004);
        PendingMatching fourth = pending(13, UUID.randomUUID(), 1005);

        ring.put(first);
        ring.put(replacement);
        ring.put(second);
        ring.put(third);
        ring.put(fourth);

        assertThat(ring.capacity()).isEqualTo(4);
        assertThat(ring.size()).isEqualTo(4);
        assertThat(ring.firstSequence()).isEqualTo(7);
        assertThat(ring.get(7)).isSameAs(replacement);
        assertThat(ring.findByCommandId(second.command().header().commandId())).isSameAs(second);
        assertThat(ring.remove(11)).isSameAs(second);
        assertThat(ring.remove(12)).isSameAs(third);
        assertThat(ring.remove(13)).isSameAs(fourth);
        assertThat(ring.firstSequence()).isEqualTo(7);
        ring.put(pending(14, UUID.randomUUID(), 1006));
        ring.put(pending(15, UUID.randomUUID(), 1007));
        ring.put(pending(16, UUID.randomUUID(), 1008));
        assertThat(ring.snapshot().keySet()).containsExactly(7L, 14L, 15L, 16L);
        assertThat(ring.remove(7)).isSameAs(replacement);
        assertThat(ring.firstSequence()).isEqualTo(14);
        ring.clear();
        assertThat(ring.snapshot()).isEmpty();
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
