package com.surprising.risk.provider.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class RiskKafkaConfiguration {

    @Bean
    public ProducerFactory<String, String> riskProducerFactory(RiskProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> riskKafkaTemplate(
            @Qualifier("riskProducerFactory") ProducerFactory<String, String> riskProducerFactory) {
        return new KafkaTemplate<>(riskProducerFactory);
    }

    @Bean
    public ConsumerFactory<String, String> riskConsumerFactory(RiskProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getKafka().getGroupId());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.getKafka().getMaxPollRecords());
        config.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, CooperativeStickyAssignor.class.getName());
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> riskKafkaListenerContainerFactory(
            @Qualifier("riskConsumerFactory") ConsumerFactory<String, String> riskConsumerFactory,
            RiskProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(riskConsumerFactory);
        factory.setConcurrency(properties.getKafka().getConcurrency());
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        // 持仓事件可能暂时早于标记价格缓存更新。资金风险触发不能通过丢弃来恢复；
        // 应暂停并重试当前批次，直到权威输入可用。
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1_000L, Long.MAX_VALUE)));
        return factory;
    }

    /** 账户钱包快照使用独立消费组，避免持仓分区重试阻塞余额快照。 */
    @Bean(name = "riskAccountWalletKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> riskAccountWalletKafkaListenerContainerFactory(
            @Qualifier("riskConsumerFactory") ConsumerFactory<String, String> riskConsumerFactory,
            RiskProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(riskConsumerFactory);
        factory.setConcurrency(properties.getKafka().getConcurrency());
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1_000L, Long.MAX_VALUE)));
        return factory;
    }

    @Bean(name = "riskInstrumentSnapshotKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> riskInstrumentSnapshotKafkaListenerContainerFactory(
            @Qualifier("riskConsumerFactory") ConsumerFactory<String, String> riskConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(riskConsumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1_000L, Long.MAX_VALUE)));
        return factory;
    }
}
