package com.surprising.marketmaker.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.instrument.api.model.*;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.consumer.MakerQuoteWakeups;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class InstrumentSnapshotRefreshTest {

    @Test
    void periodicRpcRefreshDetectsChangesAndAdjustsMarketMakerState() {
        var properties = new MarketMakerProperties();
        properties.setProductLine(ProductLine.LINEAR_PERPETUAL);

        var rpc = mock(InstrumentRpcApi.class);
        var cache = new InstrumentSnapshotCache();
        var wakeups = mock(MakerQuoteWakeups.class);
        var makerService = mock(MarketMakerService.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<MarketMakerService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(makerService);

        var initializer = new InstrumentSnapshotInitializer(properties, rpc, cache, wakeups, provider);

        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var initialInstrument = new InstrumentResponse(604, 3, 1, 1, 3, "1", 1L,
                InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL,
                "BTC", "USDT", "USDT", 1_000_000L, "BTC", 10L, 1L, 1L, 1_000_000L,
                1L, 1_000_000_000_000L, 1L, 2, 0, List.of("LIMIT"), List.of("GTX"), true,
                true, true, 100_000_000L, 10_000L, 5_000L, -100L, 500L,
                1_000_000_000L, 300_000L, 250_000_000L, 8, 100L, 3_000L, -3_000L,
                10_000_000L, 3, null, null, null, null, null, null, null, null,
                InstrumentStatus.TRADING, now, now, now, List.of(), List.of());

        when(rpc.snapshot(ProductLine.LINEAR_PERPETUAL))
                .thenReturn(new InstrumentSnapshotResponse(ProductLine.LINEAR_PERPETUAL, 1L, "hash1", List.of(initialInstrument), Map.of("USDT", 1_000_000L)));

        // 首次初始化
        initializer.initialize();
        assertThat(cache.current(ProductLine.LINEAR_PERPETUAL, 604)).isPresent();

        // 模拟合约发生状态变更：变为停牌 HALT，且版本变更为 2L
        var haltedInstrument = new InstrumentResponse(604, 3, 1, 1, 3, "1", 2L,
                InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL,
                "BTC", "USDT", "USDT", 1_000_000L, "BTC", 10L, 1L, 1L, 1_000_000L,
                1L, 1_000_000_000_000L, 1L, 2, 0, List.of("LIMIT"), List.of("GTX"), true,
                true, true, 100_000_000L, 10_000L, 5_000L, -100L, 500L,
                1_000_000_000L, 300_000L, 250_000_000L, 8, 100L, 3_000L, -3_000L,
                10_000_000L, 3, null, null, null, null, null, null, null, null,
                InstrumentStatus.HALT, now, now, now, List.of(), List.of());

        when(rpc.snapshot(ProductLine.LINEAR_PERPETUAL))
                .thenReturn(new InstrumentSnapshotResponse(ProductLine.LINEAR_PERPETUAL, 2L, "hash2", List.of(haltedInstrument), Map.of("USDT", 1_000_000L)));

        // 执行定时刷新
        initializer.refreshSnapshots();

        // 验证：
        // 1. 本地缓存已被原子替换为最新 HALT 状态
        assertThat(cache.current(ProductLine.LINEAR_PERPETUAL, 604).get().status()).isEqualTo(InstrumentStatus.HALT);
        // 2. 停牌状态下，自动调用了做市排空撤单
        verify(makerService, times(1)).onInstrumentStatusChanged(ProductLine.LINEAR_PERPETUAL, "604", false);
        // 3. 实时唤醒了做市策略感知变更
        verify(wakeups, times(1)).changed(ProductLine.LINEAR_PERPETUAL, "604");
    }
}
