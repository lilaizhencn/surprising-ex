package com.surprising.trading.matching.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.service.MatchingSymbolService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatchingSymbolServiceTest {

    @Test
    void leavesTradingSymbolLookupUnfilteredForLegacyTopics() {
        MatchingProperties properties = new MatchingProperties();
        InstrumentSnapshotCache cache = cache(ProductLine.LINEAR_PERPETUAL,
                instrument("BTC-USDT", 1L), instrument("BTC-USDT", 2L));
        MatchingSymbolService service = service(properties, cache);

        assertThat(service.currentTradingSymbols())
                .extracting(com.surprising.trading.matching.model.InstrumentSymbol::symbol)
                .containsExactly("BTC-USDT");
    }

    @Test
    void filtersTradingSymbolsByProductLineWhenProductTopicsAreEnabled() {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        MatchingSymbolService service = service(properties, cache(ProductLine.LINEAR_DELIVERY,
                instrument("BTC-USDT-240927", 7L, ContractType.LINEAR_DELIVERY)));

        assertThat(service.currentTradingSymbols()).hasSize(1);
    }

    @Test
    void filtersSingleSymbolLookupByProductLineWhenProductTopicsAreEnabled() {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        MatchingSymbolService service = service(properties,
                cache(ProductLine.INVERSE_DELIVERY,
                        instrument("BTC-USD-240927", 7L, ContractType.INVERSE_DELIVERY)));

        assertThat(service.currentTradingSymbol("BTC-USD-240927")).isPresent();
    }

    @Test
    void derivesStableMatchingIdsFromInstrumentSnapshot() {
        MatchingProperties properties = new MatchingProperties();
        InstrumentSnapshotCache cache = cache(ProductLine.LINEAR_PERPETUAL,
                instrument("BTC-USDT", 1L));
        MatchingSymbolService service = service(properties, cache);
        MatchingSymbolService restartedService = service(properties, cache);

        var first = service.ensureMatchingSymbol(new com.surprising.trading.matching.model.InstrumentSymbol(
                "BTC-USDT", "BTC", "USDT", "USDT"));
        var second = restartedService.ensureMatchingSymbol(new com.surprising.trading.matching.model.InstrumentSymbol(
                "BTC-USDT", "BTC", "USDT", "USDT"));

        assertThat(second).isEqualTo(first);
        assertThat(service.existingMatchingSymbol("BTC-USDT")).contains(first);
    }

    private MatchingSymbolService service(MatchingProperties properties, InstrumentSnapshotCache cache) {
        return new MatchingSymbolService(properties, cache);
    }

    private InstrumentSnapshotCache cache(ProductLine productLine, InstrumentResponse... instruments) {
        InstrumentSnapshotCache cache = new InstrumentSnapshotCache();
        cache.replace(productLine, List.of(instruments));
        return cache;
    }

    private InstrumentResponse instrument(String symbol, long version) {
        return instrument(symbol, version, ContractType.LINEAR_PERPETUAL);
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
