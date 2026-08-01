package com.surprising.price.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

class MarkPriceConsumerConfigurationTest {

    @Test
    void cacheConsumerStartsFromLatestAndDoesNotReplayOldMarkPrices() {
        MarkPriceConsumerProperties properties = new MarkPriceConsumerProperties();
        properties.setBootstrapServers("kafka-price:9092");

        var factory = (DefaultKafkaConsumerFactory<String, String>)
                new MarkPriceConsumerConfiguration().markPriceCacheConsumerFactory(properties);

        assertThat(factory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    }
}
