package com.surprising.risk.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.price.consumer.MarkPriceConsumerProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CachedRiskGroup;
import com.surprising.risk.provider.model.CachedRiskPosition;
import com.surprising.risk.provider.model.RiskGroupKey;
import com.surprising.risk.provider.model.RiskInstrumentSpec;
import com.surprising.risk.provider.model.RiskMaintenanceBracket;
import com.surprising.risk.provider.repository.RiskRepository;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RedisRiskCalculatorTest {

    @Test
    void calculatesFreshRedisGroupWithVersionedSpecAndTieredMaintenanceRate() {
        RiskProperties properties = new RiskProperties();
        LatestMarkPriceCache marks = new LatestMarkPriceCache(new MarkPriceConsumerProperties());
        RiskRepository repository = mock(RiskRepository.class);
        RiskInstrumentSpec spec = new RiskInstrumentSpec("BTC-USDT", 7L, ContractType.LINEAR_PERPETUAL,
                "USDT", 100L, 10L, 1_000_000L, 5_000L,
                List.of(new RiskMaintenanceBracket(50_000L, 10_000L)));
        when(repository.riskInstrumentSpec("BTC-USDT", 7L)).thenReturn(Optional.of(spec));
        marks.update(mark(600L));
        RedisRiskCalculator calculator = new RedisRiskCalculator(marks, repository, properties);
        CachedRiskGroup group = new CachedRiskGroup(new RiskGroupKey(1001L, "USDT"), 1_000_000L,
                List.of(new CachedRiskPosition("BTC-USDT", MarginMode.CROSS, PositionSide.NET, 7L, "USDT",
                        10L, 500L, 0L)), Instant.now());

        var result = calculator.calculate(group);

        assertThat(result).singleElement().satisfies(position -> {
            assertThat(position.markPriceTicks()).isEqualTo(600L);
            assertThat(position.notionalUnits()).isEqualTo(600_000L);
            assertThat(position.unrealizedPnlUnits()).isEqualTo(100_000L);
            assertThat(position.maintenanceMarginUnits()).isEqualTo(6_000L);
        });
    }

    private MarkPriceEvent mark(long ticks) {
        Instant now = Instant.now();
        BigDecimal price = BigDecimal.valueOf(ticks);
        return new MarkPriceEvent(ProductLine.LINEAR_PERPETUAL, "BTC-USDT", 7L,
                ticks, ticks, price, price, price, price, price, price, price, BigDecimal.ZERO,
                now.plusSeconds(3600), 3600L, BigDecimal.ZERO, 60L, price, price, 1L,
                PriceStatus.HEALTHY, now, now);
    }
}
