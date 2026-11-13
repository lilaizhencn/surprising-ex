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
        IndexPriceProperties.SymbolConfig instrumentId = new IndexPriceProperties.SymbolConfig();
        instrumentId.setInstrumentId("1");
        when(loader.load()).thenReturn(List.of(instrumentId));
        IndexInstrumentConfigService service = new IndexInstrumentConfigService(loader);

        service.refresh();

        assertThat(service.symbols()).containsExactly(instrumentId);
        verify(loader).load();
    }

    @Test
    void refreshUsesEmptySnapshotWhenNoInstrumentMatches() {
        IndexPriceProperties properties = new IndexPriceProperties();
        IndexInstrumentConfigLoader loader = mock(IndexInstrumentConfigLoader.class);
        when(loader.load()).thenReturn(List.of());
        IndexInstrumentConfigService service = new IndexInstrumentConfigService(loader);

        service.refresh();

        assertThat(service.symbols()).isEmpty();
    }
}
