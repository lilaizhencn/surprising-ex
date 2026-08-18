package com.surprising.trading.matching.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class MatchingKafkaConfiguration {

    @Bean("matchingMarketDataProducerFactory")
    public ProducerFactory<String, String> matchingMarketDataProducerFactory(MatchingProperties properties) {
        MatchingProperties.MarketData marketData = properties.getMarketData();
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, properties.getKafka().getClientId() + "-publisher");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "1");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        config.put(ProducerConfig.RETRIES_CONFIG, 0);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        config.put(ProducerConfig.LINGER_MS_CONFIG, marketData.getLingerMs());
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, marketData.getProducerBatchSize());
        config.put(ProducerConfig.BUFFER_MEMORY_CONFIG, marketData.getBufferMemoryBytes());
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, marketData.getMaxBlockMs());
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, marketData.getDeliveryTimeoutMs());
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, marketData.getRequestTimeoutMs());
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean("matchingMarketDataKafkaTemplate")
    public KafkaTemplate<String, String> matchingMarketDataKafkaTemplate(
            @Qualifier("matchingMarketDataProducerFactory") ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public ConsumerFactory<String, byte[]> matchingCoreEventsConsumerFactory(MatchingProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getKafka().getGroupId());
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, properties.getKafka().getClientId() + "-core-events");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.getKafka().getMaxPollRecords());
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean("matchingCoreEventsKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> matchingCoreEventsKafkaListenerContainerFactory(
            ConsumerFactory<String, byte[]> matchingCoreEventsConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(matchingCoreEventsConsumerFactory);
        factory.setConcurrency(1);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        factory.setCommonErrorHandler(new org.springframework.kafka.listener.DefaultErrorHandler(
                new FixedBackOff(1_000, Long.MAX_VALUE)));
        return factory;
    }
}
