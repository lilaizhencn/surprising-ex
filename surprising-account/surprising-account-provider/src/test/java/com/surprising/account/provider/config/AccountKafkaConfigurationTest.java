package com.surprising.account.provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.util.ReflectionTestUtils;

class AccountKafkaConfigurationTest {

    @Test
    void consumerUsesReplaySafeBatchAckSettings() {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setBootstrapServers("kafka-d:9092");
        properties.getKafka().setGroupId("account-test-group");
        properties.getKafka().setClientId("account-node-a");
        properties.getKafka().setConcurrency(3);
        properties.getKafka().setMaxPollRecords(750);

        AccountKafkaConfiguration configuration = new AccountKafkaConfiguration();
        var consumerFactory = (DefaultKafkaConsumerFactory<String, String>)
                configuration.accountConsumerFactory(properties);
        var listenerFactory = configuration.accountKafkaListenerContainerFactory(consumerFactory, properties);

        Map<String, Object> config = consumerFactory.getConfigurationProperties();
        assertThat(config).containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-d:9092");
        assertThat(config).containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "account-test-group");
        assertThat(config).containsEntry(ConsumerConfig.CLIENT_ID_CONFIG, "account-node-a");
        assertThat(config).containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        assertThat(config).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        assertThat(config).containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 750);
        assertThat(config).containsEntry(
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                CooperativeStickyAssignor.class.getName());
        assertThat(ReflectionTestUtils.getField(listenerFactory, "concurrency")).isEqualTo(3);
        assertThat(ReflectionTestUtils.getField(listenerFactory, "batchListener")).isEqualTo(true);
        assertThat(listenerFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.BATCH);
    }

    @Test
    void userCommandFactoryIsDedicatedAndAlignedWithThirtyTwoPartitions() {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setUserCommandConcurrency(32);
        AccountKafkaConfiguration configuration = new AccountKafkaConfiguration();
        var consumerFactory = configuration.accountConsumerFactory(properties);

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        var listenerFactory = configuration.accountUserCommandKafkaListenerContainerFactory(
                consumerFactory, properties, kafkaTemplate);

        assertThat(ReflectionTestUtils.getField(listenerFactory, "concurrency")).isEqualTo(32);
        assertThat(ReflectionTestUtils.getField(listenerFactory, "batchListener")).isEqualTo(true);
        assertThat(listenerFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.BATCH);
    }

    @Test
    void producerUsesIdempotentOutboxSettings() {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setBootstrapServers("kafka-d:9092");

        AccountKafkaConfiguration configuration = new AccountKafkaConfiguration();
        var producerFactory = (DefaultKafkaProducerFactory<String, String>)
                configuration.accountProducerFactory(properties);

        Map<String, Object> config = producerFactory.getConfigurationProperties();
        assertThat(config).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-d:9092");
        assertThat(config).containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
        assertThat(config).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        assertThat(config).containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        assertThat(config).containsEntry(ProducerConfig.LINGER_MS_CONFIG, 2);
        assertThat(config).containsEntry(ProducerConfig.BATCH_SIZE_CONFIG, 65_536);
        assertThat(config).containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    }

    @Test
    void keepsLegacyBusinessTopicsButAlwaysIsolatesAccountCommandTopics() {
        AccountProperties properties = new AccountProperties();

        assertThat(properties.getKafka().getGroupId()).isEqualTo("surprising-account-v1");
        assertThat(properties.getKafka().getOrderCommandsTopic())
                .isEqualTo("surprising.perp.order.commands.v1");
        assertThat(properties.getKafka().getOrderEventsTopic())
                .isEqualTo("surprising.perp.order.events.v1");
        assertThat(properties.getKafka().getPositionEventsTopic())
                .isEqualTo("surprising.account.position.events.v1");
        assertThat(properties.getKafka().getTriggerOrderEventsTopic())
                .isEqualTo("surprising.perp.trigger-order.events.v1");
        assertThat(properties.getKafka().getLiquidationFeeEventsTopic())
                .isEqualTo("surprising.account.liquidation-fee.events.v1");
        assertThat(properties.getKafka().getDeliverySettlementsTopic())
                .isEqualTo("surprising.linear-delivery.delivery.settlements.v1");
        assertThat(properties.getKafka().getOptionExercisesTopic())
                .isEqualTo("surprising.option.option.exercises.v1");
        assertThat(properties.getKafka().getUserCommandsTopic())
                .isEqualTo("surprising.linear-perp.account.user.commands.v1");
        assertThat(properties.getKafka().getUserCommandsDltTopic())
                .isEqualTo("surprising.linear-perp.account.user.commands.dlt.v1");
        assertThat(properties.getKafka().getCommandResultsTopic())
                .isEqualTo("surprising.linear-perp.account.command.results.v1");
        assertThat(properties.getKafka().getUserCommandGroupId())
                .isEqualTo("surprising-linear-perp-account-user-command-v1");
    }

    @Test
    void canResolveSettlementTopicsAndGroupFromProductLine() {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);

        assertThat(properties.getKafka().getGroupId())
                .isEqualTo("surprising-linear-perp-account-v1");
        assertThat(properties.getKafka().getOrderCommandsTopic())
                .isEqualTo("surprising.linear-perp.order.commands.v1");
        assertThat(properties.getKafka().getOrderEventsTopic())
                .isEqualTo("surprising.linear-perp.order.events.v1");
        assertThat(properties.getKafka().getPositionEventsTopic())
                .isEqualTo("surprising.linear-perp.account.position.events.v1");
        assertThat(properties.getKafka().getTriggerOrderEventsTopic())
                .isEqualTo("surprising.linear-perp.trigger-order.events.v1");
        assertThat(properties.getKafka().getLiquidationFeeEventsTopic())
                .isEqualTo("surprising.linear-perp.account.liquidation-fee.events.v1");
        assertThat(properties.getKafka().isDeliverySettlementsTopicEnabled()).isFalse();
        assertThat(properties.getKafka().isOptionExercisesTopicEnabled()).isFalse();
        assertThat(properties.getKafka().getDeliverySettlementsTopic())
                .isEqualTo("surprising.linear-delivery.delivery.settlements.v1");
        assertThat(properties.getKafka().getOptionExercisesTopic())
                .isEqualTo("surprising.option.option.exercises.v1");

        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        assertThat(properties.getKafka().isDeliverySettlementsTopicEnabled()).isTrue();
        assertThat(properties.getKafka().isOptionExercisesTopicEnabled()).isFalse();
        assertThat(properties.getKafka().getDeliverySettlementsTopic())
                .isEqualTo("surprising.linear-delivery.delivery.settlements.v1");
        assertThat(properties.getKafka().getOptionExercisesTopic())
                .isEqualTo("surprising.option.option.exercises.v1");

        properties.getKafka().setProductLine(ProductLine.OPTION);
        assertThat(properties.getKafka().isDeliverySettlementsTopicEnabled()).isFalse();
        assertThat(properties.getKafka().isOptionExercisesTopicEnabled()).isTrue();
        assertThat(properties.getKafka().getDeliverySettlementsTopic())
                .isEqualTo("surprising.linear-delivery.delivery.settlements.v1");
        assertThat(properties.getKafka().getOptionExercisesTopic())
                .isEqualTo("surprising.option.option.exercises.v1");
    }
}
