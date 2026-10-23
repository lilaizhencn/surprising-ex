package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreAlgoOrderCodecTest {
    @Test
    void roundTripsStateListAndQuery() {
        CoreAlgoOrderView value = new CoreAlgoOrderView(7, 11, "client-7", "1", 0,
                CoreOrderSide.BUY, 0, 100, 25, 10, 40, CoreMarginMode.CROSS, CorePositionSide.NET,
                false, false, CoreTimeInForce.IOC, 1, 91, "", 1, 2, 0, 1, 2, 3,
                List.of(91L), 25, 0, 0);
        assertThat(CoreAlgoOrderCodec.decode(CoreAlgoOrderCodec.encode(value))).isEqualTo(value);
        assertThat(CoreAlgoOrderCodec.decodeList(CoreAlgoOrderCodec.encodeList(List.of(value)))).containsExactly(value);
        CoreAlgoOrderView iceberg = new CoreAlgoOrderView(8, 11, "client-8", "1", 1,
                CoreOrderSide.BUY, 100, 100, 25, Long.MIN_VALUE, 40, CoreMarginMode.CROSS, CorePositionSide.NET,
                false, false, CoreTimeInForce.GTC, 1, 91, "", 1, 2, 0, 1, 2, 3,
                List.of(91L), 25, 0, 0);
        assertThat(iceberg.intervalSeconds()).isZero();
        assertThat(CoreAlgoOrderCodec.encode(value).length - CoreAlgoOrderCodec.encode(iceberg).length)
                .isEqualTo(Long.BYTES);
        assertThat(CoreAlgoOrderCodec.decodeList(CoreAlgoOrderCodec.encodeList(List.of(iceberg, value))))
                .containsExactly(iceberg, value);
        assertThat(CoreAlgoOrderCodec.decodeQuery(CoreAlgoOrderCodec.encodeQuery(11, 7, "1", 9, 100)))
                .isEqualTo(new CoreAlgoOrderCodec.Query(11, 7, "1", 9, 100));
    }

    @Test
    void roundTripsCreationAndLifecycleDelta() {
        var create = new CoreAlgoOrderCommand.Create(7, "client-7", "1", 0, CoreOrderSide.BUY,
                0, 100, 25, 10, 40, CoreMarginMode.CROSS, CorePositionSide.NET,
                false, false, CoreTimeInForce.IOC, 0);
        var update = new CoreAlgoOrderCommand.Update(7, 1, 1, 91, "", 2, 0, 2, 91);
        assertThat(CoreAlgoOrderCodec.decodeCommand(CoreAlgoOrderCodec.encodeCommand(create))).isEqualTo(create);
        var iceberg = new CoreAlgoOrderCommand.Create(7, "client-7", "1", 1, CoreOrderSide.BUY,
                100, 100, 25, Long.MAX_VALUE, 40, CoreMarginMode.CROSS, CorePositionSide.NET,
                false, false, CoreTimeInForce.GTC, 0);
        assertThat(iceberg.intervalSeconds()).isZero();
        assertThat(CoreAlgoOrderCodec.decodeCommand(CoreAlgoOrderCodec.encodeCommand(iceberg))).isEqualTo(iceberg);
        assertThat(CoreAlgoOrderCodec.encodeCommand(create).length - CoreAlgoOrderCodec.encodeCommand(iceberg).length)
                .isEqualTo(Long.BYTES);
        assertThat(CoreAlgoOrderCodec.decodeCommand(CoreAlgoOrderCodec.encodeCommand(update))).isEqualTo(update);
    }
}
