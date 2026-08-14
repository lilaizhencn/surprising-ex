package com.surprising.aeron.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KafkaCoreExportSinkTest {

    @Test
    void pinsAuthoritativeEventsToPartitionZero() {
        UUID commandId = UUID.randomUUID();
        CoreExportEvent event = new CoreExportEvent(17, 17, 9, commandId,
                CoreMessageType.ADJUST_BALANCE, ResponseStatus.APPLIED, CoreResultCode.NONE,
                101, new byte[] {1});
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                commandId, ProductLine.SPOT, CommandSource.GATEWAY, 1, 1, 101, 1, 1).exportEvent(17),
                CoreExportCodec.encodeEvent(event));

        var record = KafkaCoreExportSink.record(KafkaCoreExportSink.topic(ProductLine.SPOT),
                ProductLine.SPOT, message);

        assertThat(record.topic()).isEqualTo("surprising.spot.core.events.v1");
        assertThat(record.partition()).isZero();
        assertThat(record.key()).isEqualTo("SPOT:17");
    }
}
