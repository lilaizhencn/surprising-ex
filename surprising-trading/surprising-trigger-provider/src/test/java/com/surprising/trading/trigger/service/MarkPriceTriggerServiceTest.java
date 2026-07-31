package com.surprising.trading.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.trigger.task.TriggerOrderMaintenanceTask;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class MarkPriceTriggerServiceTest {

    @Test
    void scansEveryFreshSymbolOncePerSequence() {
        TriggerOrderService service = mock(TriggerOrderService.class);
        LatestMarkPriceCache cache = mock(LatestMarkPriceCache.class);
        MarkPriceEvent btc = mark("BTC-USDT", 51L, 60_001L);
        MarkPriceEvent eth = mark("ETH-USDT", 61L, 3_001L);
        when(cache.freshSnapshots()).thenReturn(List.of(btc, eth));
        MarkPriceTriggerService scheduler = new MarkPriceTriggerService(service, cache);

        scheduler.scanLatest();
        scheduler.scanLatest();

        verify(service).onMarkPrice(btc);
        verify(service).onMarkPrice(eth);
    }

    @Test
    void retriesLatestSampleAfterTransientScanFailure() {
        TriggerOrderService service = mock(TriggerOrderService.class);
        LatestMarkPriceCache cache = mock(LatestMarkPriceCache.class);
        MarkPriceEvent event = mark("BTC-USDT", 71L, 70_001L);
        when(cache.freshSnapshots()).thenReturn(List.of(event));
        doThrow(new IllegalStateException("database unavailable")).doNothing().when(service).onMarkPrice(event);
        MarkPriceTriggerService scheduler = new MarkPriceTriggerService(service, cache);

        scheduler.scanLatest();
        scheduler.scanLatest();

        verify(service, times(2)).onMarkPrice(event);
    }

    @Test
    void stalePricesAreSkippedByTheSharedFreshCache() {
        TriggerOrderService service = mock(TriggerOrderService.class);
        LatestMarkPriceCache cache = mock(LatestMarkPriceCache.class);
        when(cache.freshSnapshots()).thenReturn(List.of());

        new MarkPriceTriggerService(service, cache).scanLatest();

        verify(cache).freshSnapshots();
        verify(service, times(0)).onMarkPrice(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void taskUsesFixedOneSecondScanCadence() throws Exception {
        Scheduled scheduled = TriggerOrderMaintenanceTask.class.getMethod("scanLatestMarkPrices")
                .getAnnotation(Scheduled.class);
        assertThat(scheduled.fixedRate()).isEqualTo(1_000L);
        assertThat(scheduled.initialDelay()).isEqualTo(1_000L);
    }

    private MarkPriceEvent mark(String symbol, long sequence, long ticks) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z").plusSeconds(sequence);
        BigDecimal price = BigDecimal.valueOf(ticks);
        return new MarkPriceEvent(ProductLine.LINEAR_PERPETUAL, symbol, 1L, ticks, ticks,
                price, price, price, price, price, price, price, BigDecimal.ZERO,
                now.plusSeconds(3600), 3600L, BigDecimal.ZERO, 60L, price, price,
                sequence, PriceStatus.HEALTHY, now, now);
    }
}
