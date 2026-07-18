package com.surprising.funding.provider.config;

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
import org.springframework.kafka.support.serializer.JsonSerializer;

class FundingKafkaConfigurationTest {

    @Test
    void defaultsToLegacyPerpTopicUntilProductTopicsAreEnabled() {
        FundingProperties properties = new FundingProperties();

        assertThat(properties.getKafka().getFundingRateTopic())
                .isEqualTo("surprising.perp.funding.rate.v1");
    }

    @Test
    void canResolveFundingTopicFromProductLine() {
        FundingProperties properties = new FundingProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);

        assertThat(properties.getKafka().isFundingProductLine()).isTrue();
        assertThat(properties.getKafka().getFundingRateTopic())
                .isEqualTo("surprising.inverse-perp.funding.rate.v1");
    }

    @Test
    void productTopicsDoNotCreateFundingTopicForNonFundingProductLine() {
        FundingProperties properties = new FundingProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);

        assertThat(properties.getKafka().isFundingProductLine()).isFalse();
        assertThat(properties.getKafka().getFundingRateTopic())
                .isEqualTo("surprising.perp.funding.rate.v1");
    }

    @Test
    void producerUsesDurableIdempotentSettings() {
        FundingProperties properties = new FundingProperties();
        properties.getKafka().setBootstrapServers("kafka-h:9092");

        var factory = (DefaultKafkaProducerFactory<String, Object>)
                new FundingKafkaConfiguration().fundingProducerFactory(properties);

        Map<String, Object> config = factory.getConfigurationProperties();
        assertThat(config).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-h:9092");
        assertThat(config).containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
        assertThat(config).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        assertThat(config).containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        assertThat(config).containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    }

    @Test
    void accountResultsUseDurableBatchConsumption() {
        FundingProperties properties = new FundingProperties();
        properties.getKafka().setBootstrapServers("kafka-h:9092");
        properties.getKafka().setCommandResultsConcurrency(6);
        properties.getKafka().setMaxPollRecords(250);
        FundingKafkaConfiguration configuration = new FundingKafkaConfiguration();
        var consumerFactory = (DefaultKafkaConsumerFactory<String, String>)
                configuration.fundingCommandResultsConsumerFactory(properties);
        var listenerFactory = configuration.fundingCommandResultsKafkaListenerContainerFactory(
                consumerFactory, properties);

        Map<String, Object> config = consumerFactory.getConfigurationProperties();
        assertThat(config).containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-h:9092");
        assertThat(config).containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        assertThat(config).containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 250);
        assertThat(config).containsEntry(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                CooperativeStickyAssignor.class.getName());
        assertThat(listenerFactory.isBatchListener()).isTrue();
        assertThat(listenerFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.BATCH);
    }
}
