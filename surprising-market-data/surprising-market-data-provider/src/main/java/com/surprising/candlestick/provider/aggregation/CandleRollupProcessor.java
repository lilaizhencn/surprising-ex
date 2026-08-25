package com.surprising.candlestick.provider.aggregation;

import com.surprising.candlestick.api.model.CandlePeriod;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.candlestick.provider.service.CandleHotCache;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;

public class CandleRollupProcessor implements Processor<String, CandleUpdatedEvent, String, CandleUpdatedEvent> {
    private final CandlestickProperties properties;
    private final List<CandlePeriod> periods;
    private final String productKey;
    private final CandleHotCache hotCache;
    private ProcessorContext<String, CandleUpdatedEvent> context;
    private KeyValueStore<String, CandleRollupAccumulator> rollupStore;
    private KeyValueStore<String, Long> seenStore;
    private KeyValueStore<String, Long> watermarkStore;

    public CandleRollupProcessor(CandlestickProperties properties, CandleHotCache hotCache) {
        this.properties = properties;
        this.periods = properties.getPeriods().stream().map(CandlePeriod::fromCode)
                .filter(period -> period != CandlePeriod.M1).distinct().toList();
        this.productKey = properties.getKafka().getProductLine().topicSegment();
        this.hotCache = hotCache;
    }

    @Override
    public void init(ProcessorContext<String, CandleUpdatedEvent> context) {
        this.context = context;
        this.rollupStore = context.getStateStore(CandleStores.ROLLUP_STORE);
        this.seenStore = context.getStateStore(CandleStores.ROLLUP_SEEN_STORE);
        this.watermarkStore = context.getStateStore(CandleStores.ROLLUP_WATERMARK_STORE);
        context.schedule(Duration.ofMinutes(1), PunctuationType.WALL_CLOCK_TIME, this::closeElapsedRollups);
        context.schedule(properties.getStream().getDedupeRetention(), PunctuationType.WALL_CLOCK_TIME,
                this::cleanupExpiredState);
    }

    @Override
    public void process(Record<String, CandleUpdatedEvent> record) {
        CandleUpdatedEvent minute = record.value();
        if (minute == null) {
            return;
        }
        String symbol = CandleKey.normalizeSymbol(minute.symbol());
        for (CandlePeriod period : periods) {
            Instant openTime = period.floor(minute.openTime());
            String rollupKey = productKey + "|" + CandleKey.of(symbol, period, openTime).value();
            String seenKey = rollupKey + "|" + minute.openTime().toEpochMilli();
            if (seenStore.get(seenKey) != null) {
                continue;
            }
            String watermarkKey = productKey + "|" + symbol + "|" + period.code();
            Long activeOpenMillis = watermarkStore.get(watermarkKey);
            long currentOpenMillis = openTime.toEpochMilli();
            if (activeOpenMillis != null && currentOpenMillis < activeOpenMillis) {
                continue;
            }
            if (activeOpenMillis == null || currentOpenMillis > activeOpenMillis) {
                closeActiveRollup(symbol, period, activeOpenMillis, record.timestamp());
                watermarkStore.put(watermarkKey, currentOpenMillis);
            }
            CandleRollupAccumulator accumulator = Optional.ofNullable(rollupStore.get(rollupKey))
                    .orElseGet(() -> CandleRollupAccumulator.create(symbol, period, openTime));
            if (accumulator.isComplete()) {
                continue;
            }
            accumulator.add(minute);
            rollupStore.put(rollupKey, accumulator);
            seenStore.put(seenKey, minute.openTime().toEpochMilli());
            forward(symbol, accumulator, record.timestamp());
        }
    }

    private void closeActiveRollup(String symbol, CandlePeriod period, Long activeOpenMillis, long timestamp) {
        if (activeOpenMillis == null) {
            return;
        }
        Instant activeOpen = Instant.ofEpochMilli(activeOpenMillis);
        String activeKey = productKey + "|" + CandleKey.of(symbol, period, activeOpen).value();
        CandleRollupAccumulator active = rollupStore.get(activeKey);
        if (active == null || active.isComplete()) {
            return;
        }
        active.close();
        rollupStore.put(activeKey, active);
        forward(symbol, active, timestamp);
    }

    private void forward(String symbol, CandleRollupAccumulator accumulator, long timestamp) {
        Instant emittedAt = Instant.ofEpochMilli(context.currentSystemTimeMs());
        CandleUpdatedEvent event = accumulator.event(emittedAt);
        if (hotCache != null) {
            hotCache.put(event);
        }
        context.forward(new Record<>(symbol, event, timestamp));
    }

    private void closeElapsedRollups(long timestamp) {
        String prefix = productKey + "|";
        try (KeyValueIterator<String, Long> iterator = watermarkStore.all()) {
            while (iterator.hasNext()) {
                KeyValue<String, Long> watermark = iterator.next();
                int periodSeparator = watermark.key.lastIndexOf('|');
                if (!watermark.key.startsWith(prefix) || periodSeparator < prefix.length()
                        || watermark.value == null) {
                    continue;
                }
                String symbol = watermark.key.substring(prefix.length(), periodSeparator);
                CandlePeriod period = CandlePeriod.fromCode(watermark.key.substring(periodSeparator + 1));
                Instant activeOpen = Instant.ofEpochMilli(watermark.value);
                String rollupKey = productKey + "|" + CandleKey.of(symbol, period, activeOpen).value();
                CandleRollupAccumulator accumulator = rollupStore.get(rollupKey);
                if (accumulator != null && !accumulator.isComplete() && accumulator.getCloseTime() != null
                        && accumulator.getCloseTime().toEpochMilli() <= timestamp) {
                    accumulator.close();
                    rollupStore.put(rollupKey, accumulator);
                    forward(symbol, accumulator, timestamp);
                }
            }
        }
    }

    private void cleanupExpiredState(long timestamp) {
        long cutoff = timestamp - properties.getStream().getDedupeRetention().toMillis();
        int maxEntries = properties.getStream().getDedupeCleanupMaxEntries();
        List<String> expiredSeen = new ArrayList<>();
        try (KeyValueIterator<String, Long> iterator = seenStore.all()) {
            while (iterator.hasNext() && expiredSeen.size() < maxEntries) {
                KeyValue<String, Long> item = iterator.next();
                if (item.value != null && item.value < cutoff) {
                    expiredSeen.add(item.key);
                }
            }
        }
        expiredSeen.forEach(seenStore::delete);

        List<String> expiredRollups = new ArrayList<>();
        try (KeyValueIterator<String, CandleRollupAccumulator> iterator = rollupStore.all()) {
            while (iterator.hasNext() && expiredRollups.size() < maxEntries) {
                KeyValue<String, CandleRollupAccumulator> item = iterator.next();
                if (item.value != null && item.value.isComplete() && item.value.getCloseTime() != null
                        && item.value.getCloseTime().toEpochMilli() < cutoff) {
                    expiredRollups.add(item.key);
                }
            }
        }
        expiredRollups.forEach(rollupStore::delete);
    }
}
