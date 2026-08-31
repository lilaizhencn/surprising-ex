package com.surprising.aeron.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreMatcherTransition;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;

class KafkaCoreExportSinkTest {

    @Test
    void pinsAuthoritativeEventsToPartitionZero() {
        UUID commandId = UUID.randomUUID();
        byte[] payload = new byte[] {1};
        CoreMessage command = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                commandId, ProductLine.SPOT, CommandSource.GATEWAY, 1, 1, 101, 1, 1), payload);
        CoreExportEvent event = new CoreExportEvent(17, 17, 9, commandId,
                CoreMessageType.ADJUST_BALANCE, ResponseStatus.APPLIED, CoreResultCode.NONE,
                101, payload, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                8, 16, 17, com.surprising.aeron.protocol.CoreRoute.DEFAULT.version(), 1, 9, 17,
                CoreMatcherTransition.unchanged(0, 0), 17, List.of(), CommandFingerprint.of(command), List.of(),
                CoreExportEvent.TerminalIds.empty(), 16, 17, 16, 17, null, null,
                CoreExportEvent.Tombstones.empty());
        CoreMessage message = new CoreMessage(command.header().exportEvent(17),
                CoreExportCodec.encodeEvent(event));

        var record = KafkaCoreExportSink.record(KafkaCoreExportSink.topic(ProductLine.SPOT),
                ProductLine.SPOT, message);

        assertThat(record.topic()).isEqualTo("surprising.spot.core.events.v1");
        assertThat(record.partition()).isZero();
        assertThat(record.key()).isEqualTo("SPOT:17");
    }
}
