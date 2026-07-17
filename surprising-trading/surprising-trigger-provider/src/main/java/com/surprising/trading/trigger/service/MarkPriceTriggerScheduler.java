package com.surprising.trading.trigger.service;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps only the newest mark price per symbol and evaluates trigger orders at a fixed one-second cadence.
 */
@Component
public class MarkPriceTriggerScheduler {

    public static final long SCAN_INTERVAL_MS = 1_000L;

    private static final Logger log = LoggerFactory.getLogger(MarkPriceTriggerScheduler.class);

    private final TriggerOrderService triggerOrderService;
    private final LatestMarkPriceCache markPriceCache;
    private final ConcurrentMap<String, Long> processedSequences = new ConcurrentHashMap<>();
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    public MarkPriceTriggerScheduler(TriggerOrderService triggerOrderService, LatestMarkPriceCache markPriceCache) {
        this.triggerOrderService = triggerOrderService;
        this.markPriceCache = markPriceCache;
    }

    @Scheduled(fixedRate = SCAN_INTERVAL_MS, initialDelay = SCAN_INTERVAL_MS)
    public void scanLatest() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            markPriceCache.freshSnapshots().forEach(this::scan);
        } finally {
            scanning.set(false);
        }
    }

    private void scan(MarkPriceEvent event) {
        long processedSequence = processedSequences.getOrDefault(event.symbol(), 0L);
        if (event.sequence() <= processedSequence) {
            return;
        }
        try {
            triggerOrderService.onMarkPrice(event);
            processedSequences.merge(event.symbol(), event.sequence(), Math::max);
        } catch (RuntimeException ex) {
            // Keep the latest sample pending so a transient database/order-provider failure is retried next second.
            log.error("Failed to scan latest mark price symbol={} sequence={}: {}",
                    event.symbol(), event.sequence(), ex.getMessage(), ex);
        }
    }
}
