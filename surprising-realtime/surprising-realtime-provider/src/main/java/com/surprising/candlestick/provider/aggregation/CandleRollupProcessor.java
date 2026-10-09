package com.surprising.candlestick.provider.aggregation;

import com.surprising.candlestick.api.model.CandlePeriod;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.candlestick.provider.service.CandleHotCache;
import com.surprising.candlestick.provider.repository.CandleQueryRepository;
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
    private final CandleQueryRepository minutes;
    private ProcessorContext<String, CandleUpdatedEvent> context;
    private KeyValueStore<String, CandleRollupAccumulator> rollupStore;
    private KeyValueStore<String, Long> seenStore;
    private KeyValueStore<String, Long> watermarkStore;

    public CandleRollupProcessor(CandlestickProperties properties, CandleHotCache hotCache,
                                CandleQueryRepository minutes) {
        this.properties = properties;
        this.periods = properties.getPeriods().stream().map(CandlePeriod::fromCode)
                .filter(period -> period != CandlePeriod.M1).distinct().toList();
        this.productKey = properties.getKafka().getProductLine().topicSegment();
        this.hotCache = hotCache;
        this.minutes = java.util.Objects.requireNonNull(minutes);
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
        String instrumentId = CandleKey.normalizeSymbol(minute.instrumentId());
        for (CandlePeriod period : periods) {
            Instant openTime = period.floor(minute.openTime());
            String rollupKey = productKey + "|" + CandleKey.of(instrumentId, period, openTime).value();
            String seenKey = rollupKey + "|" + minute.openTime().toEpochMilli();
            CandleRollupAccumulator previous = rollupStore.get(rollupKey);
            if (minute.status() == com.surprising.candlestick.api.model.CandleStatus.PARTIAL) {
                updateActiveMinute(instrumentId, period, openTime, rollupKey, minute, previous, record.timestamp());
                continue;
            }
            if (previous != null && !previous.isComplete() && previous.getActiveMinute() != null
                    && !minute.openTime().isBefore(previous.getActiveMinute().openTime())) {
                var active = previous.getActiveMinute();
                if (minute.openTime().equals(active.openTime()) && minute.tradeCount() < active.tradeCount()) continue;
                previous.setActiveMinute(null);
                if (minute.openTime().isAfter(active.openTime())) {
                    previous.add(active);
                    seenStore.put(rollupKey + "|" + active.openTime().toEpochMilli(), ~active.tradeCount());
                }
                previous.add(minute);
                rollupStore.put(rollupKey, previous);
                seenStore.put(seenKey, ~minute.tradeCount());
                forward(instrumentId, previous, record.timestamp());
                continue;
            }
            Long seen = seenStore.get(seenKey);
            // Accepted trades only append to a minute. Its count is the revision;
            // lastSequence instead identifies the last trade by event time.
            if (seen != null && seen < 0 && minute.tradeCount() <= ~seen) continue;
            // A week can outlive minute dedupe retention. Any minute at/before the
            // accumulated tail is a replacement, even after its seen marker expires.
            if (seen != null || (previous != null && (previous.isComplete()
                    || previous.getLastMinute() != null && !minute.openTime().isAfter(previous.getLastMinute())))) {
                rebuildClosedMinutes(instrumentId, period, openTime, rollupKey, record.timestamp());
                continue;
            }
            String watermarkKey = productKey + "|" + instrumentId + "|" + period.code();
            Long activeOpenMillis = watermarkStore.get(watermarkKey);
            long currentOpenMillis = openTime.toEpochMilli();
            if (activeOpenMillis != null && currentOpenMillis < activeOpenMillis) {
                rebuildClosedMinutes(instrumentId, period, openTime, rollupKey, record.timestamp());
                seenStore.put(seenKey, ~minute.tradeCount());
                continue;
            }
            if (activeOpenMillis == null || currentOpenMillis > activeOpenMillis) {
                closeActiveRollup(instrumentId, period, activeOpenMillis, record.timestamp());
                watermarkStore.put(watermarkKey, currentOpenMillis);
            }
            CandleRollupAccumulator accumulator = Optional.ofNullable(previous)
                    .orElseGet(() -> CandleRollupAccumulator.create(instrumentId, period, openTime));
            if (accumulator.isComplete()) {
                continue;
            }
            accumulator.add(minute);
            rollupStore.put(rollupKey, accumulator);
            seenStore.put(seenKey, ~minute.tradeCount());
            forward(instrumentId, accumulator, record.timestamp());
        }
    }

    /** The input is a complete minute snapshot, never a quantity delta. Replacing the
     * active tail keeps every larger period live without SQL or double counting. */
    private void updateActiveMinute(String instrumentId, CandlePeriod period, Instant openTime,
            String rollupKey, CandleUpdatedEvent minute, CandleRollupAccumulator previous, long timestamp) {
        if (previous != null && (previous.isComplete() || previous.getLastMinute() != null
                && !minute.openTime().isAfter(previous.getLastMinute()))) return;
        var accumulator = previous == null
                ? CandleRollupAccumulator.create(instrumentId, period, openTime) : previous;
        var active = accumulator.getActiveMinute();
        if (active != null) {
            if (minute.openTime().isBefore(active.openTime())
                    || minute.openTime().equals(active.openTime()) && minute.tradeCount() <= active.tradeCount()) return;
            if (minute.openTime().isAfter(active.openTime())) {
                // A new minute can arrive before the previous minute's SQL close notification.
                // Fold its last accepted snapshot once; that later notification is idempotent.
                accumulator.setActiveMinute(null);
                accumulator.add(active);
                seenStore.put(rollupKey + "|" + active.openTime().toEpochMilli(), ~active.tradeCount());
            }
        }
        String watermarkKey = productKey + "|" + instrumentId + "|" + period.code();
        Long activeOpenMillis = watermarkStore.get(watermarkKey);
        if (activeOpenMillis != null && openTime.toEpochMilli() < activeOpenMillis) return;
        if (activeOpenMillis == null || openTime.toEpochMilli() > activeOpenMillis) {
            closeActiveRollup(instrumentId, period, activeOpenMillis, timestamp);
            watermarkStore.put(watermarkKey, openTime.toEpochMilli());
        }
        accumulator.setActiveMinute(minute);
        rollupStore.put(rollupKey, accumulator);
        forward(instrumentId, accumulator, timestamp);
    }

    /** Only a replacement/late bucket reads SQL. Ordinary new minutes retain the incremental
     * path. The existing durable minute projection supplies one bounded period (at most10080
     * rows), so no second per-minute index, changelog or schema migration is needed. A database
     * failure propagates: the input offset must not commit with a stale higher-period value. */
    private void rebuildClosedMinutes(String instrumentId, CandlePeriod period, Instant openTime,
                                      String rollupKey, long timestamp) {
        var replacement = CandleRollupAccumulator.create(instrumentId, period, openTime);
        Instant emittedAt = Instant.ofEpochMilli(context.currentSystemTimeMs());
        var rows = minutes.findRange(instrumentId, "1m", openTime, period.closeTime(openTime),
                Math.toIntExact(period.duration().toMinutes()));
        if (rows.isEmpty()) throw new IllegalStateException("durable closed minutes missing for rollup revision");
        for (var row : rows) {
            replacement.add(new CandleUpdatedEvent(row.instrumentId(), row.period(),
                row.openTime(), row.closeTime(), row.openPrice(), row.highPrice(), row.lowPrice(),
                row.closePrice(), row.baseVolume(), row.quoteVolume(), row.tradeCount(), row.firstTradeId(),
                row.lastTradeId(), row.firstSequence(), row.lastSequence(), row.status(), row.updatedAt(),
                emittedAt, null, null));
            // SQL may already contain minutes whose Kafka notification is still queued.
            // Mark those inputs too so their later notification cannot add them twice.
            String key = rollupKey + "|" + row.openTime().toEpochMilli();
            long revision = ~row.tradeCount();
            if (!java.util.Objects.equals(seenStore.get(key), revision)) seenStore.put(key, revision);
        }
        var previous = rollupStore.get(rollupKey);
        if (previous != null && previous.getActiveMinute() != null
                && (replacement.getLastMinute() == null
                    || previous.getActiveMinute().openTime().isAfter(replacement.getLastMinute())))
            replacement.setActiveMinute(previous.getActiveMinute());
        if (!period.closeTime(openTime).isAfter(emittedAt)) replacement.close();
        if (previous != null && previous.event(emittedAt).equals(replacement.event(emittedAt))) return;
        rollupStore.put(rollupKey, replacement);
        forward(instrumentId, replacement, timestamp);
    }

    private void closeActiveRollup(String instrumentId, CandlePeriod period, Long activeOpenMillis, long timestamp) {
        if (activeOpenMillis == null) {
            return;
        }
        Instant activeOpen = Instant.ofEpochMilli(activeOpenMillis);
        String activeKey = productKey + "|" + CandleKey.of(instrumentId, period, activeOpen).value();
        CandleRollupAccumulator active = rollupStore.get(activeKey);
        if (active == null || active.isComplete()) {
            return;
        }
        active.close();
        rollupStore.put(activeKey, active);
        forward(instrumentId, active, timestamp);
    }

    private void forward(String instrumentId, CandleRollupAccumulator accumulator, long timestamp) {
        Instant emittedAt = Instant.ofEpochMilli(context.currentSystemTimeMs());
        CandleUpdatedEvent event = accumulator.event(emittedAt);
        if (hotCache != null) {
            hotCache.put(event);
        }
        context.forward(new Record<>(instrumentId, event, timestamp));
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
                String instrumentId = watermark.key.substring(prefix.length(), periodSeparator);
                CandlePeriod period = CandlePeriod.fromCode(watermark.key.substring(periodSeparator + 1));
                Instant activeOpen = Instant.ofEpochMilli(watermark.value);
                String rollupKey = productKey + "|" + CandleKey.of(instrumentId, period, activeOpen).value();
                CandleRollupAccumulator accumulator = rollupStore.get(rollupKey);
                if (accumulator != null && !accumulator.isComplete() && accumulator.getCloseTime() != null
                        && accumulator.getCloseTime().toEpochMilli() <= timestamp) {
                    accumulator.close();
                    rollupStore.put(rollupKey, accumulator);
                    forward(instrumentId, accumulator, timestamp);
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
                // The minute time is already authoritative in the key. The existing
                // Long value now stores complemented count; old positive timestamp
                // markers are converted by the first durable rebuild after upgrade.
                long minuteTime = Long.parseLong(item.key.substring(item.key.lastIndexOf('|') + 1));
                if (minuteTime < cutoff) {
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
