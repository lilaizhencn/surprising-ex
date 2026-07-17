package com.surprising.trading.trigger.service;

import com.surprising.trading.trigger.model.MarkTrigger;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    private final ConcurrentMap<String, MarkTrigger> latestBySymbol = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> processedSequences = new ConcurrentHashMap<>();
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    public MarkPriceTriggerScheduler(TriggerOrderService triggerOrderService) {
        this.triggerOrderService = triggerOrderService;
    }

    public void updateLatest(MarkTrigger trigger) {
        if (trigger == null || trigger.symbol() == null || trigger.symbol().isBlank()
                || trigger.sequence() <= 0 || trigger.eventTime() == null) {
            throw new IllegalArgumentException("valid mark price trigger is required");
        }
        latestBySymbol.compute(trigger.symbol(), (symbol, current) -> current == null
                || trigger.sequence() > current.sequence() ? trigger : current);
    }

    @Scheduled(fixedRate = SCAN_INTERVAL_MS, initialDelay = SCAN_INTERVAL_MS)
    public void scanLatest() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            latestBySymbol.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .map(Map.Entry::getValue)
                    .forEach(this::scan);
        } finally {
            scanning.set(false);
        }
    }

    private void scan(MarkTrigger trigger) {
        long processedSequence = processedSequences.getOrDefault(trigger.symbol(), 0L);
        if (trigger.sequence() <= processedSequence) {
            return;
        }
        try {
            triggerOrderService.onMarkPrice(trigger);
            processedSequences.merge(trigger.symbol(), trigger.sequence(), Math::max);
        } catch (RuntimeException ex) {
            // Keep the latest sample pending so a transient database/order-provider failure is retried next second.
            log.error("Failed to scan latest mark price symbol={} sequence={}: {}",
                    trigger.symbol(), trigger.sequence(), ex.getMessage(), ex);
        }
    }
}
