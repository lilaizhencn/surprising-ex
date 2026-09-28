package com.surprising.instrument.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class MarketSummaryRepositoryTest {
    @Test
    void summarizesRollingPricesAndBoundsTrendPayload() {
        var candles = new ArrayList<MarketSummaryRepository.Sample>();
        for (int i = 0; i < 100; i++) {
            var price = BigDecimal.valueOf(100 + i);
            candles.add(new MarketSummaryRepository.Sample(7, price, price.add(BigDecimal.ONE),
                    price.subtract(BigDecimal.ONE), price, BigDecimal.valueOf(2),
                    BigDecimal.valueOf(200 + i)));
        }

        var summary = MarketSummaryRepository.summarize(candles);

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
}
