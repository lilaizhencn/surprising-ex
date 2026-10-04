package com.surprising.marketmaker.provider.task;

import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.MarkPriceUpdateListener;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import com.surprising.trading.api.model.PublicTradeEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/** Process-local work notifications only; prices, orders and positions remain in their existing owners. */
@Component
@Slf4j
public class MakerQuoteWakeups implements MarkPriceUpdateListener {
    private final MarketMakerProperties properties;
    private final ObjectMapper mapper;
    private final Map<String, Signal> signals = new ConcurrentHashMap<>();
    private final String groupId = "surprising-maker-quote-wakeup-" + UUID.randomUUID();

    public MakerQuoteWakeups(MarketMakerProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public void changed(ProductLine product, String instrumentId) {
        if (product == null || instrumentId == null) return;
        for (var strategy : properties.getStrategies()) {
            if (strategy.getProductLine() == product && strategy.getInstrumentIds().contains(instrumentId)) {
                var signal = signals.get(key(strategy));
                if (signal != null) signal.wake();
            }
        }
    }

    void register(MarketMakerProperties.Strategy strategy) {
        signals.computeIfAbsent(key(strategy), ignored -> new Signal()).wake();
    }

    void await(MarketMakerProperties.Strategy strategy, Duration watchdog) throws InterruptedException {
        Signal signal = signals.get(key(strategy));
        if (signal.ready.tryAcquire(watchdog.toMillis(), TimeUnit.MILLISECONDS)) signal.pending.set(false);
    }

    void clear() { signals.clear(); }

    @Override
    public void onMarkPriceUpdated(MarkPriceEvent previous, MarkPriceEvent current) {
        if (previous == null || previous.markPriceTicks() != current.markPriceTicks()
                || previous.status() != current.status()) changed(current.productLine(), current.instrumentId());
    }

    @KafkaListener(topics = "#{__listener.tradeTopics()}", groupId = "#{__listener.groupId()}",
            containerFactory = "marketMakerInstrumentSnapshotKafkaListenerContainerFactory",
            properties = {"auto.offset.reset=latest", "isolation.level=read_committed"})
    public void onTrade(ConsumerRecord<String, String> record) {
        try {
            var trade = mapper.readValue(record.value(), PublicTradeEvent.class);
            if (record.key() == null || !record.key().equals(trade.instrumentId()) || trade.quantitySteps() <= 0)
                throw new IllegalArgumentException("invalid committed trade identity or quantity");
            for (var product : products()) {
                if (ProductTopicNames.of(product).matchTradesTopic().equals(record.topic())) {
                    changed(product, trade.instrumentId());
                    return;
                }
            }
            throw new IllegalArgumentException("unexpected product trade topic");
        } catch (RuntimeException ex) {
            log.warn("Discarding invalid maker wakeup trade: {}", ex.getMessage());
        }
    }

    public List<String> tradeTopics() {
        return products().stream().map(p -> ProductTopicNames.of(p).matchTradesTopic()).toList();
    }
    public String groupId() { return groupId; }
    private List<ProductLine> products() {
        return properties.getStrategies().stream().map(MarketMakerProperties.Strategy::getProductLine)
                .filter(java.util.Objects::nonNull).distinct().toList();
    }
    private String key(MarketMakerProperties.Strategy s) { return s.getProductLine() + ":" + s.getStrategyId(); }
    private static final class Signal {
        final Semaphore ready = new Semaphore(0);
        final AtomicBoolean pending = new AtomicBoolean();
        void wake() { if (pending.compareAndSet(false, true)) ready.release(); }
    }
}
