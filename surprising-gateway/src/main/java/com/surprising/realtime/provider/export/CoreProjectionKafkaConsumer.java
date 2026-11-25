package com.surprising.realtime.provider.export;

import com.surprising.aeron.protocol.export.CoreProjectionBatchEvent;
import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.gateway.provider.config.KafkaBootstrapServers;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.provider.export.service.CommittedOrderProjectionService;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 无状态 Gateway 投影消费者：直接消费 Core 异步推送到 Kafka 的 {@link CoreProjectionBatchEvent}，
 * 并幂等落库到 PostgreSQL 读模型，彻底取代 Gateway 内嵌 Aeron Archive 重放核心。
 */
@Component("tradeExport")
@ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class CoreProjectionKafkaConsumer implements SmartLifecycle, HealthIndicator {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProductLine product;
    private final String bootstrapServers;
    private final CommittedOrderProjectionService orders;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String state = "STOPPED";
    private volatile Thread worker;
    private volatile KafkaConsumer<String, String> activeConsumer;

    public CoreProjectionKafkaConsumer(
            CandlestickProperties candles,
            CommittedOrderProjectionService orders,
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String defaultBootstrapServers) {
        this.product = candles.getProductLine();
        this.orders = orders;
        this.bootstrapServers = KafkaBootstrapServers.resolve(
                candles.getKafka().getBootstrapServers(), defaultBootstrapServers);
    }

    @Override
    public synchronized void start() {
        if (worker != null && worker.isAlive()) return;
        running.set(true);
        state = "STARTING";
        worker = new Thread(this::consumeLoop, "core-projection-consumer-" + product.name().toLowerCase());
        worker.setDaemon(true);
        worker.start();
    }

    private void consumeLoop() {
        String topic = CoreProjectionBatchEvent.projectionTopic(product);
        String groupId = "surprising-gateway-projection-" + product.name().toLowerCase();
        try {
            while (running.get()) {
                try (KafkaConsumer<String, String> consumer = createConsumer(groupId)) {
                    this.activeConsumer = consumer;
                    consumer.subscribe(List.of(topic));
                    state = "RUNNING";
                    log.info("CoreProjectionKafkaConsumer started productLine={} topic={} bootstrapServers={}",
                            product, topic, bootstrapServers);
                    while (running.get()) {
                        var records = consumer.poll(Duration.ofMillis(250));
                        if (records.isEmpty()) continue;
                        for (ConsumerRecord<String, String> record : records) {
                            applyRecord(record.value());
                        }
                        consumer.commitSync();
                    }
                } catch (WakeupException expected) {
                    if (!running.get()) break;
                } catch (Exception failure) {
                    state = "RETRYING";
                    log.error("CoreProjectionKafkaConsumer failed productLine={}; retrying in 2s", product, failure);
                    if (running.get()) LockSupport.parkNanos(Duration.ofSeconds(2).toNanos());
                } finally {
                    this.activeConsumer = null;
                }
            }
        } finally {
            running.set(false);
            state = "STOPPED";
        }
    }

    void applyRecord(String json) {
        if (json == null || json.isBlank()) return;
        CoreProjectionBatchEvent event = MAPPER.readValue(json, CoreProjectionBatchEvent.class);
        if (event.product() != product) {
            throw new IllegalArgumentException("Cross-product projection event: " + event.product() + " != " + product);
        }
        orders.persist(
                product,
                event.orderChanges(),
                event.commandResults(),
                event.exportSequence(),
                event.algoChanges(),
                event.liquidationChanges(),
                event.balanceFlows(),
                event.feeObservations());
        for (var funding : event.fundingPages()) {
            orders.persistFunding(funding);
        }
        for (var delivery : event.deliveryPages()) {
            orders.persistDelivery(delivery);
        }
        if (event.statistics() != null) {
            var stats = event.statistics();
            orders.statistics().publish(product, stats.activity(), stats.clusterPosition(), stats.occurredAt());
        }
    }

    private KafkaConsumer<String, String> createConsumer(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100");
        return new KafkaConsumer<>(props);
    }

    @Override
    public synchronized void stop() {
        running.set(false);
        KafkaConsumer<String, String> consumer = activeConsumer;
        if (consumer != null) consumer.wakeup();
        Thread current = worker;
        if (current == null) return;
        LockSupport.unpark(current);
        try {
            current.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        worker = null;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE;
    }

    @Override
    public Health health() {
        return switch (state) {
            case "RUNNING", "STARTING" -> Health.up().withDetail("state", state).build();
            case "RETRYING" -> Health.down().withDetail("state", state).build();
            default -> Health.unknown().withDetail("state", state).build();
        };
    }
}
