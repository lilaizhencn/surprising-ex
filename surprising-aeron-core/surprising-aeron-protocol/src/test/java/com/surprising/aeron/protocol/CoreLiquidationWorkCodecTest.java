package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CoreLiquidationWorkCodecTest {

    @Test
    void roundTripsBoundedLiquidationWork() {
        assertThat(CoreLiquidationWorkCodec.decodeQuery(CoreLiquidationWorkCodec.encodeQuery(128))).isEqualTo(128);
        CoreLiquidationWorkView work = new CoreLiquidationWorkView(true, List.of(
                new CoreLiquidationActionView(7, 11, "BTC-USDT", CoreMarginMode.ISOLATED,
                        CorePositionSide.LONG, 3, 19, 5, 5, 60_000)));

        assertThat(CoreLiquidationWorkCodec.decodeWork(CoreLiquidationWorkCodec.encodeWork(work))).isEqualTo(work);
    }
}
