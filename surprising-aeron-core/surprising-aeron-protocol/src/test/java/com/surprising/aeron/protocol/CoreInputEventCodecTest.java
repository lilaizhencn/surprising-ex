package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class CoreInputEventCodecTest {

    @Test
    void roundTripsVersionedInputEnvelope() {
        CoreInputEvent event = new CoreInputEvent(CoreProtocol.SCHEMA_VERSION,
                ProductLine.LINEAR_PERPETUAL, CoreMessageType.APPLY_MARK_PRICE, 0,
                new byte[] {1, 2, 3});

        CoreInputEvent decoded = CoreInputEventCodec.decode(CoreInputEventCodec.encode(event));

        assertThat(decoded.schemaVersion()).isEqualTo(CoreProtocol.SCHEMA_VERSION);
        assertThat(decoded.productLine()).isEqualTo(ProductLine.LINEAR_PERPETUAL);
        assertThat(decoded.commandType()).isEqualTo(CoreMessageType.APPLY_MARK_PRICE);
        assertThat(decoded.commandPayload()).containsExactly(1, 2, 3);
    }
}
