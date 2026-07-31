package com.surprising.price.index.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.repository.IndexInstrumentCurrentVersionRepository;
import com.surprising.price.index.repository.IndexInstrumentKey;
import com.surprising.price.index.repository.IndexInstrumentRepository;
import com.surprising.price.index.repository.IndexInstrumentRepository.IndexInstrument;
import com.surprising.price.index.repository.IndexInstrumentSourceRepository;
import com.surprising.price.index.repository.IndexInstrumentSourceRepository.IndexSource;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndexInstrumentConfigLoaderTest {

    @Test
    void legacySnapshotSelectsPerpetualInstrumentsAndAggregatesCurrentSources() {
        IndexPriceProperties properties = new IndexPriceProperties();
        IndexInstrumentRepository instruments = mock(IndexInstrumentRepository.class);
        IndexInstrumentCurrentVersionRepository versions = mock(IndexInstrumentCurrentVersionRepository.class);
        IndexInstrumentSourceRepository sources = mock(IndexInstrumentSourceRepository.class);
        IndexInstrument current = new IndexInstrument("BTC-USDT", 7L, 2);
        IndexInstrument stale = new IndexInstrument("ETH-USDT", 3L, 3);
        when(instruments.findTradingVersions(null)).thenReturn(List.of(current, stale));
        when(versions.findAll()).thenReturn(Map.of("BTC-USDT", 7L, "ETH-USDT", 4L));
        when(sources.findEnabled(List.of(current.key())))
                .thenReturn(Map.of(current.key(), List.of(source())));
        IndexInstrumentConfigLoader loader = new IndexInstrumentConfigLoader(
                properties, instruments, versions, sources);

        List<IndexPriceProperties.SymbolConfig> loaded = loader.load();

        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst().getSymbol()).isEqualTo("BTC-USDT");
        assertThat(loaded.getFirst().getMinValidSources()).isEqualTo(2);
        assertThat(loaded.getFirst().getSources()).hasSize(1);
        assertThat(loaded.getFirst().getSources().getFirst().getWeight())
                .isEqualByComparingTo("1.000000");
        verify(instruments).findTradingVersions(null);
    }

    @Test
    void productSnapshotSelectsConfiguredContractType() {
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        IndexInstrumentRepository instruments = mock(IndexInstrumentRepository.class);
        IndexInstrumentCurrentVersionRepository versions = mock(IndexInstrumentCurrentVersionRepository.class);
        IndexInstrumentSourceRepository sources = mock(IndexInstrumentSourceRepository.class);
        when(instruments.findTradingVersions("LINEAR_DELIVERY")).thenReturn(List.of());
        when(versions.findAll()).thenReturn(Map.of());
        IndexInstrumentConfigLoader loader = new IndexInstrumentConfigLoader(
                properties, instruments, versions, sources);

        assertThat(loader.load()).isEmpty();

        verify(instruments).findTradingVersions("LINEAR_DELIVERY");
    }

    private IndexSource source() {
        return new IndexSource(
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
}
