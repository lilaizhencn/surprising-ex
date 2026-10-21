package com.surprising.instrument.provider.service;

import com.surprising.instrument.provider.model.MarketSummaryRows.Sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class MarketSummaryServiceTest {
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
