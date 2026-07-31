package com.surprising.price.index.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.IndexSourceConfig;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndexInstrumentConfigLoaderTest {

    @Test
    void snapshotSelectsPerpetualInstrumentsAndAggregatesCurrentSources() {
        IndexPriceProperties properties = new IndexPriceProperties();
        InstrumentSnapshotCache cache = new InstrumentSnapshotCache();
        cache.replace(ProductLine.LINEAR_PERPETUAL, List.of(instrument("BTC-USDT", 7L, true),
                instrument("ETH-USDT", 3L, false)));
        IndexInstrumentConfigLoader loader = new IndexInstrumentConfigLoader(properties, cache);

        List<IndexPriceProperties.SymbolConfig> loaded = loader.load();

        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst().getSymbol()).isEqualTo("BTC-USDT");
        assertThat(loaded.getFirst().getMinValidSources()).isEqualTo(2);
        assertThat(loaded.getFirst().getSources()).hasSize(1);
        assertThat(loaded.getFirst().getSources().getFirst().getWeight())
                .isEqualByComparingTo("1.000000");
    }

    @Test
    void productSnapshotSelectsConfiguredContractType() {
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        InstrumentSnapshotCache cache = new InstrumentSnapshotCache();
        cache.replace(ProductLine.LINEAR_DELIVERY, List.of());
        IndexInstrumentConfigLoader loader = new IndexInstrumentConfigLoader(properties, cache);

        assertThat(loader.load()).isEmpty();

    }

    private IndexSourceConfig source() {
        return new IndexSourceConfig(
                "BINANCE",
                true,
                "https://api.binance.com",
                "/ticker",
                "BTCUSDT",
                "BINANCE",
                "USDT",
                "USDT",
                null,
                null,
                null,
                "DISCOUNT",
                "MULTIPLY",
                500_000L,
                true,
                "wss://stream.binance.com",
                "{}",
                "BINANCE",
                1_000_000L);
    }

    private InstrumentResponse instrument(String symbol, long version, boolean withSource) {
        Instant now = Instant.parse("2026-07-31T00:00:00Z");
        return new InstrumentResponse(symbol, version, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL,
                "BTC", "USDT", "USDT", 1_000_000L, "BTC", 10L, 1L, 1L, 1_000_000L,
                1L, 1_000_000_000L, 1L, 2, 0, List.of("LIMIT"), List.of("GTC"), true,
                true, true, 100_000_000L, 10_000L, 5_000L, 100L, 500L,
                1_000_000_000L, 300_000L, 250_000_000L, 8, 100L, 3_000L, -3_000L,
                10_000_000L, withSource ? 2 : 3, null, null, null, null, null, null, null,
                InstrumentStatus.TRADING, now, now, now, List.of(), withSource ? List.of(source()) : List.of());
    }
}
