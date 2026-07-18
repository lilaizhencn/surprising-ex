package com.surprising.candlestick.provider.config;

import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import com.surprising.candlestick.provider.aggregation.CandleAccumulator;
import com.surprising.candlestick.provider.aggregation.CandleAggregationProcessor;
import com.surprising.candlestick.provider.aggregation.CandleSink;
import com.surprising.candlestick.provider.aggregation.CandleSnapshot;
import com.surprising.candlestick.provider.aggregation.CandleStores;
import com.surprising.candlestick.provider.service.PublicTradeEventMapper;
import com.surprising.candlestick.provider.service.SymbolRegistryService;
import com.surprising.trading.api.model.PublicTradeEvent;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.Stores;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.config.KafkaStreamsConfiguration;
import org.springframework.kafka.support.serializer.JsonSerde;

@Configuration
@EnableKafkaStreams
/**
 * Builds the Kafka Streams topology for perpetual K-line aggregation.
 *
 * <p>The topology consumes the shared trade topic, updates RocksDB-backed state stores, emits
 * candle update events, and periodically lets the processor flush dirty snapshots to PostgreSQL.</p>
 */
public class CandlestickStreamConfiguration {

    /**
     * Shared Streams configuration for all nodes in the same deployment group.
     */
    @Bean(name = "defaultKafkaStreamsConfig")
    public KafkaStreamsConfiguration defaultKafkaStreamsConfig(CandlestickProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, properties.getKafka().getApplicationId());
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, properties.getStream().getThreads());
        config.put(StreamsConfig.STATE_DIR_CONFIG, properties.getStream().getStateDir());
        config.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, properties.getStream().getCommitInterval().toMillis());
        config.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        config.put(StreamsConfig.ROCKSDB_CONFIG_SETTER_CLASS_CONFIG, CandlestickRocksDbConfig.class);
        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configureRocksDb(properties, config);
        return new KafkaStreamsConfiguration(config);
    }

    private void configureRocksDb(CandlestickProperties properties, Map<String, Object> config) {
        CandlestickProperties.Rocksdb rocksdb = properties.getRocksdb();
        config.put(CandlestickRocksDbConfig.BLOCK_CACHE_SIZE_BYTES_CONFIG, rocksdb.getBlockCacheSize().toBytes());
        config.put(CandlestickRocksDbConfig.WRITE_BUFFER_SIZE_BYTES_CONFIG, rocksdb.getWriteBufferSize().toBytes());
        config.put(CandlestickRocksDbConfig.WRITE_BUFFER_MANAGER_SIZE_BYTES_CONFIG, rocksdb.getWriteBufferManagerSize().toBytes());
        config.put(CandlestickRocksDbConfig.MAX_WRITE_BUFFER_NUMBER_CONFIG, rocksdb.getMaxWriteBufferNumber());
        config.put(CandlestickRocksDbConfig.BLOCK_SIZE_BYTES_CONFIG, rocksdb.getBlockSize().toBytes());
        config.put(CandlestickRocksDbConfig.MAX_BACKGROUND_JOBS_CONFIG, rocksdb.getMaxBackgroundJobs());
        config.put(CandlestickRocksDbConfig.TARGET_FILE_SIZE_BASE_BYTES_CONFIG, rocksdb.getTargetFileSizeBase().toBytes());
        config.put(CandlestickRocksDbConfig.BYTES_PER_SYNC_CONFIG, rocksdb.getBytesPerSync().toBytes());
        config.put(CandlestickRocksDbConfig.COMPRESSION_TYPE_CONFIG, rocksdb.getCompressionType());
        config.put(CandlestickRocksDbConfig.BLOOM_FILTER_ENABLED_CONFIG, rocksdb.isBloomFilterEnabled());
        config.put(CandlestickRocksDbConfig.BLOOM_FILTER_BITS_PER_KEY_CONFIG, rocksdb.getBloomFilterBitsPerKey());
    }

    /**
     * Wires state stores and the processor. The output stream is the input for WebSocket/fanout
     * services, not a direct client connection from this service.
     */
    @Bean
    public KStream<String, CandleUpdatedEvent> candlestickTopology(
            StreamsBuilder streamsBuilder,
            CandlestickProperties properties,
            CandleSink candleSink,
            SymbolRegistryService symbolRegistryService,
            PublicTradeEventMapper tradeEventMapper) {

        Serde<PublicTradeEvent> tradeSerde = jsonSerde(PublicTradeEvent.class);
        Serde<CandleUpdatedEvent> updateSerde = jsonSerde(CandleUpdatedEvent.class);
        Serde<CandleAccumulator> accumulatorSerde = jsonSerde(CandleAccumulator.class);
        Serde<CandleSnapshot> snapshotSerde = jsonSerde(CandleSnapshot.class);

        // Persistent stores are backed by RocksDB and restored from Kafka Streams changelog topics.
        streamsBuilder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(CandleStores.CANDLE_STORE),
                Serdes.String(),
                accumulatorSerde));
        streamsBuilder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(CandleStores.DIRTY_STORE),
                Serdes.String(),
                snapshotSerde));
        streamsBuilder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(CandleStores.DEDUPE_STORE),
                Serdes.String(),
                Serdes.Long()));
        streamsBuilder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(CandleStores.SEQUENCE_STORE),
                Serdes.String(),
                Serdes.Long()));

        KStream<String, CandleUpdatedEvent> updates = streamsBuilder
                .stream(properties.getKafka().getTradeTopic(), Consumed.with(Serdes.String(), tradeSerde))
                .process(() -> new CandleAggregationProcessor(properties, candleSink, symbolRegistryService,
                                tradeEventMapper),
                        Named.as("candlestick-aggregator"),
                        CandleStores.CANDLE_STORE,
                        CandleStores.DIRTY_STORE,
                        CandleStores.DEDUPE_STORE,
                        CandleStores.SEQUENCE_STORE);

        updates.to(properties.getKafka().getCandleTopic(), Produced.with(Serdes.String(), updateSerde));
        return updates;
    }

    private <T> JsonSerde<T> jsonSerde(Class<T> type) {
        JsonSerde<T> serde = new JsonSerde<>(type);
        serde.ignoreTypeHeaders();
        serde.noTypeInfo();
        return serde;
    }
}
