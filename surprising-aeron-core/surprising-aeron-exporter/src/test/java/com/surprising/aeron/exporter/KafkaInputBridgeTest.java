package com.surprising.aeron.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.client.ResultUnknownException;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.CoreProbeState;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KafkaInputBridgeTest {

    @Test
    void stableOffsetMapsToIdempotentCoreCommandAndThenMayCommit() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        KafkaInputBridge bridge = new KafkaInputBridge(ProductLine.SPOT, state::apply);
        var input = new KafkaInputBridge.KafkaInput("instrument.v1", 3, 41, 1_000);

        var applied = bridge.submit(input, CoreMessageType.PROBE_INCREMENT, 0, CoreProtocol.probePayload(7));
        var duplicate = bridge.submit(input, CoreMessageType.PROBE_INCREMENT, 0, CoreProtocol.probePayload(7));

        assertThat(applied.status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(KafkaInputBridge.mayCommitOffset(applied)).isTrue();
        assertThat(KafkaInputBridge.mayCommitOffset(duplicate)).isTrue();
        assertThat(state.probeValue()).isEqualTo(7);
        assertThat(KafkaInputBridge.sourceId("instrument.v1", 3))
                .isEqualTo(KafkaInputBridge.sourceId("instrument.v1", 3));
    }

    @Test
    void unknownResultPropagatesAndCannotAuthorizeOffsetCommit() {
        KafkaInputBridge bridge = new KafkaInputBridge(ProductLine.SPOT, message -> {
            throw new ResultUnknownException(UUID.randomUUID(), "timeout");
        });

        assertThatThrownBy(() -> bridge.submit(new KafkaInputBridge.KafkaInput("mark.v1", 0, 1, 2),
                CoreMessageType.PROBE_INCREMENT, 0, CoreProtocol.probePayload(1)))
                .isInstanceOf(ResultUnknownException.class);
        assertThat(KafkaInputBridge.mayCommitOffset(null)).isFalse();
        assertThat(KafkaInputBridge.mayCommitOffset(new CoreResponse(ResponseStatus.REJECTED,
                ResponseStatus.REJECTED, CoreResultCode.EXPORT_BACKLOG_FULL, 0, 0))).isFalse();
    }
}
