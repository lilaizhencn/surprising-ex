package com.surprising.candlestick.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.candlestick.provider.repository.CandlestickAssetScaleRepository;
import com.surprising.candlestick.provider.repository.CandlestickInstrumentRepository;
import com.surprising.candlestick.provider.repository.CandlestickInstrumentRepository.InstrumentDefinition;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PublicTradeEvent;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PublicTradeEventMapperTest {

    @Test
    void mapsTradeWithInstrumentAndAssetScaleRepositoriesAndCachesVersion() {
        CandlestickInstrumentRepository instrumentRepository = mock(CandlestickInstrumentRepository.class);
        CandlestickAssetScaleRepository assetScaleRepository = mock(CandlestickAssetScaleRepository.class);
        when(instrumentRepository.find("BTC-USDT", 7L)).thenReturn(Optional.of(new InstrumentDefinition(
                "BTC-USDT", 7L, "BTC", "USDT", 10L, 1_000L)));
        when(assetScaleRepository.findScaleUnits("BTC")).thenReturn(Optional.of(100_000_000L));
        when(assetScaleRepository.findScaleUnits("USDT")).thenReturn(Optional.of(1_000_000L));
        PublicTradeEventMapper mapper = new PublicTradeEventMapper(instrumentRepository, assetScaleRepository);
        PublicTradeEvent trade = new PublicTradeEvent(
                "trade:1",
                1L,
                "BTC-USDT",
                7L,
                OrderSide.BUY,
                6_000_000L,
                250_000L,
                Instant.parse("2026-07-31T00:00:00Z"),
                "trace:1");

        var first = mapper.toTradeEvent(trade);
        var second = mapper.toTradeEvent(trade);

        assertThat(first.price()).isEqualByComparingTo("60");
        assertThat(first.quantity()).isEqualByComparingTo("2.5");
        assertThat(second).isEqualTo(first);
        verify(instrumentRepository, times(1)).find("BTC-USDT", 7L);
        verify(assetScaleRepository, times(1)).findScaleUnits("BTC");
        verify(assetScaleRepository, times(1)).findScaleUnits("USDT");
    }
}
