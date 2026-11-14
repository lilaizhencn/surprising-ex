package com.surprising.funding.provider.config;

import com.surprising.gateway.provider.config.KafkaBootstrapServers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
public class FundingKafkaConfiguration {

    @Bean
    public ProducerFactory<String, Object> fundingProducerFactory(
            @Qualifier("gatewaySharedJsonProducerFactory") ProducerFactory<String, Object> shared) {
        return KafkaBootstrapServers.nonDisposableDelegate(shared);
    }

    @Bean
    public KafkaTemplate<String, Object> fundingKafkaTemplate(
            @Qualifier("fundingProducerFactory") ProducerFactory<String, Object> fundingProducerFactory) {
        return new KafkaTemplate<>(fundingProducerFactory);
    }
}
