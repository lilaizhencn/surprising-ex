package com.surprising.candlestick.provider.aggregation;

import com.surprising.candlestick.api.model.CandlePeriod;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import com.surprising.candlestick.api.model.TradeEvent;
import com.surprising.candlestick.provider.config.CandlestickProperties;
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
 * Kafka Streams processor that turns keyed perpetual trades into candle snapshots.
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
    private final List<CandlePeriod> periods;

    private ProcessorContext<String, CandleUpdatedEvent> context;
    private KeyValueStore<String, CandleAccumulator> candleStore;
    private KeyValueStore<String, CandleSnapshot> dirtyStore;
    private KeyValueStore<String, Long> dedupeStore;
    private KeyValueStore<String, Long> sequenceStore;

    public CandleAggregationProcessor(CandlestickProperties properties, CandleSink candleSink,
                                      SymbolRegistryService symbolRegistryService,
                                      PublicTradeEventMapper tradeEventMapper) {
        this.properties = properties;
        this.candleSink = candleSink;
        this.symbolRegistryService = symbolRegistryService;
        this.tradeEventMapper = tradeEventMapper;
        this.periods = properties.getPeriods().stream()
                .map(CandlePeriod::fromCode)
                .toList();
    }

    @Override
    public void init(ProcessorContext<String, CandleUpdatedEvent> context) {
        this.context = context;
        this.candleStore = context.getStateStore(CandleStores.CANDLE_STORE);
        this.dirtyStore = context.getStateStore(CandleStores.DIRTY_STORE);
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

        // One accepted trade updates every configured interval, e.g. 1m, 5m, 1h, 1d.
        for (CandlePeriod period : periods) {
            Instant openTime = period.floor(trade.tradeTime());
            String candleKey = CandleKey.of(symbol, period, openTime).value();
            CandleAccumulator accumulator = Optional.ofNullable(candleStore.get(candleKey))
                    .orElseGet(() -> CandleAccumulator.create(symbol, period, openTime));

            CandleMath.apply(accumulator, trade, now);
            candleStore.put(candleKey, accumulator);

            CandleSnapshot snapshot = accumulator.snapshot(now, partition, offset);
            dirtyStore.put(candleKey, snapshot);
            context.forward(new Record<>(symbol, snapshot.toUpdatedEvent(now), record.timestamp()));
        }

        dedupeStore.put(dedupeKey(symbol, trade), trade.tradeTime().toEpochMilli());
        if (trade.sequence() >= 0) {
            sequenceStore.put(symbol, Math.max(trade.sequence(), Optional.ofNullable(sequenceStore.get(symbol)).orElse(-1L)));
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
        Long lastSequence = sequenceStore.get(symbol);
        return lastSequence != null && trade.sequence() <= lastSequence;
    }

    private String dedupeKey(String symbol, TradeEvent trade) {
        return symbol + "|" + trade.idempotencyKey();
    }

    private void flushDirtyCandles(long timestamp) {
        int maxBatchSize = properties.getFlush().getMaxBatchSize();
        List<CandleSnapshot> batch = new ArrayList<>(maxBatchSize);
        List<String> keys = new ArrayList<>(maxBatchSize);
        try (KeyValueIterator<String, CandleSnapshot> iterator = dirtyStore.all()) {
            while (iterator.hasNext()) {
                KeyValue<String, CandleSnapshot> item = iterator.next();
                batch.add(item.value);
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
        for (String key : keys) {
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
    }
}
