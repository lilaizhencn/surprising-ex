package com.surprising.candlestick.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SymbolRegistryServiceTest {

    @Test
    void instrumentSnapshotIsTheOnlySymbolSource() {
        CandlestickProperties properties = new CandlestickProperties();
        InstrumentSnapshotCache cache = cache(ProductLine.LINEAR_PERPETUAL,
                instrument("BTC-USDT", 2L, ContractType.LINEAR_PERPETUAL),
                instrument("ETH-USDT", 3L, ContractType.LINEAR_PERPETUAL));
        SymbolRegistryService service = service(properties, cache);

        service.refresh();

        assertThat(service.isEnabled("BTC-USDT")).isTrue();
        assertThat(service.isEnabled("ETH-USDT")).isTrue();
    }

    @Test
    void instrumentSnapshotFiltersByProductLineWhenProductTopicsAreEnabled() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        SymbolRegistryService service = service(properties,
                cache(ProductLine.LINEAR_DELIVERY,
                        instrument("BTC-USDT-20260925", 7L, ContractType.LINEAR_DELIVERY)));

        service.refresh();

        assertThat(service.isEnabled("BTC-USDT-20260925")).isTrue();
    }

    @Test
    void symbolsOutsideInstrumentSnapshotAreRejected() {
        CandlestickProperties properties = new CandlestickProperties();
        SymbolRegistryService service = service(properties, cache(ProductLine.LINEAR_PERPETUAL,
                instrument("BTC-USDT", 2L, ContractType.LINEAR_PERPETUAL)));

        service.refresh();

        assertThat(service.isEnabled("BTC-USDT")).isTrue();
        assertThat(service.isEnabled("ETH-USDT")).isFalse();
    }

    private SymbolRegistryService service(CandlestickProperties properties, InstrumentSnapshotCache cache) {
        return new SymbolRegistryService(properties, cache, null);
    }

    private InstrumentSnapshotCache cache(ProductLine productLine, InstrumentResponse... instruments) {
        InstrumentSnapshotCache cache = new InstrumentSnapshotCache();
        cache.replace(productLine, List.of(instruments), Map.of());
        return cache;
    }

    private InstrumentResponse instrument(String symbol, long version, ContractType contractType) {
        Instant now = Instant.parse("2026-07-31T00:00:00Z");
        return new InstrumentResponse(symbol, version, InstrumentType.PERPETUAL, contractType,
                "BTC", "USDT", "USDT", 1_000_000L, "BTC", 10L, 1L, 1L, 1_000_000L,
                1L, 1_000_000_000L, 1L, 2, 0, List.of("LIMIT"), List.of("GTC"), true,
                true, true, 100_000_000L, 10_000L, 5_000L, 100L, 500L,
                1_000_000_000L, 300_000L, 250_000_000L, 8, 100L, 3_000L, -3_000L,
                10_000_000L, 3, null, null, null, null, null, null, null,
                InstrumentStatus.TRADING, now, now, now, List.of(), List.of());
    }
}
