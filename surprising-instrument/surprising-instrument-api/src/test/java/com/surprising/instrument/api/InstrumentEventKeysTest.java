package com.surprising.instrument.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentEventType;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InstrumentEventKeysTest {

    @Test
    void acceptsOnlyTheCanonicalProductLineKey() {
        InstrumentEvent event = new InstrumentEvent("btc-usdt", 3L, InstrumentStatus.TRADING,
                InstrumentEventType.UPSERTED, Instant.EPOCH, null, ProductLine.SPOT, 3L);

        assertThat(InstrumentEventKeys.key(event)).isEqualTo("SPOT:BTC-USDT");
        assertThat(InstrumentEventKeys.matches("SPOT:BTC-USDT", event)).isTrue();
        assertThat(InstrumentEventKeys.matches("BTC-USDT", event)).isFalse();
    }

    @Test
    void rejectsEventsWithoutProductLineOrSequence() {
        assertThatThrownBy(() -> new InstrumentEvent("BTC-USDT", 3L, InstrumentStatus.TRADING,
                InstrumentEventType.UPSERTED, Instant.EPOCH, null, null, 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstrumentEvent("BTC-USDT", 3L, InstrumentStatus.TRADING,
                InstrumentEventType.UPSERTED, Instant.EPOCH, null, ProductLine.SPOT, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
