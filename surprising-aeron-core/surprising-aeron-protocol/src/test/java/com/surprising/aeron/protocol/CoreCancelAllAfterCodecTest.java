package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CoreCancelAllAfterCodecTest {
    @Test
    void roundTripsCommandQueryAndViews() {
        CoreCancelAllAfterCommand command = new CoreCancelAllAfterCommand(CoreCancelAllAfterAction.CLAIM,
                101, "BTC-USDT", 1_000, 2_000, 4, 0, 0, 2_001);
        CoreCancelAllAfterView view = new CoreCancelAllAfterView(101, "BTC-USDT", 1_000,
                CoreCancelAllAfterStatus.TRIGGERING, 2_000, 2_001, 0, 0, 5);

        assertThat(CoreCancelAllAfterCodec.decodeCommand(CoreCancelAllAfterCodec.encodeCommand(command)))
                .isEqualTo(command);
        assertThat(CoreCancelAllAfterCodec.decodeQuery(
                CoreCancelAllAfterCodec.encodeQuery(101, "BTC-USDT", 2_000, 100)))
                .isEqualTo(new CoreCancelAllAfterCodec.Query(101, "BTC-USDT", 2_000, 100));
        assertThat(CoreCancelAllAfterCodec.decodeList(CoreCancelAllAfterCodec.encodeList(List.of(view))))
                .containsExactly(view);
    }
}
