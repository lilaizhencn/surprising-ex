package com.surprising.product.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InstrumentSpecEpochTest {

    @Test
    void keepsTheSameInternalIdentityAsTheLegacySpecId() {
        InstrumentSpecEpoch epoch = new InstrumentSpecEpoch(ProductLine.OPTION, " btc-usdt ", 7L);

        assertThat(epoch.symbol()).isEqualTo("BTC-USDT");
        assertThat(epoch.key()).isEqualTo("OPTION:BTC-USDT:7");
        assertThat(epoch.asSpecId().epoch()).isEqualTo(7L);
    }

    @Test
    void rejectsAnInvalidEpoch() {
        assertThatThrownBy(() -> new InstrumentSpecEpoch(ProductLine.SPOT, "BTC-USDT", 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
