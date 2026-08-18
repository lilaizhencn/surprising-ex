package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreAlgoOrderCodecTest {
    @Test
    void roundTripsStateListAndQuery() {
        CoreAlgoOrderView value = new CoreAlgoOrderView(7, 11, "client-7", "BTC-USDT", 0,
                CoreOrderSide.BUY, 0, 100, 25, 10, 40, CoreMarginMode.CROSS, CorePositionSide.NET,
                false, false, CoreTimeInForce.IOC, 1, 91, "", "trace", 1, 2, 0, 1, 2, 3,
                List.of(91L), 25, 0, 0);
        assertThat(CoreAlgoOrderCodec.decode(CoreAlgoOrderCodec.encode(value))).isEqualTo(value);
        assertThat(CoreAlgoOrderCodec.decodeList(CoreAlgoOrderCodec.encodeList(List.of(value)))).containsExactly(value);
        assertThat(CoreAlgoOrderCodec.decodeQuery(CoreAlgoOrderCodec.encodeQuery(11, 7, "BTC-USDT", 9, 100)))
                .isEqualTo(new CoreAlgoOrderCodec.Query(11, 7, "BTC-USDT", 9, 100));
    }

    @Test
    void materializesCreationTemplateWithClusterTime() {
        CoreAlgoOrderView template = new CoreAlgoOrderView(7, 11, "client-7", "BTC-USDT", 0,
                CoreOrderSide.BUY, 0, 100, 25, 10, 40, CoreMarginMode.CROSS, CorePositionSide.NET,
                false, false, CoreTimeInForce.IOC, 0, 0, "", "command-id", 0, 0, 0, 0, 0, 1,
                List.of(), 0, 0, 0);

        CoreAlgoOrderView decoded = CoreAlgoOrderCodec.decode(CoreAlgoOrderCodec.encode(template));
        CoreAlgoOrderView materialized = decoded.materializeCreation(1_700_000_000_000L);

        assertThat(materialized.startAtEpochMillis()).isEqualTo(1_700_000_000_000L);
        assertThat(materialized.nextSliceAtEpochMillis()).isEqualTo(1_700_000_000_000L);
        assertThat(materialized.createdAtEpochMillis()).isEqualTo(1_700_000_000_000L);
        assertThat(materialized.updatedAtEpochMillis()).isEqualTo(1_700_000_000_000L);
    }
}
