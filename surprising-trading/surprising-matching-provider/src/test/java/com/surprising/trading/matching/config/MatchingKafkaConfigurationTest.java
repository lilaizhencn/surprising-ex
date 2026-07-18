package com.surprising.trading.matching.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.matching.service.MatchingPartitionAssignmentGuard;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.util.ReflectionTestUtils;

class MatchingKafkaConfigurationTest {

    @Test
    void producerUsesDurableIdempotentSettings() {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setBootstrapServers("kafka-b:9092");

        var factory = (DefaultKafkaProducerFactory<String, String>)
                new MatchingKafkaConfiguration().matchingProducerFactory(properties);

        Map<String, Object> config = factory.getConfigurationProperties();
        assertThat(config).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-b:9092");
        assertThat(config).containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
        assertThat(config).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        assertThat(config).containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        assertThat(config).containsEntry(ProducerConfig.LINGER_MS_CONFIG, 2);
        assertThat(config).containsEntry(ProducerConfig.BATCH_SIZE_CONFIG, 65_536);
        assertThat(config).containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    }

    @Test
    void marketDataProducerIsLossyAndIsolatedFromDurableBusinessPublishing() {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setBootstrapServers("kafka-market:9092");
        properties.getKafka().setClientId("matching-node-market");
        properties.getMarketData().setMaxBlockMs(7L);

        var factory = (DefaultKafkaProducerFactory<String, String>)
                new MatchingKafkaConfiguration().matchingMarketDataProducerFactory(properties);

        Map<String, Object> config = factory.getConfigurationProperties();
        assertThat(config).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-market:9092");
        assertThat(config).containsEntry(ProducerConfig.CLIENT_ID_CONFIG,
                "matching-node-market-public-depth");
        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "1");
        assertThat(config).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        assertThat(config).containsEntry(ProducerConfig.RETRIES_CONFIG, 0);
        assertThat(config).containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        assertThat(config).containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 7L);
        assertThat(config).containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 500);
        assertThat(config).containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 300);
    }

    @Test
    void consumerUsesReplaySafeRecordAckSettings() {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setBootstrapServers("kafka-c:9092");
        properties.getKafka().setGroupId("matching-test-group");
        properties.getKafka().setClientId("matching-node-a");
        properties.getKafka().setMaxPollRecords(321);

        MatchingKafkaConfiguration configuration = new MatchingKafkaConfiguration();
        var consumerFactory = (DefaultKafkaConsumerFactory<String, String>)
                configuration.matchingConsumerFactory(properties);
        var listenerFactory = configuration.matchingKafkaListenerContainerFactory(
                consumerFactory,
                properties,
                new MatchingPartitionAssignmentGuard(properties, mock(ConfigurableApplicationContext.class)));

        Map<String, Object> config = consumerFactory.getConfigurationProperties();
        assertThat(config).containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-c:9092");
        assertThat(config).containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "matching-test-group");
        assertThat(config).containsEntry(ConsumerConfig.CLIENT_ID_CONFIG, "matching-node-a");
        assertThat(config).containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        assertThat(config).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        assertThat(config).containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 321);
        assertThat(config).containsEntry(
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                CooperativeStickyAssignor.class.getName());
        assertThat(listenerFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(ReflectionTestUtils.getField(listenerFactory, "concurrency")).isEqualTo(4);
        assertThat(listenerFactory.getContainerProperties().getConsumerRebalanceListener())
                .isInstanceOf(MatchingPartitionAssignmentGuard.class);
    }

    @Test
    void defaultsToLegacyPerpTopicsUntilProductTopicsAreEnabled() {
        MatchingProperties properties = new MatchingProperties();

        assertThat(properties.getKafka().getGroupId()).isEqualTo("surprising-matching-v1");
        assertThat(properties.getKafka().getOrderCommandsTopic())
                .isEqualTo("surprising.perp.order.commands.v1");
        assertThat(properties.getKafka().getMatchResultsTopic())
                .isEqualTo("surprising.perp.match.results.v1");
        assertThat(properties.getKafka().getMatchTradesTopic())
                .isEqualTo("surprising.perp.match.trades.v1");
        assertThat(properties.getKafka().getOrderBookDepthTopic())
                .isEqualTo("surprising.perp.orderbook.depth.v1");
        assertThat(properties.getKafka().getConcurrency()).isEqualTo(4);
        assertThat(properties.getEngine().getMatchingEngines()).isEqualTo(4);
        assertThat(properties.getEngine().getRiskEngines()).isEqualTo(2);
    }

    @Test
    void canResolveMatchingTopicsAndGroupFromProductLine() {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);

        assertThat(properties.getKafka().getGroupId())
                .isEqualTo("surprising-inverse-perp-matching-v1");
        assertThat(properties.getKafka().getOrderCommandsTopic())
                .isEqualTo("surprising.inverse-perp.order.commands.v1");
        assertThat(properties.getKafka().getMatchResultsTopic())
                .isEqualTo("surprising.inverse-perp.match.results.v1");
        assertThat(properties.getKafka().getMatchTradesTopic())
                .isEqualTo("surprising.inverse-perp.match.trades.v1");
        assertThat(properties.getKafka().getOrderBookDepthTopic())
                .isEqualTo("surprising.inverse-perp.orderbook.depth.v1");
    }
}
