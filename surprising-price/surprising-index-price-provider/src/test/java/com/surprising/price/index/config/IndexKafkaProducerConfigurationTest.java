package com.surprising.price.index.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

class IndexKafkaProducerConfigurationTest {

    @Test
    void defaultsToLegacyPerpTopicsUntilProductTopicsAreEnabled() {
        IndexPriceProperties properties = new IndexPriceProperties();

        assertThat(properties.getKafka().getIndexPriceTopic()).isEqualTo("surprising.perp.index.price.v1");
        assertThat(properties.getKafka().getIndexComponentsTopic())
                .isEqualTo("surprising.perp.index.components.v1");
    }

    @Test
    void canResolveIndexTopicsFromProductLine() {
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);

        assertThat(properties.getKafka().getIndexPriceTopic())
                .isEqualTo("surprising.linear-delivery.index.price.v1");
        assertThat(properties.getKafka().getIndexComponentsTopic())
                .isEqualTo("surprising.linear-delivery.index.components.v1");
    }

    @Test
    void producerUsesDurableIdempotentSettings() {
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getKafka().setBootstrapServers("kafka-index:9092");

        var factory = (DefaultKafkaProducerFactory<String, Object>)
                new IndexKafkaProducerConfiguration().indexProducerFactory(properties);

        Map<String, Object> config = factory.getConfigurationProperties();
        assertThat(config).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-index:9092");
        assertThat(config).containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
        assertThat(config).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        assertThat(config).containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        assertThat(config).containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        assertThat(config).containsEntry(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
    }
}
