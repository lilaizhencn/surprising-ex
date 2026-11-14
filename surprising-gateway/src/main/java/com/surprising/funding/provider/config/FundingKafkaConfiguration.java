package com.surprising.funding.provider.config;

import java.util.HashMap;
import java.util.Map;
import com.surprising.gateway.provider.config.KafkaBootstrapServers;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

@Configuration
public class FundingKafkaConfiguration {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String defaultBootstrapServers = "localhost:9092";

    public ProducerFactory<String, Object> fundingProducerFactory(FundingProperties properties) {
        return fundingProducerFactory(properties, null);
    }

    @Bean
    public ProducerFactory<String, Object> fundingProducerFactory(
            FundingProperties properties,
            @Autowired(required = false) @Qualifier("gatewaySharedJsonProducerFactory")
            ProducerFactory<String, Object> shared) {
        String resolved = KafkaBootstrapServers.resolve(properties.getKafka().getBootstrapServers(), defaultBootstrapServers);
        if (shared != null && resolved.equals(KafkaBootstrapServers.resolve(null, defaultBootstrapServers))) {
            return KafkaBootstrapServers.nonDisposableDelegate(shared);
        }
        return new DefaultKafkaProducerFactory<>(KafkaBootstrapServers.jsonProducerConfig(resolved));
    }

    @Bean
    public KafkaTemplate<String, Object> fundingKafkaTemplate(
            @Qualifier("fundingProducerFactory") ProducerFactory<String, Object> fundingProducerFactory) {
        return new KafkaTemplate<>(fundingProducerFactory);
    }
}
