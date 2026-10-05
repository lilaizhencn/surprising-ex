package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentSnapshotResponse;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InstrumentSnapshotInitializerTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void emptyProductLineCanStartBeforeItsFirstAdminListing(ProductLine line) {
        var service = mock(InstrumentService.class);
        var cache = new InstrumentSnapshotCache();
        var properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(line);
        when(service.snapshot(line)).thenReturn(new InstrumentSnapshotResponse(
                line, 0, "", List.of(), Map.of("USDT", 100_000_000L)));

        new InstrumentSnapshotInitializer(service, cache, properties).initialize();

        assertThat(cache.initialized(line)).isTrue();
        assertThat(cache.current(line)).isEmpty();
        assertThat(cache.current(line, 1)).isEmpty();
        assertThat(cache.scale(line, "USDT")).contains(100_000_000L);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void wrongProductSnapshotStillFailsStartup(ProductLine line) {
        var service = mock(InstrumentService.class);
        var cache = new InstrumentSnapshotCache();
        var properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(line);
        var other = line == ProductLine.SPOT ? ProductLine.OPTION : ProductLine.SPOT;
        when(service.snapshot(line)).thenReturn(new InstrumentSnapshotResponse(other, 0, "", List.of()));

        assertThatThrownBy(() -> new InstrumentSnapshotInitializer(service, cache, properties).initialize())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("产品线不匹配");
        assertThat(cache.initialized(line)).isFalse();
    }
}
