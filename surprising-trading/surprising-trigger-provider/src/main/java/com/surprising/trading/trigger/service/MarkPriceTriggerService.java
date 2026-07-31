package com.surprising.trading.trigger.service;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 每个交易对只保留最新标记价格，并执行触发单评估。
 */
@Component
public class MarkPriceTriggerService {

    public static final long SCAN_INTERVAL_MS = 1_000L;

    private static final Logger log = LoggerFactory.getLogger(MarkPriceTriggerService.class);

    private final TriggerOrderService triggerOrderService;
    private final LatestMarkPriceCache markPriceCache;
    private final ConcurrentMap<String, Long> processedSequences = new ConcurrentHashMap<>();
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    public MarkPriceTriggerService(TriggerOrderService triggerOrderService, LatestMarkPriceCache markPriceCache) {
        this.triggerOrderService = triggerOrderService;
        this.markPriceCache = markPriceCache;
    }

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
            // 保留最新采样，使数据库或订单服务的瞬时故障可以在下一秒重试。
            log.error("Failed to scan latest mark price symbol={} sequence={}: {}",
                    event.symbol(), event.sequence(), ex.getMessage(), ex);
        }
    }
}
