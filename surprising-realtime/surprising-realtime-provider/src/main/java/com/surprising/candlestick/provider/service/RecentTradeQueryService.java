package com.surprising.candlestick.provider.service;

import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.trading.api.model.PublicTradeEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.errors.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.utils.Utils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 查询当前产品线已提交的真实成交；只在 REST 查询边界读取 Kafka，不进入聚合热路径。 */
@Service
public class RecentTradeQueryService {
    private static final int MAX_SCAN = 500;
    private final CandlestickProperties properties;
    private final PublicTradeEventMapper tradeMapper;
    private final ObjectMapper mapper;
    // KafkaConsumer is not thread-safe: only the request holding this lock may access it.
    // No trade cache, extra consumer worker or consumer group is introduced.
    private final ReentrantLock consumerLock = new ReentrantLock();
    private final Supplier<Consumer<String, String>> consumerFactory;
    private Consumer<String, String> consumer;
    private boolean closed;
    private static final long QUERY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);

    @Autowired
    public RecentTradeQueryService(CandlestickProperties properties,
                                   PublicTradeEventMapper tradeMapper, ObjectMapper mapper) {
        this(properties, tradeMapper, mapper, () -> new KafkaConsumer<>(consumerConfig(properties)));
    }

    RecentTradeQueryService(CandlestickProperties properties, PublicTradeEventMapper tradeMapper,
                            ObjectMapper mapper, Supplier<Consumer<String, String>> consumerFactory) {
        this.properties = properties;
        this.tradeMapper = tradeMapper;
        this.mapper = mapper;
        this.consumerFactory = consumerFactory;
    }

    static Properties consumerConfig(CandlestickProperties properties) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "recent-trades-query-" + properties.getKafka().getProductLine());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_SCAN);
        config.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2000);
        return config;
    }

    public List<RecentTrade> recent(String instrumentId, int limit) {
        if (instrumentId == null || !com.surprising.product.api.InstrumentIds.valid(instrumentId))
            throw new IllegalArgumentException("invalid instrumentId");
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be in [1,50]");
        String normalized = instrumentId.toUpperCase(java.util.Locale.ROOT);
        long deadline = System.nanoTime() + QUERY_TIMEOUT_NANOS;
        boolean locked;
        try {
            locked = consumerLock.tryLock(QUERY_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("recent trade query interrupted", interrupted);
        }
        if (!locked) throw new TimeoutException("recent trade query is busy");
        try {
            if (closed) throw new IllegalStateException("recent trade query is closed");
            if (consumer == null) consumer = consumerFactory.get();
            String topic = properties.getKafka().getTradeTopic();
            var partitions = consumer.partitionsFor(topic, remaining(deadline));
            if (partitions == null || partitions.isEmpty()) return List.of();
            int partition = Utils.toPositive(Utils.murmur2(normalized.getBytes(StandardCharsets.UTF_8)))
                    % partitions.size();
            TopicPartition selected = new TopicPartition(topic, partition);
            consumer.assign(List.of(selected));
            long end = consumer.endOffsets(List.of(selected), remaining(deadline)).get(selected);
            long beginning = consumer.beginningOffsets(List.of(selected), remaining(deadline)).get(selected);
            consumer.seek(selected, Math.max(beginning, end - MAX_SCAN));
            List<RecentTrade> found = new ArrayList<>();
            while (consumer.position(selected, remaining(deadline)) < end) {
                var records = consumer.poll(remaining(deadline).compareTo(Duration.ofMillis(250)) < 0
                        ? remaining(deadline) : Duration.ofMillis(250));
                for (var record : records.records(selected)) {
                    if (record.offset() >= end || !normalized.equals(record.key())) continue;
                    PublicTradeEvent event = mapper.readValue(record.value(), PublicTradeEvent.class);
                    if (!normalized.equals(event.instrumentId()))
                        throw new IllegalStateException("trade instrument does not match Kafka key");
                    var trade = tradeMapper.toTradeEvent(event);
                    found.add(new RecentTrade(trade.tradeId(), trade.sequence(), normalized,
                            trade.side().name(), trade.price(), trade.quantity(), event.quantitySteps(), trade.tradeTime()));
                }
            }
            return found.stream().sorted(Comparator.comparingLong(RecentTrade::sequence).reversed())
                    .limit(limit).toList();
        } finally {
            consumerLock.unlock();
        }
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new TimeoutException("recent trade query timed out");
        return Duration.ofNanos(nanos);
    }

    @PreDestroy
    public void close() {
        // Shutdown shares the ownership lock; an in-flight query has a bounded deadline.
        consumerLock.lock();
        try {
            closed = true;
            if (consumer != null) {
                consumer.close(Duration.ofSeconds(2));
                consumer = null;
            }
        } finally {
            consumerLock.unlock();
        }
    }

    public record RecentTrade(String tradeId, long sequence, String instrumentId, String side,
                              BigDecimal price, BigDecimal quantity, long quantitySteps, Instant eventTime) {}
}
