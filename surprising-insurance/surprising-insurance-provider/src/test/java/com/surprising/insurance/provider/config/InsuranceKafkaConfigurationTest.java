package com.surprising.insurance.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

class InsuranceKafkaConfigurationTest {

    @Test
    void consumerUsesManualRecordAckAndCooperativeRebalance() {
        InsuranceProperties properties = new InsuranceProperties();
        properties.getKafka().setBootstrapServers("localhost:19092");
        properties.getKafka().setGroupId("insurance-test");
        properties.getKafka().setMaxPollRecords(444);
        InsuranceKafkaConfiguration configuration = new InsuranceKafkaConfiguration();

        var consumerFactory = (DefaultKafkaConsumerFactory<String, String>)
                configuration.insuranceConsumerFactory(properties);
        var listenerFactory = configuration.insuranceKafkaListenerContainerFactory(consumerFactory, properties);

        Map<String, Object> configs = consumerFactory.getConfigurationProperties();
        assertThat(configs)
                .containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:19092")
                .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "insurance-test")
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 444)
                .containsEntry(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                        CooperativeStickyAssignor.class.getName());
        assertThat(listenerFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);
    }
}
