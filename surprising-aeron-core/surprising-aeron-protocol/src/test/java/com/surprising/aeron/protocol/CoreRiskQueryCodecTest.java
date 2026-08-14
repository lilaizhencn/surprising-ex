package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CoreRiskQueryCodecTest {
    @Test
    void roundTripsRiskSnapshots() {
        var value = new CoreRiskSnapshotView(1001, "BTC-USDT", CoreMarginMode.CROSS, CorePositionSide.LONG,
                7, "USDT", 10, 50_000, 55_000, 550_000, 0, 9,
                1_000, 100, -20, 50, 500_000, "WARNING");
        assertThat(CoreRiskQueryCodec.decode(CoreRiskQueryCodec.encode(List.of(value)))).containsExactly(value);
    }

    @Test
    void rejectsTrailingBytes() {
        byte[] encoded = CoreRiskQueryCodec.encode(List.of());
        byte[] malformed = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThatThrownBy(() -> CoreRiskQueryCodec.decode(malformed)).isInstanceOf(ProtocolException.class);
    }
}
