package com.surprising.trading.matching.config;

import java.util.HashMap;
import java.util.Map;
import com.surprising.trading.matching.service.MatchingPartitionAssignmentGuard;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.producer.ProducerConfig;
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

@Configuration
public class MatchingKafkaConfiguration {

    @Bean
    public ProducerFactory<String, String> matchingProducerFactory(MatchingProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        config.put(ProducerConfig.LINGER_MS_CONFIG, 2);
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, 65_536);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> matchingKafkaTemplate(
            ProducerFactory<String, String> matchingProducerFactory) {
        return new KafkaTemplate<>(matchingProducerFactory);
    }

    @Bean("matchingMarketDataProducerFactory")
    public ProducerFactory<String, String> matchingMarketDataProducerFactory(MatchingProperties properties) {
        MatchingProperties.MarketData marketData = properties.getMarketData();
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, properties.getKafka().getClientId() + "-public-depth");
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
            @Qualifier("matchingMarketDataProducerFactory")
            ProducerFactory<String, String> matchingMarketDataProducerFactory) {
        return new KafkaTemplate<>(matchingMarketDataProducerFactory);
    }

    @Bean
    public ConsumerFactory<String, String> matchingConsumerFactory(MatchingProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getKafka().getGroupId());
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, properties.getKafka().getClientId());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.getKafka().getMaxPollRecords());
        config.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, CooperativeStickyAssignor.class.getName());
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> matchingKafkaListenerContainerFactory(
            ConsumerFactory<String, String> matchingConsumerFactory,
            MatchingProperties properties,
            MatchingPartitionAssignmentGuard partitionAssignmentGuard) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(matchingConsumerFactory);
        factory.setConcurrency(properties.getKafka().getConcurrency());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setConsumerRebalanceListener(partitionAssignmentGuard);
        return factory;
    }
}
