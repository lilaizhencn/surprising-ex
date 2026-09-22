package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.cache.FeeScheduleSnapshotCache;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleSourceType;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.OrderFeeRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeeScheduleSnapshotInitializerTest {

    @Test
    void importsTheDatabaseSnapshotIntoCoreBeforePublishingTheLocalCache() {
        TradingOrderProperties properties = properties();
        OrderFeeRepository repository = mock(OrderFeeRepository.class);
        FeeScheduleSnapshotCache cache = new FeeScheduleSnapshotCache();
        FeePolicyCoreImporter importer = mock(FeePolicyCoreImporter.class);
        FeeScheduleResponse policy = policy();
        when(repository.loadSnapshotSchedules(ProductLine.LINEAR_PERPETUAL)).thenReturn(List.of(policy));

        new FeeScheduleSnapshotInitializer(properties, repository, cache, importer).initialize();

        verify(importer).importPolicy(policy);
        assertThat(cache.schedules(ProductLine.LINEAR_PERPETUAL)).containsExactly(policy);
    }

    @Test
    void failsStartupWhenTheAuthoritativeFeeSnapshotCannotBeRestored() {
        TradingOrderProperties properties = properties();
        OrderFeeRepository repository = mock(OrderFeeRepository.class);
        FeeScheduleSnapshotCache cache = new FeeScheduleSnapshotCache();
        FeePolicyCoreImporter importer = mock(FeePolicyCoreImporter.class);
        when(repository.loadSnapshotSchedules(ProductLine.LINEAR_PERPETUAL))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> new FeeScheduleSnapshotInitializer(
                properties, repository, cache, importer).initialize())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        assertThat(cache.initialized(ProductLine.LINEAR_PERPETUAL)).isFalse();
        verifyNoInteractions(importer);
    }

    private static TradingOrderProperties properties() {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        return properties;
    }

    private static FeeScheduleResponse policy() {
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        return new FeeScheduleResponse(71, ProductLine.LINEAR_PERPETUAL, 1001, "BTC-USDT", -25, 75,
                FeeScheduleSourceType.VIP, "VIP3", "tier", FeeScheduleStatus.ACTIVE,
                now.minusSeconds(60), null, now.minusSeconds(120), now);
    }
}
