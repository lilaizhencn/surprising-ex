package com.surprising.candlestick.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.candlestick.api.model.CandleResponse;
import com.surprising.candlestick.api.model.CandleStatus;
import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.candlestick.provider.repository.CandleQueryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandleQueryServiceTest {
    @Test
    void normalizesHigherPeriodAndDelegatesBoundedRangeToRepository() {
        CandleQueryRepository repository = mock(CandleQueryRepository.class);
        CandlestickProperties properties = new CandlestickProperties();
        CandleQueryService service = new CandleQueryService(repository, properties);
        Instant start = Instant.parse("2025-08-25T10:00:00Z");
        Instant end = Instant.parse("2025-08-25T11:00:00Z");
        CandleResponse candle = new CandleResponse("BTC-USDT", "5m", start, start.plusSeconds(300),
                BigDecimal.ONE, BigDecimal.TWO, BigDecimal.ONE, BigDecimal.TWO,
                BigDecimal.ONE, BigDecimal.TWO, 1, "a", "a", 1L, 1L,
                CandleStatus.CLOSED, start.plusSeconds(300));
        when(repository.findRange("BTC-USDT", "5m", start, end, 100)).thenReturn(List.of(candle));

        var response = service.query(" btc-usdt ", "M5", start, end, 100);

        assertThat(response.candles()).containsExactly(candle);
        verify(repository).findRange("BTC-USDT", "5m", start, end, 100);
    }
}
