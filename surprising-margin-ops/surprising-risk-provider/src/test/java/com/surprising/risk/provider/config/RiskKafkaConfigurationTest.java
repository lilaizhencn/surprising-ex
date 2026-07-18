package com.surprising.risk.provider.config;

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

class RiskKafkaConfigurationTest {

    @Test
    void consumerUsesReplaySafeBatchAckSettings() {
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setBootstrapServers("kafka-g:9092");
        properties.getKafka().setGroupId("risk-test-group");
        properties.getKafka().setConcurrency(3);
        properties.getKafka().setMaxPollRecords(222);

        RiskKafkaConfiguration configuration = new RiskKafkaConfiguration();
        var consumerFactory = (DefaultKafkaConsumerFactory<String, String>)
                configuration.riskConsumerFactory(properties);
        var listenerFactory = configuration.riskKafkaListenerContainerFactory(consumerFactory, properties);

        Map<String, Object> config = consumerFactory.getConfigurationProperties();
        assertThat(config).containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-g:9092");
        assertThat(config).containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "risk-test-group");
        assertThat(config).containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        assertThat(config).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        assertThat(config).containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 222);
        assertThat(config).containsEntry(
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                CooperativeStickyAssignor.class.getName());
        assertThat(listenerFactory.isBatchListener()).isTrue();
        assertThat(listenerFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.BATCH);
    }

    @Test
    void producerUsesDurableIdempotentSettings() {
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setBootstrapServers("kafka-g:9092");

        var factory = (DefaultKafkaProducerFactory<String, String>)
                new RiskKafkaConfiguration().riskProducerFactory(properties);

        Map<String, Object> config = factory.getConfigurationProperties();
        assertThat(config).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-g:9092");
        assertThat(config).containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
        assertThat(config).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        assertThat(config).containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        assertThat(config).containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    }

    @Test
    void defaultsToLegacyPerpTopicsUntilProductTopicsAreEnabled() {
        RiskProperties properties = new RiskProperties();

        assertThat(properties.getKafka().getGroupId()).isEqualTo("surprising-risk-v1");
        assertThat(properties.getKafka().getPositionEventsTopic())
                .isEqualTo("surprising.account.position.events.v1");
        assertThat(properties.getKafka().getAccountRiskEventsTopic())
                .isEqualTo("surprising.risk.account.events.v1");
        assertThat(properties.getKafka().getPositionRiskEventsTopic())
                .isEqualTo("surprising.risk.position.events.v1");
        assertThat(properties.getKafka().getLiquidationCandidatesTopic())
                .isEqualTo("surprising.perp.liquidation.candidates.v1");
    }

    @Test
    void canResolveRiskTopicsAndGroupFromProductLine() {
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);

        assertThat(properties.getKafka().getGroupId())
                .isEqualTo("surprising-linear-delivery-risk-v1");
        assertThat(properties.getKafka().getPositionEventsTopic())
                .isEqualTo("surprising.linear-delivery.account.position.events.v1");
        assertThat(properties.getKafka().getAccountRiskEventsTopic())
                .isEqualTo("surprising.linear-delivery.risk.account.events.v1");
        assertThat(properties.getKafka().getPositionRiskEventsTopic())
                .isEqualTo("surprising.linear-delivery.risk.position.events.v1");
        assertThat(properties.getKafka().getLiquidationCandidatesTopic())
                .isEqualTo("surprising.linear-delivery.liquidation.candidates.v1");
    }
}
