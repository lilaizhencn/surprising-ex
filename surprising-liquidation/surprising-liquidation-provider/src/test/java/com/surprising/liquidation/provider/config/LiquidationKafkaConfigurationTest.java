package com.surprising.liquidation.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.util.ReflectionTestUtils;

class LiquidationKafkaConfigurationTest {

    @Test
    void candidateConsumerUsesReplaySafeBatchAckSettings() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setBootstrapServers("kafka-e:9092");
        properties.getKafka().setGroupId("liquidation-test-group");
        properties.getKafka().setCandidateConcurrency(32);
        properties.getKafka().setMatchResultConcurrency(8);
        properties.getKafka().setMaxPollRecords(333);

        LiquidationKafkaConfiguration configuration = new LiquidationKafkaConfiguration();
        var consumerFactory = (DefaultKafkaConsumerFactory<String, String>)
                configuration.liquidationConsumerFactory(properties);
        var candidateFactory = configuration.liquidationCandidateKafkaListenerContainerFactory(consumerFactory,
                properties);
        var matchFactory = configuration.liquidationMatchResultKafkaListenerContainerFactory(consumerFactory,
                properties);

        Map<String, Object> config = consumerFactory.getConfigurationProperties();
        assertThat(config).containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-e:9092");
        assertThat(config).containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "liquidation-test-group");
        assertThat(config).containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        assertThat(config).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        assertThat(config).containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 333);
        assertThat(config).containsEntry(
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                CooperativeStickyAssignor.class.getName());
        assertThat(ReflectionTestUtils.getField(candidateFactory, "concurrency")).isEqualTo(32);
        assertThat(ReflectionTestUtils.getField(candidateFactory, "batchListener")).isEqualTo(true);
        assertThat(candidateFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.BATCH);
        assertThat(ReflectionTestUtils.getField(matchFactory, "concurrency")).isEqualTo(8);
        assertThat(matchFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);
    }

    @Test
    void producerUsesDurableIdempotentSettings() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setBootstrapServers("kafka-f:9092");

        var factory = (DefaultKafkaProducerFactory<String, String>)
                new LiquidationKafkaConfiguration().liquidationProducerFactory(properties);

        Map<String, Object> config = factory.getConfigurationProperties();
        assertThat(config).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-f:9092");
        assertThat(config).containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
        assertThat(config).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        assertThat(config).containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        assertThat(config).containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    }

    @Test
    void defaultsToLegacyPerpTopicsUntilProductTopicsAreEnabled() {
        LiquidationProperties properties = new LiquidationProperties();

        assertThat(properties.getKafka().getGroupId()).isEqualTo("surprising-liquidation-v1");
        assertThat(properties.getKafka().getLiquidationCandidatesTopic())
                .isEqualTo("surprising.perp.liquidation.candidates.v1");
        assertThat(properties.getKafka().getMatchResultsTopic())
                .isEqualTo("surprising.perp.match.results.v1");
        assertThat(properties.getKafka().getOrderCommandsTopic())
                .isEqualTo("surprising.perp.order.commands.v1");
        assertThat(properties.getKafka().getOrderEventsTopic())
                .isEqualTo("surprising.perp.order.events.v1");
    }

    @Test
    void canResolveLiquidationTopicsAndGroupFromProductLine() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);

        assertThat(properties.getKafka().getGroupId())
                .isEqualTo("surprising-inverse-delivery-liquidation-v1");
        assertThat(properties.getKafka().getLiquidationCandidatesTopic())
                .isEqualTo("surprising.inverse-delivery.liquidation.candidates.v1");
        assertThat(properties.getKafka().getMatchResultsTopic())
                .isEqualTo("surprising.inverse-delivery.match.results.v1");
        assertThat(properties.getKafka().getOrderCommandsTopic())
                .isEqualTo("surprising.inverse-delivery.order.commands.v1");
        assertThat(properties.getKafka().getOrderEventsTopic())
                .isEqualTo("surprising.inverse-delivery.order.events.v1");
    }
}
