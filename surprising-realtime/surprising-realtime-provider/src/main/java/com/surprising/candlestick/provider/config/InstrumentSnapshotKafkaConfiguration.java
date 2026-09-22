package com.surprising.candlestick.provider.config;

import com.surprising.instrument.api.kafka.InstrumentKafkaConsumerProperties;
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
        var config = InstrumentKafkaConsumerProperties.create(
                properties.getKafka().getBootstrapServers(),
                properties.getKafka().getInstrumentSnapshotGroupId(), null, 0);
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
