package com.surprising.trading.order.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

class TradingOrderKafkaConfigurationTest {

    @Test
    void producerUsesDurableIdempotentSettings() {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        properties.getKafka().setBootstrapServers("kafka-a:9092");

        var factory = (DefaultKafkaProducerFactory<String, String>)
                new TradingOrderKafkaConfiguration().orderProducerFactory(properties);

        Map<String, Object> config = factory.getConfigurationProperties();
        assertThat(config).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-a:9092");
        assertThat(config).containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
        assertThat(config).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        assertThat(config).containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        assertThat(config).containsEntry(ProducerConfig.LINGER_MS_CONFIG, 2);
        assertThat(config).containsEntry(ProducerConfig.BATCH_SIZE_CONFIG, 65_536);
        assertThat(config).containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        assertThat(config).containsEntry(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 4 * 1024 * 1024);
    }

    @Test
    void lifecycleConsumersUseBoundedDurableFetchSettings() {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        TradingOrderKafkaConfiguration configuration = new TradingOrderKafkaConfiguration();
        var consumerFactory = (DefaultKafkaConsumerFactory<String, String>)
                configuration.orderStateConsumerFactory(properties);
        Map<String, Object> consumerConfig = consumerFactory.getConfigurationProperties();
        assertThat(consumerConfig).containsEntry(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 4 * 1024 * 1024);
        assertThat(consumerConfig).containsEntry(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 4 * 1024 * 1024);
        assertThat(configuration.orderInstrumentLifecycleKafkaListenerContainerFactory(consumerFactory))
                .isNotNull();
    }

    @Test
    void resolvesOnlyProductLineTopics() {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);

        assertThat(properties.getKafka().getFeeScheduleEventsTopic())
                .isEqualTo("surprising.linear-perp.fee.schedule.events.v1");
        assertThat(properties.getKafka().getInstrumentLifecycleDrainTopic())
                .isEqualTo("surprising.instrument.lifecycle-drain.v1");
    }
}
