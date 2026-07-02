package com.surprising.price.mark.config;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.util.ReflectionTestUtils;

class MarkKafkaConfigurationTest {

    @Test
    void producerUsesDurableIdempotentSettings() {
        MarkPriceProperties properties = new MarkPriceProperties();
        properties.getKafka().setBootstrapServers("kafka-price:9092");

        var factory = (DefaultKafkaProducerFactory<String, Object>)
                new MarkKafkaConfiguration().markProducerFactory(properties);

        Map<String, Object> config = factory.getConfigurationProperties();
        assertThat(config).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-price:9092");
        assertThat(config).containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
        assertThat(config).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        assertThat(config).containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        assertThat(config).containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        assertThat(config).containsEntry(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
    }

    @Test
    void consumerUsesLiveFeedRecordAckSettings() {
        MarkPriceProperties properties = new MarkPriceProperties();
        properties.getKafka().setBootstrapServers("kafka-price:9092");
        properties.getKafka().setGroupId("mark-test-group");
        properties.getKafka().setConcurrency(3);
        properties.getKafka().setMaxPollRecords(250);

        MarkKafkaConfiguration configuration = new MarkKafkaConfiguration();
        var consumerFactory = (DefaultKafkaConsumerFactory<String, String>)
                configuration.markConsumerFactory(properties);
        var listenerFactory = configuration.kafkaListenerContainerFactory(consumerFactory, properties);

        Map<String, Object> config = consumerFactory.getConfigurationProperties();
        assertThat(config).containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-price:9092");
        assertThat(config).containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "mark-test-group");
        assertThat(config).containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        assertThat(config).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        assertThat(config).containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 250);
        assertThat(config).containsEntry(
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                CooperativeStickyAssignor.class.getName());
        assertThat(ReflectionTestUtils.getField(listenerFactory, "concurrency")).isEqualTo(3);
        assertThat(listenerFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);
    }
}
