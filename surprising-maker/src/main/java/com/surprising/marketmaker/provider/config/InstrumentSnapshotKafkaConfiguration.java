package com.surprising.marketmaker.provider.config;

import com.surprising.instrument.api.kafka.InstrumentKafkaConsumerProperties;
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
        var config = InstrumentKafkaConsumerProperties.create(
                properties.getKafka().getBootstrapServers(),
                properties.getKafka().getInstrumentSnapshotGroupId(),
                "surprising-market-maker-instrument-snapshot", 500);
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
