package com.surprising.trading.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.surprising.trading.trigger.model.MarkTrigger;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class MarkPriceTriggerSchedulerTest {

    @Test
    void keepsOnlyNewestMarkPricePerSymbolAndDoesNotRepeatProcessedSequence() {
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);
        MarkPriceTriggerScheduler scheduler = new MarkPriceTriggerScheduler(triggerOrderService);
        MarkTrigger newest = trigger("BTC-USDT", 43L);

        scheduler.updateLatest(trigger("BTC-USDT", 41L));
        scheduler.updateLatest(newest);
        scheduler.updateLatest(trigger("BTC-USDT", 42L));
        scheduler.scanLatest();
        scheduler.scanLatest();

        verify(triggerOrderService).onMarkPrice(newest);
    }

    @Test
    void scansEverySymbolsLatestSample() {
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);
        MarkPriceTriggerScheduler scheduler = new MarkPriceTriggerScheduler(triggerOrderService);
        MarkTrigger btc = trigger("BTC-USDT", 51L);
        MarkTrigger eth = trigger("ETH-USDT", 61L);

        scheduler.updateLatest(btc);
        scheduler.updateLatest(eth);
        scheduler.scanLatest();

        verify(triggerOrderService).onMarkPrice(btc);
        verify(triggerOrderService).onMarkPrice(eth);
    }

    @Test
    void retriesLatestSampleAfterTransientScanFailure() {
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);
        MarkPriceTriggerScheduler scheduler = new MarkPriceTriggerScheduler(triggerOrderService);
        MarkTrigger trigger = trigger("BTC-USDT", 71L);
        doThrow(new IllegalStateException("database unavailable"))
                .doNothing()
                .when(triggerOrderService).onMarkPrice(trigger);

        scheduler.updateLatest(trigger);
        scheduler.scanLatest();
        scheduler.scanLatest();

        verify(triggerOrderService, times(2)).onMarkPrice(trigger);
    }

    @Test
    void scheduledScanUsesFixedOneSecondCadence() throws Exception {
        Scheduled scheduled = MarkPriceTriggerScheduler.class.getMethod("scanLatest")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedRate()).isEqualTo(1_000L);
        assertThat(scheduled.initialDelay()).isEqualTo(1_000L);
    }

    private MarkTrigger trigger(String symbol, long sequence) {
        return new MarkTrigger(symbol, sequence, Instant.parse("2026-07-01T00:00:00Z").plusSeconds(sequence));
    }
}
