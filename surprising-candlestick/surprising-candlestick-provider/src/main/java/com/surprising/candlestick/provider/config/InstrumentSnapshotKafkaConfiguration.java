package com.surprising.candlestick.provider.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * K 线服务的 Instrument 增量事件消费者配置。
 */
@Configuration
public class InstrumentSnapshotKafkaConfiguration {

    @Bean
    public ConsumerFactory<String, String> candlestickInstrumentSnapshotConsumerFactory(
            CandlestickProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getKafka().getInstrumentSnapshotGroupId());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean(name = "candlestickInstrumentSnapshotKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String>
    candlestickInstrumentSnapshotKafkaListenerContainerFactory(
            ConsumerFactory<String, String> candlestickInstrumentSnapshotConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(candlestickInstrumentSnapshotConsumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
}
