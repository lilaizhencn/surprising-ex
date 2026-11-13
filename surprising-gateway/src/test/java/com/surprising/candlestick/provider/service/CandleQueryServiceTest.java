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
        CandleResponse candle = new CandleResponse("1", "5m", start, start.plusSeconds(300),
                BigDecimal.ONE, BigDecimal.TWO, BigDecimal.ONE, BigDecimal.TWO,
                BigDecimal.ONE, BigDecimal.TWO, 1, "a", "a", 1L, 1L,
                CandleStatus.CLOSED, start.plusSeconds(300));
        when(repository.findRange("1", "5m", start, end, 100)).thenReturn(List.of(candle));

        var response = service.query("1", "M5", start, end, 100);

        assertThat(response.candles()).hasSize(12);
        assertThat(response.candles().getFirst()).isEqualTo(candle);
        assertThat(response.candles().get(1).openTime()).isEqualTo(start.plusSeconds(300));
        assertThat(response.candles().get(1).openPrice()).isEqualTo(BigDecimal.TWO);
        assertThat(response.candles().get(1).baseVolume()).isEqualTo(BigDecimal.ZERO);
        assertThat(response.candles().get(1).tradeCount()).isZero();
        verify(repository).findRange("1", "5m", start, end, 100);
    }

    @Test
    void quietWindowUsesLastRealCloseWithoutInventingExecutions() {
        CandleQueryRepository repository = mock(CandleQueryRepository.class);
        var service = new CandleQueryService(repository, new CandlestickProperties());
        Instant start = Instant.parse("2025-08-25T10:00:00Z"), end = start.plusSeconds(900);
        var prior = candle(start.minusSeconds(172800), "100", 9);
        when(repository.findRange("1", "5m", start, end, 3)).thenReturn(List.of());
        when(repository.findBefore("1", start)).thenReturn(java.util.Optional.of(prior));
        var rows = service.query("1", "5m", start, end, 3).candles();
        assertThat(rows).hasSize(3);
        assertThat(rows.getFirst().openTime()).isEqualTo(start);
        assertThat(rows.getLast().closeTime()).isEqualTo(end);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.openPrice()).isEqualByComparingTo("100");
            assertThat(row.closePrice()).isEqualByComparingTo("100");
            assertThat(row.baseVolume()).isZero();
            assertThat(row.quoteVolume()).isZero();
            assertThat(row.tradeCount()).isZero();
            assertThat(row.firstTradeId()).isNull();
            assertThat(row.lastSequence()).isNull();
        });
    }

    @Test
    void leadingEmptyPeriodsKeepPriorCloseUntilActualTrade() {
        CandleQueryRepository repository = mock(CandleQueryRepository.class);
        var service = new CandleQueryService(repository, new CandlestickProperties());
        Instant start = Instant.parse("2025-08-25T10:00:00Z"), end = start.plusSeconds(900);
        var actual = candle(start.plusSeconds(600), "105", 2);
        when(repository.findRange("1", "5m", start, end, 3)).thenReturn(List.of(actual));
        when(repository.findBefore("1", start)).thenReturn(java.util.Optional.of(candle(start.minusSeconds(900), "100", 9)));
        var rows = service.query("1", "5m", start, end, 3).candles();
        assertThat(rows).hasSize(3);
        assertThat(rows.getFirst().closePrice()).isEqualByComparingTo("100");
        assertThat(rows.get(1).tradeCount()).isZero();
        assertThat(rows.getLast()).isEqualTo(actual);
    }

    @Test
    void neverTradedInstrumentStaysEmpty() {
        CandleQueryRepository repository = mock(CandleQueryRepository.class);
        var service = new CandleQueryService(repository, new CandlestickProperties());
        Instant start = Instant.parse("2025-08-25T10:00:00Z");
        assertThat(service.query("1", "5m", start, start.plusSeconds(900), 3).candles()).isEmpty();
    }

    private CandleResponse candle(Instant time, String value, long count) {
        var price = new BigDecimal(value);
        return new CandleResponse("1", "5m", time, time.plusSeconds(300), price, price, price, price,
                BigDecimal.ONE, price, count, "real", "real", 1L, 1L, CandleStatus.CLOSED, time.plusSeconds(300));
    }
}
