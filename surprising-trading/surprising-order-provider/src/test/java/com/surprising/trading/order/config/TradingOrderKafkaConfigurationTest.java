package com.surprising.trading.order.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

class TradingOrderKafkaConfigurationTest {

    @Test
    void producerUsesDurableIdempotentSettings() {
        TradingOrderProperties properties = new TradingOrderProperties();
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
        assertThat(config).containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
    }

    @Test
    void openOrderProjectionConsumesAndAcknowledgesKafkaInBatches() {
        TradingOrderProperties properties = new TradingOrderProperties();
        TradingOrderKafkaConfiguration configuration = new TradingOrderKafkaConfiguration();
        var consumerFactory = (DefaultKafkaConsumerFactory<String, String>)
                configuration.orderOpenViewConsumerFactory(properties);
        var listenerFactory = configuration.orderOpenViewKafkaListenerContainerFactory(consumerFactory);

        assertThat(listenerFactory.isBatchListener()).isTrue();
        assertThat(listenerFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.BATCH);
    }

    @Test
    void defaultsToLegacyPerpTopicsUntilProductTopicsAreEnabled() {
        TradingOrderProperties properties = new TradingOrderProperties();

        assertThat(properties.getKafka().getOrderCommandsTopic())
                .isEqualTo("surprising.perp.order.commands.v1");
        assertThat(properties.getKafka().getOrderEventsTopic())
                .isEqualTo("surprising.perp.order.events.v1");
        assertThat(properties.getKafka().getPositionEventsTopic())
                .isEqualTo("surprising.account.position.events.v1");
        assertThat(properties.getKafka().getPositionMaintenanceGroupId())
                .isEqualTo("surprising-order-position-maintenance-v1");
    }

    @Test
    void canResolveOrderTopicsFromProductLine() {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.SPOT);
        properties.getKafka().setProductTopicsEnabled(true);

        assertThat(properties.getKafka().getOrderCommandsTopic())
                .isEqualTo("surprising.spot.order.commands.v1");
        assertThat(properties.getKafka().getOrderEventsTopic())
                .isEqualTo("surprising.spot.order.events.v1");
        assertThat(properties.getKafka().getPositionEventsTopic())
                .isEqualTo("surprising.spot.account.position.events.v1");
        assertThat(properties.getKafka().getPositionMaintenanceGroupId())
                .contains("spot")
                .contains("order-position-maintenance");
    }
}
