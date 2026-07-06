package com.surprising.funding.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

class FundingKafkaConfigurationTest {

    @Test
    void defaultsToLegacyPerpTopicUntilProductTopicsAreEnabled() {
        FundingProperties properties = new FundingProperties();

        assertThat(properties.getKafka().getFundingRateTopic())
                .isEqualTo("surprising.perp.funding.rate.v1");
    }

    @Test
    void canResolveFundingTopicFromProductLine() {
        FundingProperties properties = new FundingProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);

        assertThat(properties.getKafka().getFundingRateTopic())
                .isEqualTo("surprising.inverse-perp.funding.rate.v1");
    }

    @Test
    void producerUsesDurableIdempotentSettings() {
        FundingProperties properties = new FundingProperties();
        properties.getKafka().setBootstrapServers("kafka-h:9092");

        var factory = (DefaultKafkaProducerFactory<String, String>)
                new FundingKafkaConfiguration().fundingProducerFactory(properties);

        Map<String, Object> config = factory.getConfigurationProperties();
        assertThat(config).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-h:9092");
        assertThat(config).containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
        assertThat(config).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        assertThat(config).containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        assertThat(config).containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    }
}
