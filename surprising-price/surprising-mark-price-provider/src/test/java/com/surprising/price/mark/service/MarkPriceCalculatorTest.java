package com.surprising.price.mark.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceEncoding;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkPriceCalculatorTest {

    @Test
    void calculatesMedianMarkPriceWithClamp() {
        MarkPriceProperties properties = new MarkPriceProperties();
        properties.getCalculation().setClampRatio(new BigDecimal("0.03"));
        MarkPriceCalculator calculator = new MarkPriceCalculator(properties);
        Instant now = Instant.parse("2026-06-30T10:00:00Z");

        MarkPriceEvent event = calculator.calculate(
                "BTC-USDT",
                1,
                new IndexPriceEvent("BTC-USDT", new BigDecimal("100.00"), 1, PriceStatus.HEALTHY, 5, 5, BigDecimal.valueOf(5), now, List.of()),
                new PerpBookTickerEvent("BTC-USDT", new BigDecimal("100.00"), new BigDecimal("102.00"), 1, now),
                new PerpTradeEvent("BTC-USDT", "t1", 1, now, new BigDecimal("101.00"), BigDecimal.ONE, "BUY"),
                new PerpFundingRateEvent("BTC-USDT", BigDecimal.ZERO, now.plusSeconds(3600), 8, 1, now),
                BigDecimal.ONE,
                new MarkPriceEncoding(7L, 100_000_000L, 1_000_000L),
                now);

        assertThat(event.price1()).isEqualByComparingTo("100.000000000000000000");
        assertThat(event.price2()).isEqualByComparingTo("101.000000000000000000");
        assertThat(event.markPrice()).isEqualByComparingTo("101.000000000000000000");
        assertThat(event.markPriceUnits()).isEqualTo(10_100_000_000L);
        assertThat(event.markPriceTicks()).isEqualTo(10_100L);
        assertThat(event.instrumentVersion()).isEqualTo(7L);
        assertThat(event.eventTime()).isEqualTo(now);
        assertThat(event.publishedAt()).isEqualTo(now);
        assertThat(event.status()).isEqualTo(PriceStatus.HEALTHY);
    }

    @Test
    void nonFundingProductDoesNotRequireFundingRateForHealthyMarkPrice() {
        MarkPriceProperties properties = new MarkPriceProperties();
        properties.getKafka().setProductLine(com.surprising.product.api.ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        MarkPriceCalculator calculator = new MarkPriceCalculator(properties);
        Instant now = Instant.parse("2026-06-30T10:00:00Z");

        MarkPriceEvent event = calculator.calculate(
                "BTC-USDT-260925",
                1,
                new IndexPriceEvent("BTC-USDT-260925", new BigDecimal("100.00"), 1, PriceStatus.HEALTHY,
                        5, 5, BigDecimal.valueOf(5), now, List.of()),
                new PerpBookTickerEvent("BTC-USDT-260925", new BigDecimal("100.00"),
                        new BigDecimal("100.00"), 1, now),
                new PerpTradeEvent("BTC-USDT-260925", "t1", 1, now,
                        new BigDecimal("100.00"), BigDecimal.ONE, "BUY"),
                null,
                BigDecimal.ZERO,
                new MarkPriceEncoding(3L, 100_000_000L, 1_000_000L),
                now);

        assertThat(event.status()).isEqualTo(PriceStatus.HEALTHY);
        assertThat(event.fundingRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
