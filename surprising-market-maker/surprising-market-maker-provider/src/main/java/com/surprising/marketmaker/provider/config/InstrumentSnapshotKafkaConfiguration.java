package com.surprising.marketmaker.provider.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

/** 合约增量事件的独立消费配置，避免与做市业务消费互相影响。 */
@Configuration
public class InstrumentSnapshotKafkaConfiguration {

    @Bean
    public ConsumerFactory<String, String> marketMakerInstrumentSnapshotConsumerFactory(
            MarketMakerProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getKafka().getInstrumentSnapshotGroupId());
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "surprising-market-maker-instrument-snapshot");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean(name = "marketMakerInstrumentSnapshotKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String>
    marketMakerInstrumentSnapshotKafkaListenerContainerFactory(
            ConsumerFactory<String, String> marketMakerInstrumentSnapshotConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(marketMakerInstrumentSnapshotConsumerFactory);
        factory.setCommonErrorHandler(new org.springframework.kafka.listener.DefaultErrorHandler(
                new org.springframework.util.backoff.FixedBackOff(1_000L, Long.MAX_VALUE)));
        return factory;
    }
}
