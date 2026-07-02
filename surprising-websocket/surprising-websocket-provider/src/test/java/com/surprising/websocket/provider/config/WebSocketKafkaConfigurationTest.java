package com.surprising.websocket.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

class WebSocketKafkaConfigurationTest {

    @Test
    void consumerUsesPerNodeFanoutSettings() {
        WebSocketProperties properties = new WebSocketProperties();
        properties.getKafka().setBootstrapServers("kafka-a:9092");
        properties.getKafka().setGroupId("surprising-websocket-node-a");
        properties.getKafka().setConcurrency(4);
        properties.getKafka().setMaxPollRecords(250);

        WebSocketKafkaConfiguration configuration = new WebSocketKafkaConfiguration();
        var consumerFactory = (DefaultKafkaConsumerFactory<String, String>)
                configuration.webSocketConsumerFactory(properties);
        var listenerFactory = configuration.webSocketKafkaListenerContainerFactory(consumerFactory, properties);

        Map<String, Object> config = consumerFactory.getConfigurationProperties();
        assertThat(config).containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-a:9092");
        assertThat(config).containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "surprising-websocket-node-a");
        assertThat(config).containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        assertThat(config).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        assertThat(config).containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 250);
        assertThat(config).containsEntry(
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                CooperativeStickyAssignor.class.getName());
        assertThat(listenerFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);
    }
}
