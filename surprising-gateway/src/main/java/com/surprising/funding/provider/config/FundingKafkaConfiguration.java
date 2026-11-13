package com.surprising.funding.provider.config;

import java.util.HashMap;
import java.util.Map;
import com.surprising.gateway.provider.config.KafkaBootstrapServers;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
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

    @Bean
    public ProducerFactory<String, Object> fundingProducerFactory(FundingProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KafkaBootstrapServers.resolve(properties.getKafka().getBootstrapServers(), defaultBootstrapServers));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> fundingKafkaTemplate(
            @Qualifier("fundingProducerFactory") ProducerFactory<String, Object> fundingProducerFactory) {
        return new KafkaTemplate<>(fundingProducerFactory);
    }
}
