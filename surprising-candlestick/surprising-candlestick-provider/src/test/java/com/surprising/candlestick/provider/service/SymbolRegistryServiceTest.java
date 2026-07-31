package com.surprising.candlestick.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.candlestick.provider.repository.CandlestickInstrumentCurrentVersionRepository;
import com.surprising.candlestick.provider.repository.CandlestickInstrumentRepository;
import com.surprising.candlestick.provider.repository.CandlestickInstrumentRepository.InstrumentVersion;
import com.surprising.candlestick.provider.repository.CandlestickSymbolRepository;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SymbolRegistryServiceTest {

    private final CandlestickInstrumentRepository instrumentRepository =
            mock(CandlestickInstrumentRepository.class);
    private final CandlestickInstrumentCurrentVersionRepository currentVersionRepository =
            mock(CandlestickInstrumentCurrentVersionRepository.class);
    private final CandlestickSymbolRepository symbolRepository = mock(CandlestickSymbolRepository.class);

    @Test
    void strictInstrumentRegistryUsesCurrentPerpetualVersionByDefault() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.getSymbols().setAcceptUnknownSymbols(false);
        when(instrumentRepository.findEnabledPerpetualVersions()).thenReturn(List.of(
                new InstrumentVersion("BTC-USDT", 1L),
                new InstrumentVersion("BTC-USDT", 2L),
                new InstrumentVersion("ETH-USDT", 3L)));
        when(currentVersionRepository.findAll()).thenReturn(Map.of(
                "BTC-USDT", 2L,
                "ETH-USDT", 4L));
        SymbolRegistryService service = service(properties);

        service.refresh();

        assertThat(service.isEnabled("BTC-USDT")).isTrue();
        assertThat(service.isEnabled("ETH-USDT")).isFalse();
        verify(instrumentRepository).findEnabledPerpetualVersions();
        verifyNoInteractions(symbolRepository);
    }

    @Test
    void strictInstrumentRegistryFiltersByProductLineWhenProductTopicsAreEnabled() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.getSymbols().setAcceptUnknownSymbols(false);
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        when(instrumentRepository.findEnabledVersionsByContractType("LINEAR_DELIVERY"))
                .thenReturn(List.of(new InstrumentVersion("BTC-USDT-20260925", 7L)));
        when(currentVersionRepository.findAll()).thenReturn(Map.of("BTC-USDT-20260925", 7L));
        SymbolRegistryService service = service(properties);

        service.refresh();

        assertThat(service.isEnabled("BTC-USDT-20260925")).isTrue();
        verify(instrumentRepository).findEnabledVersionsByContractType("LINEAR_DELIVERY");
    }

    @Test
    void compatibilityRegistryUsesCandlestickSymbolsTableOnly() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.getSymbols().setAcceptUnknownSymbols(false);
        properties.getSymbols().setSource("CANDLESTICK_SYMBOLS");
        when(symbolRepository.findEnabledSymbols()).thenReturn(Set.of("btc-usdt"));
        SymbolRegistryService service = service(properties);

        service.refresh();

        assertThat(service.isEnabled("BTC-USDT")).isTrue();
        verify(symbolRepository).findEnabledSymbols();
        verifyNoInteractions(instrumentRepository, currentVersionRepository);
    }

    private SymbolRegistryService service(CandlestickProperties properties) {
        return new SymbolRegistryService(
                properties,
                instrumentRepository,
                currentVersionRepository,
                symbolRepository);
    }
}
