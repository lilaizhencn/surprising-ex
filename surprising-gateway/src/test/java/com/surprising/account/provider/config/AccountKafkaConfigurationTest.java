package com.surprising.account.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.util.ReflectionTestUtils;

class AccountKafkaConfigurationTest {

    @Test
    void settlementConsumerUsesReplaySafeRecordAckSettings() {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        properties.getKafka().setBootstrapServers("kafka-d:9092");
        properties.getKafka().setClientId("account-node-a");
        properties.getKafka().setMaxPollRecords(750);

        AccountKafkaConfiguration configuration = new AccountKafkaConfiguration();
        var consumerFactory = (DefaultKafkaConsumerFactory<String, String>)
                configuration.accountConsumerFactory(properties);
        var listenerFactory = configuration.accountInstrumentLifecycleKafkaListenerContainerFactory(consumerFactory);

        Map<String, Object> config = consumerFactory.getConfigurationProperties();
        assertThat(config).containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-d:9092");
        assertThat(config).containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "surprising-linear-perp-account-v1");
        assertThat(config).containsEntry(ConsumerConfig.CLIENT_ID_CONFIG, "account-node-a");
        assertThat(config).containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(config).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        assertThat(config).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        assertThat(config).containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 750);
        assertThat(config).containsEntry(
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                CooperativeStickyAssignor.class.getName());
        assertThat(ReflectionTestUtils.getField(listenerFactory, "batchListener")).isEqualTo(false);
        assertThat(listenerFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);
    }

    @Test
    void canResolveSettlementTopicsAndGroupFromProductLine() {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);

        assertThat(properties.getKafka().getGroupId())
                .isEqualTo("surprising-linear-perp-account-v1");
        assertThat(properties.getKafka().isDeliverySettlementsTopicEnabled()).isFalse();
        assertThat(properties.getKafka().isOptionExercisesTopicEnabled()).isFalse();
        assertThat(properties.getKafka().getDeliverySettlementsTopic())
                .isEqualTo("surprising.linear-perp.delivery.settlements.v1");
        assertThat(properties.getKafka().getOptionExercisesTopic())
                .isEqualTo("surprising.linear-perp.option.exercises.v1");

        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        assertThat(properties.getKafka().isDeliverySettlementsTopicEnabled()).isTrue();
        assertThat(properties.getKafka().isOptionExercisesTopicEnabled()).isFalse();
        assertThat(properties.getKafka().getDeliverySettlementsTopic())
                .isEqualTo("surprising.linear-delivery.delivery.settlements.v1");
        assertThat(properties.getKafka().getOptionExercisesTopic())
                .isEqualTo("surprising.linear-delivery.option.exercises.v1");

        properties.getKafka().setProductLine(ProductLine.OPTION);
        assertThat(properties.getKafka().isDeliverySettlementsTopicEnabled()).isFalse();
        assertThat(properties.getKafka().isOptionExercisesTopicEnabled()).isTrue();
        assertThat(properties.getKafka().getDeliverySettlementsTopic())
                .isEqualTo("surprising.option.delivery.settlements.v1");
        assertThat(properties.getKafka().getOptionExercisesTopic())
                .isEqualTo("surprising.option.option.exercises.v1");
    }
}
