package com.surprising.price.index.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.api.model.SourceStatus;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.model.SourceQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndexPriceCalculatorTest {

    @Test
    void excludesOutlierAndRenormalizesWeights() {
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getCalculation().setMinValidSources(3);
        properties.getCalculation().setOutlierThreshold(new BigDecimal("0.01"));
        IndexPriceCalculator calculator = new IndexPriceCalculator(properties);
        Instant now = Instant.parse("2026-06-30T10:00:00Z");

        IndexPriceEvent event = calculator.calculate("BTC-USDT", 1, 3, List.of(
                quote("A", "100.00", now),
                quote("B", "100.10", now),
                quote("C", "99.90", now),
                quote("D", "130.00", now)
        ), now);

        assertThat(event.status()).isEqualTo(PriceStatus.DEGRADED);
        assertThat(event.validComponentCount()).isEqualTo(3);
        assertThat(event.indexPrice()).isBetween(new BigDecimal("99.999999999999999000"), new BigDecimal("100.000000000000001000"));
        assertThat(event.components()).filteredOn(component -> component.source().equals("D"))
                .first()
                .extracting(component -> component.status())
                .isEqualTo(SourceStatus.OUTLIER);
    }

    @Test
    void returnsInsufficientSourcesWithoutIndexPriceWhenTooFewSourcesRemainHealthy() {
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getCalculation().setMinValidSources(3);
        IndexPriceCalculator calculator = new IndexPriceCalculator(properties);
        Instant now = Instant.parse("2026-06-30T10:00:00Z");

        IndexPriceEvent event = calculator.calculate("BTC-USDT", 2, 3, List.of(
                quote("A", "100.00", now),
                quote("B", "100.10", now.minusSeconds(90)),
                quote("C", "99.90", now.minusSeconds(90))
        ), now);

        assertThat(event.status()).isEqualTo(PriceStatus.INSUFFICIENT_SOURCES);
        assertThat(event.indexPrice()).isNull();
        assertThat(event.validComponentCount()).isEqualTo(1);
        assertThat(event.components()).filteredOn(component -> component.status() == SourceStatus.STALE)
                .hasSize(2);
    }

    private SourceQuote quote(String source, String price, Instant now) {
        return new SourceQuote(source, "BTCUSDT", new BigDecimal(price), new BigDecimal(price), new BigDecimal(price),
                BigDecimal.ONE, SourceStatus.HEALTHY, null, now, now, 1L);
    }
}
