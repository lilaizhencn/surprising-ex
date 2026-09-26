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

    public RecentTradeQueryService(CandlestickProperties properties,
                                   PublicTradeEventMapper tradeMapper, ObjectMapper mapper) {
        this.properties = properties;
        this.tradeMapper = tradeMapper;
        this.mapper = mapper;
    }

    public List<RecentTrade> recent(String symbol, int limit) {
        if (symbol == null || !symbol.matches("[A-Za-z0-9][A-Za-z0-9-]{1,63}"))
            throw new IllegalArgumentException("invalid symbol");
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be in [1,50]");
        String normalized = symbol.toUpperCase(java.util.Locale.ROOT);
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "recent-trades-query-" + java.util.UUID.randomUUID());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_SCAN);
        String topic = properties.getKafka().getTradeTopic();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            var partitions = consumer.partitionsFor(topic, Duration.ofSeconds(2));
            if (partitions == null || partitions.isEmpty()) return List.of();
            int partition = Utils.toPositive(Utils.murmur2(normalized.getBytes(StandardCharsets.UTF_8)))
                    % partitions.size();
            TopicPartition selected = new TopicPartition(topic, partition);
            consumer.assign(List.of(selected));
            long end = consumer.endOffsets(List.of(selected), Duration.ofSeconds(2)).get(selected);
            long beginning = consumer.beginningOffsets(List.of(selected), Duration.ofSeconds(2)).get(selected);
            consumer.seek(selected, Math.max(beginning, end - MAX_SCAN));
            List<RecentTrade> found = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (consumer.position(selected) < end && System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(250));
                for (var record : records.records(selected)) {
                    if (!normalized.equals(record.key())) continue;
                    PublicTradeEvent event = mapper.readValue(record.value(), PublicTradeEvent.class);
                    var trade = tradeMapper.toTradeEvent(event);
                    found.add(new RecentTrade(trade.tradeId(), trade.sequence(), normalized,
                            trade.side().name(), trade.price(), trade.quantity(), event.quantitySteps(), trade.tradeTime()));
                }
            }
            return found.stream().sorted(Comparator.comparingLong(RecentTrade::sequence).reversed())
                    .limit(limit).toList();
        }
    }

    public record RecentTrade(String tradeId, long sequence, String symbol, String side,
                              BigDecimal price, BigDecimal quantity, long quantitySteps, Instant eventTime) {}
}
