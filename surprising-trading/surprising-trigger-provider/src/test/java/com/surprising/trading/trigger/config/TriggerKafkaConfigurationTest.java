package com.surprising.trading.trigger.config;

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

class TriggerKafkaConfigurationTest {

    @Test
    void propertiesExposeMarkPriceTriggerTopic() {
        TriggerProperties properties = new TriggerProperties();

        assertThat(properties.getKafka().getGroupId()).isEqualTo("surprising-trigger-v1");
        assertThat(properties.getKafka().getMarkPriceTopic()).isEqualTo("surprising.perp.mark.price.v1");
        assertThat(properties.getKafka().getPositionEventsTopic())
                .isEqualTo("surprising.account.position.events.v1");
    }

    @Test
    void canResolveTriggerTopicsAndGroupFromProductLine() {
        TriggerProperties properties = new TriggerProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);

        assertThat(properties.getKafka().getGroupId()).isEqualTo("surprising-inverse-perp-trigger-v1");
        assertThat(properties.getKafka().getMarkPriceTopic()).isEqualTo("surprising.inverse-perp.mark.price.v1");
        assertThat(properties.getKafka().getPositionEventsTopic())
                .isEqualTo("surprising.inverse-perp.account.position.events.v1");
    }

    @Test
    void consumerUsesReplaySafeRecordAckSettings() {
        TriggerProperties properties = new TriggerProperties();
        properties.getKafka().setBootstrapServers("kafka-t:9092");
        properties.getKafka().setGroupId("trigger-test-group");
        properties.getKafka().setConcurrency(3);
        properties.getKafka().setMaxPollRecords(123);

        TriggerKafkaConfiguration configuration = new TriggerKafkaConfiguration();
        var consumerFactory = (DefaultKafkaConsumerFactory<String, String>)
                configuration.triggerConsumerFactory(properties);
        var listenerFactory = configuration.triggerKafkaListenerContainerFactory(consumerFactory, properties);

        Map<String, Object> config = consumerFactory.getConfigurationProperties();
        assertThat(config).containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-t:9092");
        assertThat(config).containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "trigger-test-group");
        assertThat(config).containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        assertThat(config).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        assertThat(config).containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 123);
        assertThat(config).containsEntry(
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                CooperativeStickyAssignor.class.getName());
        assertThat(listenerFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);
    }

    @Test
    void producerUsesConfiguredBrokerAndIdempotentOutboxSettings() {
        TriggerProperties properties = new TriggerProperties();
        properties.getKafka().setBootstrapServers("kafka-t:9092");
        TriggerKafkaConfiguration configuration = new TriggerKafkaConfiguration();

        var producerFactory = (DefaultKafkaProducerFactory<String, String>)
                configuration.triggerProducerFactory(properties);

        Map<String, Object> config = producerFactory.getConfigurationProperties();
        assertThat(config).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-t:9092");
        assertThat(config).containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
        assertThat(config).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        assertThat(config).containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        assertThat(config).containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
    }
}
