package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreExportCodecTest {

    @Test
    void roundTripsEventBatchAckAndStatus() {
        UUID commandId = UUID.randomUUID();
        CoreExportEvent event = new CoreExportEvent(7, 11, 13, commandId,
                CoreMessageType.ADJUST_BALANCE, ResponseStatus.APPLIED, CoreResultCode.NONE,
                17, new byte[]{1, 2, 3});
        CoreMessage message = new CoreMessage(new CoreMessageHeader(CoreProtocol.SCHEMA_VERSION,
                WireMessageKind.EXPORT_EVENT, CoreMessageType.CORE_EVENT, commandId, ProductLine.SPOT,
                CommandSource.GATEWAY, 1, 7, 17, 19, 23), CoreExportCodec.encodeEvent(event));

        CoreExportEvent restored = CoreExportCodec.decodeEvent(message.payload());
        List<CoreMessage> batch = CoreExportCodec.decodeBatch(CoreExportCodec.encodeBatch(List.of(message)));

        assertThat(restored.exportSequence()).isEqualTo(7);
        assertThat(restored.commandPayload()).containsExactly(1, 2, 3);
        assertThat(batch).containsExactly(message);
        assertThat(CoreExportCodec.decodeAck(CoreExportCodec.encodeAck(new AckExportCommand(7))))
                .isEqualTo(new AckExportCommand(7));
        CoreExportStatus status = new CoreExportStatus(6, 8, 1, 256, 1_000_000, 64L * 1024 * 1024);
        assertThat(CoreExportCodec.decodeStatus(CoreExportCodec.encodeStatus(status))).isEqualTo(status);
    }

    @Test
    void rejectsTruncatedOrTrailingPayloads() {
        assertThatThrownBy(() -> CoreExportCodec.decodeBatch(new byte[]{1, 0, 0, 0}))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreExportCodec.decodeAck(new byte[7]))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreExportCodec.decodeBatchQuery(new byte[]{0, 0, 0, 0}))
                .isInstanceOf(ProtocolException.class);
    }
}
