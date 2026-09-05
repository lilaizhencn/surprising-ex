package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CoreOrderPreflightCodecTest {

    @Test
    void roundTripsExactReservation() {
        CoreOrderPreflightView view = new CoreOrderPreflightView("USDT", 12_345);

        assertThat(CoreOrderPreflightCodec.decode(CoreOrderPreflightCodec.encode(view))).isEqualTo(view);
    }
}
