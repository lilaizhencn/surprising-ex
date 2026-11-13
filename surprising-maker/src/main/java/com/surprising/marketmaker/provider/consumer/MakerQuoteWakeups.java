package com.surprising.marketmaker.provider.consumer;

import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 做市商本地工作唤醒通知；通过 UDP 极速响应价格、成交与盘口变动，完全解耦 Kafka。 */
@Component
@Slf4j
public class MakerQuoteWakeups {
    private final MarketMakerProperties properties;
    private final Map<String, Signal> signals = new ConcurrentHashMap<>();
    private final Map<String, MarketMakerProperties.Strategy> registeredStrategies = new ConcurrentHashMap<>();

    @Autowired
    public MakerQuoteWakeups(MarketMakerProperties properties) {
        this.properties = properties;
    }

    public MakerQuoteWakeups(MarketMakerProperties properties, tools.jackson.databind.ObjectMapper mapper) {
        this.properties = properties;
    }

    public void changed(ProductLine product, String instrumentId) {
        if (product == null || instrumentId == null) return;
        for (var strategy : registeredStrategies.values()) {
            if (strategy.getProductLine() == product && strategy.getInstrumentIds().contains(instrumentId)) {
                var signal = signals.get(key(strategy));
                if (signal != null) signal.wake();
            }
        }
    }

    public void register(MarketMakerProperties.Strategy strategy) {
        registeredStrategies.put(key(strategy), strategy);
        signals.computeIfAbsent(key(strategy), ignored -> new Signal()).wake();
    }

    public void await(MarketMakerProperties.Strategy strategy, Duration watchdog) throws InterruptedException {
        Signal signal = signals.get(key(strategy));
        if (signal != null && signal.ready.tryAcquire(watchdog.toMillis(), TimeUnit.MILLISECONDS)) {
            signal.pending.set(false);
        }
    }

    public void clear() {
        signals.clear();
        registeredStrategies.clear();
    }

    public void onMarkPriceUpdated(MarkPriceEvent event) {
        if (event != null) {
            changed(event.productLine(), event.instrumentId());
        }
    }

    public void onTrade(ProductLine product, String instrumentId) {
        changed(product, instrumentId);
    }

    public void onTrade(String instrumentId) {
        changed(properties.getProductLine(), instrumentId);
    }

    private String key(MarketMakerProperties.Strategy s) {
        return s.getProductLine() + ":" + s.getStrategyId();
    }

    private static final class Signal {
        final Semaphore ready = new Semaphore(0);
        final AtomicBoolean pending = new AtomicBoolean();
        void wake() {
            if (pending.compareAndSet(false, true)) {
                ready.release();
            }
        }
    }
}
