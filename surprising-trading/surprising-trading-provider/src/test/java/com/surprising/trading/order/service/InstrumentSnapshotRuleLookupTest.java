package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InstrumentSnapshotRuleLookupTest {

    @Test
    void readsCurrentRuleFromTheProductLineSnapshot() {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        InstrumentSnapshotCache cache = new InstrumentSnapshotCache();
        cache.replace(ProductLine.INVERSE_DELIVERY, List.of(instrument()));

        InstrumentSnapshotRuleLookup lookup = new InstrumentSnapshotRuleLookup(properties, cache);

        assertThat(lookup.currentRule("BTC-USD-240927")).isPresent()
                .get().extracting("symbol").isEqualTo("BTC-USD-240927");
    }

    private InstrumentResponse instrument() {
        Instant now = Instant.parse("2026-07-31T00:00:00Z");
        return new InstrumentResponse("BTC-USD-240927", 7L, InstrumentType.PERPETUAL,
                ContractType.INVERSE_DELIVERY, "BTC", "USD", "USD", 1_000_000L, "BTC",
                10L, 1L, 1L, 1_000_000L, 1L, 1_000_000_000L, 1L, 2, 0,
                List.of("LIMIT"), List.of("GTC"), true, true, true, 100_000_000L,
                10_000L, 5_000L, 100L, 500L, 1_000_000_000L, 300_000L, 250_000_000L,
                8, 100L, 3_000L, -3_000L, 10_000_000L, 3, null, null, null, null,
                null, null, null, InstrumentStatus.TRADING, now, now, now, List.of(), List.of());
    }
}
