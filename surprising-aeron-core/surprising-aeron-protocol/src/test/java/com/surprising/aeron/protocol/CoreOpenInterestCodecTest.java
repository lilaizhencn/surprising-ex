package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CoreOpenInterestCodecTest {

    @Test
    void roundTripsSortedOpenInterest() {
        var values = List.of(new CoreOpenInterestView("BTC-USDT", 11, 9));
        assertThat(CoreOpenInterestCodec.decode(CoreOpenInterestCodec.encode(values)))
                .containsExactlyElementsOf(values);
    }

    @Test
    void rejectsTrailingBytes() {
        byte[] encoded = CoreOpenInterestCodec.encode(List.of());
        assertThatThrownBy(() -> CoreOpenInterestCodec.decode(java.util.Arrays.copyOf(encoded, encoded.length + 1)))
                .isInstanceOf(ProtocolException.class);
    }
}
