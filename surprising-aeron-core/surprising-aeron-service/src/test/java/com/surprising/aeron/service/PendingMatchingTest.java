package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingMatchingTest {

    @Test
    void preservesCapturedPreCommandHashesAcrossDeferredMatchingUpdates() {
        CoreMessage command = command(11);
        TradingCoreState beforeState = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        PendingMatching pending = new PendingMatching(7, PendingMatching.Operation.PLACE, command,
                List.of(17L), beforeState, 101L, 202L);

        PendingMatching updatedCommand = pending.withCommand(command(12));
        PendingMatching updatedCancellations = updatedCommand.withPreMatchingCancellations(List.of(18L, 19L));

        assertThat(updatedCancellations.beforeBusinessStateHash()).isEqualTo(101L);
        assertThat(updatedCancellations.beforeFundsStateHash()).isEqualTo(202L);
        assertThat(updatedCancellations.beforeState()).isSameAs(beforeState);
        assertThat(updatedCancellations.preMatchingCancellationOrderIds()).containsExactly(18L, 19L);
    }

    private static CoreMessage command(long sourceSequence) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, UUID.randomUUID(),
                ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 7, sourceSequence,
                101, 1_700_000_000_000L, sourceSequence), new byte[0]);
    }
}
