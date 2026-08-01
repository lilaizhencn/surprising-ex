package com.surprising.liquidation.provider.config;

import com.surprising.instrument.api.kafka.InstrumentKafkaConsumerProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 强平服务的 Instrument 增量事件消费者配置。
 */
@Configuration("liquidationInstrumentSnapshotKafkaConfiguration")
public class InstrumentSnapshotKafkaConfiguration {

    @Bean
    public ConsumerFactory<String, String> liquidationInstrumentSnapshotConsumerFactory(
            LiquidationProperties properties) {
        var config = InstrumentKafkaConsumerProperties.create(
                properties.getKafka().getBootstrapServers(),
                properties.getKafka().getInstrumentSnapshotGroupId(), null, 0);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean(name = "liquidationInstrumentSnapshotKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String>
    liquidationInstrumentSnapshotKafkaListenerContainerFactory(
            @Qualifier("liquidationInstrumentSnapshotConsumerFactory")
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
}
