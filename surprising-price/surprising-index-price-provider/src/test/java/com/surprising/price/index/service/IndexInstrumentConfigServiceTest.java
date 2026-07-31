package com.surprising.price.index.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.price.index.config.IndexPriceProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndexInstrumentConfigServiceTest {

    @Test
    void refreshUsesRepositoryAggregationSnapshot() {
        IndexPriceProperties properties = new IndexPriceProperties();
        IndexInstrumentConfigLoader loader = mock(IndexInstrumentConfigLoader.class);
        IndexPriceProperties.SymbolConfig symbol = new IndexPriceProperties.SymbolConfig();
        symbol.setSymbol("BTC-USDT");
        when(loader.load()).thenReturn(List.of(symbol));
        IndexInstrumentConfigService service = new IndexInstrumentConfigService(properties, loader);

        service.refresh();

        assertThat(service.symbols()).containsExactly(symbol);
        verify(loader).load();
    }

    @Test
    void disabledDatabaseSnapshotUsesStaticConfiguration() {
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getInstrument().setEnabled(false);
        IndexPriceProperties.SymbolConfig symbol = new IndexPriceProperties.SymbolConfig();
        symbol.setSymbol("ETH-USDT");
        properties.setSymbols(List.of(symbol));
        IndexInstrumentConfigLoader loader = mock(IndexInstrumentConfigLoader.class);
        IndexInstrumentConfigService service = new IndexInstrumentConfigService(properties, loader);

        service.refresh();

        assertThat(service.symbols()).containsExactly(symbol);
    }
}
