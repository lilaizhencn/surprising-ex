package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BorrowedResponseSlotTest {
    private static void initialize(CoreMessage slot, long id) {
        slot.decoderPayloadBuffer(0);
        slot.decoderHeader(CoreProtocol.SCHEMA_VERSION, CoreMessageType.ADJUST_BALANCE, new UUID(1, id), ProductLine.SPOT,
                CommandSource.GATEWAY, 1, id, 7, 1, id);
    }

    @Test void terminalResponseAndHeaderRemainLeasedThroughTheLastTransportReader() {
        var slot = CoreMessage.decoderSlot();
        assertThat(slot.acquireDecoderSlot()).isTrue();
        initialize(slot, 1);
        var response = slot.ownedResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                1, new byte[]{1, 2, 3}, 0, 3);
        var header = slot.responseHeader(CoreMessageType.COMMAND_RESULT);
        assertThat(slot.header().kind()).isEqualTo(WireMessageKind.COMMAND);
        assertThat(header.kind()).isEqualTo(WireMessageKind.RESPONSE);
        byte[] retainedValue = response.data();
        header.retainForEgress();
        header.retainForEgress();
        slot.releaseDecoderSlot();
        assertThat(slot.acquireDecoderSlot()).isFalse();
        header.releaseFromEgress();
        assertThat(slot.acquireDecoderSlot()).isFalse();
        assertThat(response.data()).containsExactly(1, 2, 3);
        assertThat(header.commandId()).isEqualTo(new UUID(1, 1));
        header.releaseFromEgress();
        assertThat(response.dataUnsafe()).isEmpty();
        assertThat(slot.acquireDecoderSlot()).isTrue();
        initialize(slot, 2);
        var next = slot.ownedResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.INVALID_COMMAND,
                2, new byte[]{4, 5}, 0, 2);
        assertThat(next).isSameAs(response);
        assertThat(slot.responseHeader(CoreMessageType.COMMAND_RESULT)).isSameAs(header);
        assertThat(retainedValue).containsExactly(1, 2, 3);
        assertThat(next.appliedCommandCount()).isEqualTo(2);
        assertThatThrownBy(() -> slot.ownedResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                CoreResultCode.NONE, 3, new byte[0], 0, 0)).isInstanceOf(IllegalStateException.class);
        slot.releaseDecoderSlot();
    }

    @Test void independentMessagesKeepStableResponseValuesAcrossSubsequentResponses() {
        var request = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                new UUID(1, 1), ProductLine.SPOT, CommandSource.GATEWAY, 1, 1, 7, 1, 1), new byte[0]);
        var first = request.ownedResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                1, new byte[]{1}, 0, 1);
        var second = request.ownedResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.INVALID_COMMAND,
                2, new byte[]{2}, 0, 1);
        assertThat(first).isNotSameAs(second);
        assertThat(first.status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(first.appliedCommandCount()).isEqualTo(1);
        assertThat(first.data()).containsExactly(1);
    }
}
