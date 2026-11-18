package com.surprising.instrument.provider.service;

import com.surprising.instrument.provider.model.MarketSummaryRows.Sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class MarketSummaryServiceTest {
    @Test void sharesFiveSecondResultsAndSeparatesProductsAndCatalogMembership() throws Exception {
        var repository = org.mockito.Mockito.mock(com.surprising.instrument.provider.repository.MarketSummaryRepository.class);
        var price = BigDecimal.TEN;
        org.mockito.Mockito.when(repository.samples(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(new Sample(7, price, price, price, price, BigDecimal.ONE, price)));
        var service = new MarketSummaryService(repository);
        var now = java.time.Instant.parse("2026-11-18T00:00:00Z");
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<java.util.Map<Integer, com.surprising.instrument.provider.model.MarketSummaryRows.Summary>>>();
            for (int i=0;i<16;i++) results.add(executor.submit(() -> service.summaries(com.surprising.product.api.ProductLine.SPOT, java.util.List.of(7), now)));
            var first = results.getFirst().get();
            for (var result : results) assertThat(result.get()).isSameAs(first);
        }
        service.summaries(com.surprising.product.api.ProductLine.SPOT, java.util.List.of(7), now.plusSeconds(4));
        org.mockito.Mockito.verify(repository).samples(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        service.summaries(com.surprising.product.api.ProductLine.LINEAR_PERPETUAL, java.util.List.of(7), now);
        service.summaries(com.surprising.product.api.ProductLine.SPOT, java.util.List.of(7), now.plusSeconds(5));
        service.summaries(com.surprising.product.api.ProductLine.SPOT, java.util.List.of(7,8), now.plusSeconds(5));
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(4)).samples(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test void queryErrorsAreNotConvertedIntoCachedZeroMarketData() {
        var repository = org.mockito.Mockito.mock(com.surprising.instrument.provider.repository.MarketSummaryRepository.class);
        org.mockito.Mockito.when(repository.samples(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("offline")).thenReturn(java.util.List.of());
        var service = new MarketSummaryService(repository);
        var now = java.time.Instant.EPOCH;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.summaries(com.surprising.product.api.ProductLine.SPOT, java.util.List.of(7), now))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.summaries(com.surprising.product.api.ProductLine.SPOT, java.util.List.of(7), now)).isEmpty();
    }

    @Test
    void summarizesRollingPricesAndBoundsTrendPayload() {
        var candles = new ArrayList<Sample>();
        for (int i = 0; i < 100; i++) {
            var price = BigDecimal.valueOf(100 + i);
            candles.add(new Sample(7, price, price.add(BigDecimal.ONE),
                    price.subtract(BigDecimal.ONE), price, BigDecimal.valueOf(2),
                    BigDecimal.valueOf(200 + i)));
        }

        var summary = MarketSummaryService.summarize(candles);

        assertThat(summary.lastPrice()).isEqualByComparingTo("199");
        assertThat(summary.change24h()).isEqualByComparingTo("99");
        assertThat(summary.high24h()).isEqualByComparingTo("200");
        assertThat(summary.low24h()).isEqualByComparingTo("99");
        assertThat(summary.volume24h()).isEqualByComparingTo("200");
        assertThat(summary.quoteVolume24h()).isEqualByComparingTo("24950");
        assertThat(summary.trend()).hasSize(32);
        assertThat(summary.trend().getFirst()).isEqualByComparingTo("100");
        assertThat(summary.trend().getLast()).isEqualByComparingTo("199");
    }

    @Test
    void quietDayKeepsRealCloseAndZeroVolume() {
        var price = new BigDecimal("86256.8");
        var summary = MarketSummaryService.summarize(java.util.List.of(
                new Sample(604, price, price, price, price, BigDecimal.ZERO, BigDecimal.ZERO)));
        assertThat(summary.lastPrice()).isEqualByComparingTo(price);
        assertThat(summary.change24h()).isZero();
        assertThat(summary.volume24h()).isZero();
        assertThat(summary.quoteVolume24h()).isZero();
        assertThat(summary.trend()).containsExactly(price, price);
    }

    @Test
    void firstTradeAfterQuietPeriodUsesOpeningCloseButOnlyNewTradeVolume() {
        var prior = new BigDecimal("100");
        var current = new BigDecimal("105");
        var summary = MarketSummaryService.summarize(java.util.List.of(
                new Sample(604, prior, prior, prior, prior, BigDecimal.ZERO, BigDecimal.ZERO),
                new Sample(604, current, current, current, current, BigDecimal.TWO, new BigDecimal("210"))));
        assertThat(summary.change24h()).isEqualByComparingTo("5");
        assertThat(summary.volume24h()).isEqualByComparingTo("2");
        assertThat(summary.quoteVolume24h()).isEqualByComparingTo("210");
    }
}
