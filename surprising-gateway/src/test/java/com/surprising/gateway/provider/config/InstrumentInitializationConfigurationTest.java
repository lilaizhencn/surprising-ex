package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.*;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import tools.jackson.databind.ObjectMapper;

class InstrumentInitializationConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = {"com.surprising.account.provider", "com.surprising.trading.order"},
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = ".*\\.InstrumentSnapshot(Configuration|Initializer|Consumer)"))
    static class SnapshotComponents { }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void onlyOrderSnapshotLoadsAndPreservesProductAndScales(ProductLine line) {
        var instruments = mock(InstrumentService.class);
        var row = mock(InstrumentResponse.class);
        when(row.symbol()).thenReturn("BTC-USDT");
        when(row.contractType()).thenReturn(Arrays.stream(ContractType.values())
                .filter(type -> type.productLine() == line).findFirst().orElseThrow());
        when(row.changeId()).thenReturn(1L);
        when(row.lastChangeId()).thenReturn(1L);
        when(row.status()).thenReturn(InstrumentStatus.TRADING);
        var snapshot = new InstrumentSnapshotResponse(line, 1, "", List.of(row), Map.of("USDT", 100000000L));
        when(instruments.snapshot(line)).thenReturn(snapshot);
        context(line, instruments).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(InstrumentSnapshotCache.class)
                    .doesNotHaveBean("accountInstrumentSnapshotCache")
                    .doesNotHaveBean("instrumentSnapshotInitializer");
            var cache = ctx.getBean(InstrumentSnapshotCache.class);
            assertThat(cache.ready(line)).isTrue();
            assertThat(cache.current(line, "BTC-USDT")).isPresent();
            assertThat(cache.scale(line, "USDT")).contains(100000000L);
            verify(instruments, times(1)).snapshot(line);
            verifyNoMoreInteractions(instruments);
        });
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void stillRejectsEmptySnapshot(ProductLine line) {
        var instruments = mock(InstrumentService.class);
        when(instruments.snapshot(line)).thenReturn(new InstrumentSnapshotResponse(line, 0, "", List.of()));
        context(line, instruments).run(ctx -> assertThat(ctx).hasFailed());
    }

    private ApplicationContextRunner context(ProductLine line, InstrumentService instruments) {
        var orders = new TradingOrderProperties();
        orders.getKafka().setProductLine(line);
        return new ApplicationContextRunner().withUserConfiguration(SnapshotComponents.class)
                .withBean(InstrumentService.class, () -> instruments)
                .withBean(TradingOrderProperties.class, () -> orders)
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }
}
