package com.surprising.product.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InstrumentSpecKeyTest {

    @Test
    void normalizesSymbolAndIncludesProductLineAndVersion() {
        InstrumentSpecKey key = new InstrumentSpecKey(ProductLine.OPTION, " btc-usdt ", 7L);

        assertThat(key.symbol()).isEqualTo("BTC-USDT");
        assertThat(key.key()).isEqualTo("OPTION:BTC-USDT:7");
    }

    @Test
    void rejectsMissingIdentityParts() {
        assertThatThrownBy(() -> new InstrumentSpecKey(ProductLine.SPOT, "BTC-USDT", 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
