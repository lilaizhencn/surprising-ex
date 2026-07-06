package com.surprising.price.mark.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.util.ReflectionTestUtils;

class MarkKafkaConfigurationTest {

    @Test
    void defaultsToLegacyPerpTopicsUntilProductTopicsAreEnabled() {
        MarkPriceProperties properties = new MarkPriceProperties();

        assertThat(properties.getKafka().getGroupId()).isEqualTo("surprising-mark-price-v1");
        assertThat(properties.indexPriceTopic()).isEqualTo("surprising.perp.index.price.v1");
        assertThat(properties.bookTickerTopic()).isEqualTo("surprising.perp.book.ticker.v1");
        assertThat(properties.tradeTopic()).isEqualTo("surprising.perp.trade.events.v1");
        assertThat(properties.isFundingRateExpected()).isTrue();
        assertThat(properties.fundingRateTopic()).isEqualTo("surprising.perp.funding.rate.v1");
        assertThat(properties.markPriceTopic()).isEqualTo("surprising.perp.mark.price.v1");
        assertThat(properties.markPriceAuditTopic()).isEqualTo("surprising.perp.mark.price.audit.v1");
    }

    @Test
    void canResolveMarkTopicsAndGroupFromProductLine() {
        MarkPriceProperties properties = new MarkPriceProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);

        assertThat(properties.getKafka().getGroupId()).isEqualTo("surprising-inverse-delivery-mark-price-v1");
        assertThat(properties.indexPriceTopic()).isEqualTo("surprising.inverse-delivery.index.price.v1");
        assertThat(properties.bookTickerTopic()).isEqualTo("surprising.inverse-delivery.book.ticker.v1");
        assertThat(properties.tradeTopic()).isEqualTo("surprising.inverse-delivery.trade.events.v1");
        assertThat(properties.isFundingRateExpected()).isFalse();
        assertThat(properties.fundingRateTopic()).isEqualTo("surprising.perp.funding.rate.v1");
        assertThat(properties.markPriceTopic()).isEqualTo("surprising.inverse-delivery.mark.price.v1");
        assertThat(properties.markPriceAuditTopic()).isEqualTo("surprising.inverse-delivery.mark.price.audit.v1");
    }

    @Test
    void canResolveFundingTopicForFundingProductLine() {
        MarkPriceProperties properties = new MarkPriceProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);

        assertThat(properties.isFundingRateExpected()).isTrue();
        assertThat(properties.fundingRateTopic()).isEqualTo("surprising.inverse-perp.funding.rate.v1");
    }

    @Test
    void producerUsesDurableIdempotentSettings() {
        MarkPriceProperties properties = new MarkPriceProperties();
        properties.getKafka().setBootstrapServers("kafka-price:9092");

        var factory = (DefaultKafkaProducerFactory<String, Object>)
                new MarkKafkaConfiguration().markProducerFactory(properties);

        Map<String, Object> config = factory.getConfigurationProperties();
        assertThat(config).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-price:9092");
        assertThat(config).containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
        assertThat(config).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        assertThat(config).containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        assertThat(config).containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        assertThat(config).containsEntry(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
    }

    @Test
    void consumerUsesLiveFeedRecordAckSettings() {
        MarkPriceProperties properties = new MarkPriceProperties();
        properties.getKafka().setBootstrapServers("kafka-price:9092");
        properties.getKafka().setGroupId("mark-test-group");
        properties.getKafka().setConcurrency(3);
        properties.getKafka().setMaxPollRecords(250);

        MarkKafkaConfiguration configuration = new MarkKafkaConfiguration();
        var consumerFactory = (DefaultKafkaConsumerFactory<String, String>)
                configuration.markConsumerFactory(properties);
        var listenerFactory = configuration.kafkaListenerContainerFactory(consumerFactory, properties);

        Map<String, Object> config = consumerFactory.getConfigurationProperties();
        assertThat(config).containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-price:9092");
        assertThat(config).containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "mark-test-group");
        assertThat(config).containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        assertThat(config).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        assertThat(config).containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 250);
        assertThat(config).containsEntry(
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                CooperativeStickyAssignor.class.getName());
        assertThat(ReflectionTestUtils.getField(listenerFactory, "concurrency")).isEqualTo(3);
        assertThat(listenerFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);
    }
}
