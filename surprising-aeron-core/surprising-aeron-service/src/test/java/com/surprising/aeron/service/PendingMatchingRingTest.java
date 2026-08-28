package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.service.state.RuntimeFundsDelta;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingMatchingRingTest {

    @Test
    void keepsPrimitiveSequenceOrderAndReplacesAnExistingSlotWithoutGrowing() {
        PendingMatchingRing ring = new PendingMatchingRing(3);
        PendingMatching first = pending(7, UUID.randomUUID(), 1001);
        PendingMatching replacement = first.withCommand(command(first.command().header().commandId(), 1002));
        PendingMatching second = pending(11, UUID.randomUUID(), 1003);

        ring.put(first);
        ring.put(replacement);
        ring.put(second);

        assertThat(ring.capacity()).isEqualTo(4);
        assertThat(ring.size()).isEqualTo(2);
        assertThat(ring.firstSequence()).isEqualTo(7);
        assertThat(ring.get(7)).isSameAs(replacement);
        assertThat(ring.findByCommandId(second.command().header().commandId())).isSameAs(second);
        assertThat(ring.remove(11)).isNull();
        assertThat(ring.remove(7)).isSameAs(replacement);
        assertThat(ring.firstSequence()).isEqualTo(11);
        assertThat(ring.snapshot()).containsOnlyKeys(11L);
    }

    private static PendingMatching pending(long sequence, UUID commandId, long userId) {
        return new PendingMatching(sequence, PendingMatching.Operation.PLACE,
                command(commandId, userId), TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                1, 1, RuntimeFundsDelta.empty());
    }

    private static CoreMessage command(UUID commandId, long userId) {
        return new CoreMessage(CoreMessageHeader.command(
                CoreMessageType.PROBE_INCREMENT, commandId, ProductLine.LINEAR_PERPETUAL,
                CommandSource.GATEWAY, 1, 1, userId, 1_000, 1), new byte[]{1});
    }
}
