package com.surprising.risk.provider.service;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.MarkPriceUpdateListener;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.provider.config.RiskProperties;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Dispatches Redis-indexed risk scans for price changes plus a bounded stable-price retry heartbeat. */
@Component
public class MarkPriceRiskTrigger implements MarkPriceUpdateListener {

    private static final Logger log = LoggerFactory.getLogger(MarkPriceRiskTrigger.class);

    private final RiskProperties properties;
    private final RedisRiskStateStore stateStore;
    private final RiskService riskService;
    private final ExecutorService executor;

    public MarkPriceRiskTrigger(RiskProperties properties,
                                RedisRiskStateStore stateStore,
                                RiskService riskService) {
        this.properties = properties;
        this.stateStore = stateStore;
        this.riskService = riskService;
        int concurrency = Math.max(1, properties.getRedisState().getTriggerConcurrency());
        this.executor = Executors.newFixedThreadPool(concurrency,
                Thread.ofPlatform().name("risk-mark-trigger-", 0).factory());
    }

    @Override
    public void onMarkPriceUpdated(MarkPriceEvent previous, MarkPriceEvent current) {
        ProductLine productLine = properties.getKafka().getProductLine();
        if (!properties.getCalculation().isEnabled() || current.productLine() != productLine
                || !stateStore.ready(productLine)) {
            return;
        }
        boolean priceChanged = previous.markPriceTicks() != current.markPriceTicks();
        boolean claimed = priceChanged
                ? stateStore.claim(productLine, current)
                : stateStore.claimHeartbeat(productLine, current,
                        properties.getRedisState().getUnchangedTriggerInterval());
        if (!claimed) {
            return;
        }
        executor.execute(() -> {
            try {
                riskService.scanMarkPrice(current);
            } catch (RuntimeException ex) {
                riskService.invalidateProjection();
                log.error("Redis mark-price risk scan failed symbol={} version={} sequence={}: {}",
                        current.symbol(), current.instrumentVersion(), current.sequence(), ex.getMessage(), ex);
            }
        });
    }

    @PreDestroy
    public void close() {
        executor.shutdown();
    }
}
