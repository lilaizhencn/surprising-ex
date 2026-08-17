package com.surprising.price.index.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import java.net.URI;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

class IndexKafkaProducerConfigurationTest {

    @Test
    void defaultsToLinearPerpetualTopics() {
        IndexPriceProperties properties = new IndexPriceProperties();

        assertThat(properties.getKafka().getPriceEventsTopic()).isEqualTo("surprising.linear-perp.price.events.v1");
    }

    @Test
    void canResolveIndexTopicsFromProductLine() {
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);

        assertThat(properties.getKafka().getPriceEventsTopic())
                .isEqualTo("surprising.linear-delivery.price.events.v1");
    }

    @Test
    void configuresOptionalExternalHttpProxy() {
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getHttp().setProxyEnabled(true);
        properties.getHttp().setProxyHost("127.0.0.1");
        properties.getHttp().setProxyPort(7897);

        var proxy = properties.getHttp().proxySelector().select(URI.create("https://api.example.com")).getFirst();

        assertThat(proxy.type()).isEqualTo(java.net.Proxy.Type.HTTP);
        assertThat(proxy.address().toString()).isEqualTo("/127.0.0.1:7897");
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
        assertThat(config).containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
        assertThat(config).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        assertThat(config).containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        assertThat(config).containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        assertThat(config).containsEntry(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, false);
    }

    @Test
    void cacheConsumerStartsFromLatestAndDoesNotReplayOldIndexPrices() {
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getKafka().setBootstrapServers("kafka-index:9092");

        var factory = (DefaultKafkaConsumerFactory<String, String>)
                new IndexKafkaProducerConfiguration().indexPriceCacheConsumerFactory(properties);

        assertThat(factory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    }
}
