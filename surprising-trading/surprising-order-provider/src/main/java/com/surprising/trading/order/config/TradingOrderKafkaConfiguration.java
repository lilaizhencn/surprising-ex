package com.surprising.trading.order.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

@Configuration
public class TradingOrderKafkaConfiguration {

    @Bean
    public ProducerFactory<String, String> orderProducerFactory(TradingOrderProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        config.put(ProducerConfig.LINGER_MS_CONFIG, 2);
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, 65_536);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> orderKafkaTemplate(
            @Qualifier("orderProducerFactory") ProducerFactory<String, String> orderProducerFactory) {
        return new KafkaTemplate<>(orderProducerFactory);
    }

    /** 供可重放的活跃订单读模型使用；每个生命周期事件仍会从 PostgreSQL 重新读取权威订单。 */
    @Bean
    public ConsumerFactory<String, String> orderOpenViewConsumerFactory(TradingOrderProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean(name = "orderOpenViewKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> orderOpenViewKafkaListenerContainerFactory(
            @Qualifier("orderOpenViewConsumerFactory") ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.BATCH);
        return factory;
    }

    /**
     * 资金预占结果以 productLine:userId 为键。每个 Topic 分区使用一个消费者，使不同用户可以并发推进，
     * 同时由 Kafka 保证同一用户的全部指令结果串行。
     */
    @Bean(name = "orderAccountCommandResultKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> orderAccountCommandResultKafkaListenerContainerFactory(
            @Qualifier("orderOpenViewConsumerFactory") ConsumerFactory<String, String> consumerFactory,
            TradingOrderProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(properties.getKafka().getAccountCommandResultsConcurrency());
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.BATCH);
        return factory;
    }
}
