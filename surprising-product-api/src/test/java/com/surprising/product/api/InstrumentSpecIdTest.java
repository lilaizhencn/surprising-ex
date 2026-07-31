package com.surprising.product.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InstrumentSpecIdTest {

    @Test
    void normalizesSymbolAndKeepsProductLineInKey() {
        InstrumentSpecId id = new InstrumentSpecId(ProductLine.OPTION, " btc-usdt ", 7L);

        assertThat(id.symbol()).isEqualTo("BTC-USDT");
        assertThat(id.key()).isEqualTo("OPTION:BTC-USDT:7");
    }

    @Test
    void rejectsMissingIdentityParts() {
        assertThatThrownBy(() -> new InstrumentSpecId(ProductLine.SPOT, "BTC-USDT", 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
