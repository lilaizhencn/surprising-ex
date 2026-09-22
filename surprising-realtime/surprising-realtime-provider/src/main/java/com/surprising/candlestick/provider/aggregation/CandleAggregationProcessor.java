package com.surprising.candlestick.provider.aggregation;

import com.surprising.candlestick.api.model.CandlePeriod;
import com.surprising.candlestick.api.model.CandleStatus;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import com.surprising.candlestick.api.model.TradeEvent;
import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.candlestick.provider.service.CandleHotCache;
import com.surprising.candlestick.provider.service.PublicTradeEventMapper;
import com.surprising.candlestick.provider.service.SymbolRegistryService;
import com.surprising.trading.api.model.PublicTradeEvent;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka Streams processor that turns keyed product-line trades into one-minute candle snapshots.
 *
 * <p>Concurrency is controlled by Kafka partitioning: every record key must equal the normalized
 * symbol, so one symbol is processed by exactly one stream task at a time. RocksDB state stores
 * keep hot candles, dedupe keys, dirty snapshots, and latest sequence locally, while Kafka Streams
 * changelog topics make the state restorable after restart or rebalance.</p>
 */
public class CandleAggregationProcessor implements Processor<String, PublicTradeEvent, String, CandleUpdatedEvent> {

    private static final Logger log = LoggerFactory.getLogger(CandleAggregationProcessor.class);

    private final CandlestickProperties properties;
    private final CandleSink candleSink;
    private final SymbolRegistryService symbolRegistryService;
    private final PublicTradeEventMapper tradeEventMapper;
    private final CandleHotCache hotCache;
    private final String productKey;

    private ProcessorContext<String, CandleUpdatedEvent> context;
    private KeyValueStore<String, CandleAccumulator> candleStore;
    private KeyValueStore<String, CandleSnapshot> dirtyStore;
    private KeyValueStore<String, Long> closedWatermarkStore;
    private KeyValueStore<String, Long> dedupeStore;
    private KeyValueStore<String, Long> sequenceStore;

    public CandleAggregationProcessor(CandlestickProperties properties, CandleSink candleSink,
                                      SymbolRegistryService symbolRegistryService,
                                      PublicTradeEventMapper tradeEventMapper,
                                      CandleHotCache hotCache) {
        this.properties = properties;
        this.candleSink = candleSink;
        this.symbolRegistryService = symbolRegistryService;
        this.tradeEventMapper = tradeEventMapper;
        this.hotCache = hotCache;
        this.productKey = properties.getKafka().getProductLine().topicSegment();
    }

    @Override
    public void init(ProcessorContext<String, CandleUpdatedEvent> context) {
        this.context = context;
        this.candleStore = context.getStateStore(CandleStores.CANDLE_STORE);
        this.dirtyStore = context.getStateStore(CandleStores.DIRTY_STORE);
        this.closedWatermarkStore = context.getStateStore(CandleStores.CLOSED_M1_WATERMARK_STORE);
        this.dedupeStore = context.getStateStore(CandleStores.DEDUPE_STORE);
        this.sequenceStore = context.getStateStore(CandleStores.SEQUENCE_STORE);
        context.schedule(properties.getFlush().getInterval(), PunctuationType.WALL_CLOCK_TIME, this::flushDirtyCandles);
        context.schedule(properties.getStream().getDedupeRetention(), PunctuationType.WALL_CLOCK_TIME, this::cleanupDedupeStore);
    }

    /**
     * Handles one trade record. Invalid, disabled, duplicate, or old-sequence trades are dropped
     * before they can change candle state.
     */
    @Override
    public void process(Record<String, PublicTradeEvent> record) {
        PublicTradeEvent publicTrade = record.value();
        if (publicTrade == null) {
            return;
        }

        TradeEvent trade;
        String symbol;
        try {
            trade = tradeEventMapper.toTradeEvent(publicTrade);
            symbol = CandleKey.normalizeSymbol(trade.symbol());
            validateRecordKey(record.key(), symbol);
            if (!symbolRegistryService.isEnabled(symbol)) {
                log.warn("Rejected disabled or unknown symbol trade event: {}", symbol);
                return;
            }
            if (isDuplicateOrOldTrade(symbol, trade)) {
                return;
            }
        } catch (IllegalArgumentException ex) {
            log.warn("Rejected invalid trade event: {}", ex.getMessage());
            return;
        }

        Instant now = Instant.ofEpochMilli(context.currentSystemTimeMs());
        Integer partition = context.recordMetadata().map(metadata -> metadata.partition()).orElse(null);
        Long offset = context.recordMetadata().map(metadata -> metadata.offset()).orElse(null);

        CandlePeriod period = CandlePeriod.M1;
        Instant openTime = period.floor(trade.tradeTime());
        String candleKey = productKey + "|" + CandleKey.of(symbol, period, openTime).value();
        Long closedThrough = closedWatermarkStore.get(sequenceKey(symbol));
        if (closedThrough != null && period.closeTime(openTime).toEpochMilli() <= closedThrough) {
            rememberTrade(symbol, trade);
            return;
        }
        CandleAccumulator accumulator = Optional.ofNullable(candleStore.get(candleKey))
                .orElseGet(() -> CandleAccumulator.create(symbol, period, openTime));

        CandleMath.apply(accumulator, trade, now);
        candleStore.put(candleKey, accumulator);

        CandleSnapshot snapshot = accumulator.snapshot(now, partition, offset);
        snapshot.setStatus(CandleStatus.PARTIAL);
        dirtyStore.put(candleKey, snapshot);
        if (hotCache != null) {
            hotCache.put(snapshot.toUpdatedEvent(now));
        }
        context.forward(new Record<>(symbol, snapshot.toUpdatedEvent(now), record.timestamp()));
        rememberTrade(symbol, trade);
    }

    private void rememberTrade(String symbol, TradeEvent trade) {
        dedupeStore.put(dedupeKey(symbol, trade), trade.tradeTime().toEpochMilli());
        if (trade.sequence() >= 0) {
            String key = sequenceKey(symbol);
            sequenceStore.put(key, Math.max(trade.sequence(), Optional.ofNullable(sequenceStore.get(key)).orElse(-1L)));
        }
    }

    private void validateRecordKey(String recordKey, String symbol) {
        if (recordKey == null || recordKey.isBlank()) {
            throw new IllegalArgumentException("Kafka record key must be the normalized symbol");
        }
        String normalizedKey = CandleKey.normalizeSymbol(recordKey);
        if (!normalizedKey.equals(symbol)) {
            throw new IllegalArgumentException("Kafka record key must equal trade symbol; key=" + recordKey + ", symbol=" + symbol);
        }
    }

    private boolean isDuplicateOrOldTrade(String symbol, TradeEvent trade) {
        if (dedupeStore.get(dedupeKey(symbol, trade)) != null) {
            return true;
        }
        Long lastSequence = sequenceStore.get(sequenceKey(symbol));
        return lastSequence != null && trade.sequence() <= lastSequence;
    }

    private String dedupeKey(String symbol, TradeEvent trade) {
        return productKey + "|" + symbol + "|" + trade.idempotencyKey();
    }

    private String sequenceKey(String symbol) {
        return productKey + "|" + symbol;
    }

    private void flushDirtyCandles(long timestamp) {
        int maxBatchSize = properties.getFlush().getMaxBatchSize();
        List<CandleSnapshot> batch = new ArrayList<>(maxBatchSize);
        List<String> keys = new ArrayList<>(maxBatchSize);
        Instant flushTime = Instant.ofEpochMilli(timestamp);
        try (KeyValueIterator<String, CandleSnapshot> iterator = dirtyStore.all()) {
            while (iterator.hasNext()) {
                KeyValue<String, CandleSnapshot> item = iterator.next();
                CandleSnapshot snapshot = item.value;
                // 数据库只接收已经结束的时间桶；未关闭快照留在 dirtyStore，等待下一次状态更新。
                if (snapshot == null || snapshot.getCloseTime() == null
                        || snapshot.getCloseTime().isAfter(flushTime)) {
                    continue;
                }
                snapshot.setStatus(CandleStatus.CLOSED);
                batch.add(snapshot);
                keys.add(item.key);
                if (batch.size() >= maxBatchSize) {
                    flushBatch(batch, keys);
                }
            }
            flushBatch(batch, keys);
        } catch (Exception ex) {
            log.error("Failed to flush dirty candles to PostgreSQL; state store keeps them for retry", ex);
        }
    }

    /**
     * Deletes dirty markers only after PostgreSQL accepts the batch. If the write fails, the dirty
     * store keeps the snapshots and the next scheduled flush retries them.
     */
    private void flushBatch(List<CandleSnapshot> batch, List<String> keys) {
        if (batch.isEmpty()) {
            return;
        }
        candleSink.upsertBatch(List.copyOf(batch));
        Instant emittedAt = Instant.ofEpochMilli(context.currentSystemTimeMs());
        for (int index = 0; index < keys.size(); index++) {
            String key = keys.get(index);
            CandleSnapshot snapshot = batch.get(index);
            CandleUpdatedEvent event = snapshot.toUpdatedEvent(emittedAt);
            String watermarkKey = sequenceKey(snapshot.getSymbol());
            long closeTime = snapshot.getCloseTime().toEpochMilli();
            closedWatermarkStore.put(watermarkKey,
                    Math.max(closeTime, Optional.ofNullable(closedWatermarkStore.get(watermarkKey)).orElse(-1L)));
            if (hotCache != null) {
                hotCache.put(event);
            }
            context.forward(new Record<>(snapshot.getSymbol(), event, emittedAt.toEpochMilli()));
            dirtyStore.delete(key);
        }
        batch.clear();
        keys.clear();
    }

    private void cleanupDedupeStore(long timestamp) {
        long cutoff = timestamp - properties.getStream().getDedupeRetention().toMillis();
        int maxEntries = properties.getStream().getDedupeCleanupMaxEntries();
        int scanned = 0;
        try (KeyValueIterator<String, Long> iterator = dedupeStore.all()) {
            while (iterator.hasNext() && scanned < maxEntries) {
                KeyValue<String, Long> item = iterator.next();
                if (item.value != null && item.value < cutoff) {
                    dedupeStore.delete(item.key);
                }
                scanned++;
            }
        }
        scanned = 0;
        try (KeyValueIterator<String, CandleAccumulator> iterator = candleStore.all()) {
            while (iterator.hasNext() && scanned < maxEntries) {
                KeyValue<String, CandleAccumulator> item = iterator.next();
                if (item.value != null && item.value.getCloseTime() != null
                        && item.value.getCloseTime().toEpochMilli() < cutoff
                        && dirtyStore.get(item.key) == null) {
                    candleStore.delete(item.key);
                }
                scanned++;
            }
        }
    }
}
