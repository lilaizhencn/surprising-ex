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
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class TradingOrderKafkaConfiguration {

    private static final int ORDER_STATE_SNAPSHOT_MAX_BYTES = 4 * 1024 * 1024;

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
        config.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, ORDER_STATE_SNAPSHOT_MAX_BYTES);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> orderKafkaTemplate(
            @Qualifier("orderProducerFactory") ProducerFactory<String, String> orderProducerFactory) {
        return new KafkaTemplate<>(orderProducerFactory);
    }

    /** 订单本地状态消费者共用的 Kafka 客户端配置。 */
    @Bean
    public ConsumerFactory<String, String> orderStateConsumerFactory(TradingOrderProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        config.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, ORDER_STATE_SNAPSHOT_MAX_BYTES);
        config.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, ORDER_STATE_SNAPSHOT_MAX_BYTES);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean(name = "orderStateKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> orderStateKafkaListenerContainerFactory(
            @Qualifier("orderStateConsumerFactory") ConsumerFactory<String, String> consumerFactory) {
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
            @Qualifier("orderStateConsumerFactory") ConsumerFactory<String, String> consumerFactory,
            TradingOrderProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(properties.getKafka().getAccountCommandResultsConcurrency());
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.BATCH);
        return factory;
    }

    /** 用户订单命令按用户分区串行消费；同一用户不会在两个订单节点同时写 WAL。 */
    @Bean(name = "orderUserCommandKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> orderUserCommandKafkaListenerContainerFactory(
            @Qualifier("orderStateConsumerFactory") ConsumerFactory<String, String> consumerFactory,
            TradingOrderProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(properties.getKafka().getUserCommandConcurrency());
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.BATCH);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1_000L, Long.MAX_VALUE)));
        return factory;
    }

    /** 结果 Topic 每个 HTTP 节点使用独立消费组，保证等待请求能在发起节点完成。 */
    @Bean(name = "orderUserCommandResultKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> orderUserCommandResultKafkaListenerContainerFactory(
            @Qualifier("orderStateConsumerFactory") ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        return factory;
    }

    @Bean(name = "orderMatchResultKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> orderMatchResultKafkaListenerContainerFactory(
            @Qualifier("orderStateConsumerFactory") ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.BATCH);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1_000L, Long.MAX_VALUE)));
        return factory;
    }

    /**
     * 到期清理必须失败关闭；只要撤单或冻结资金确认未完成，就持续重试同一 instrument 事件。
     */
    @Bean(name = "orderInstrumentLifecycleKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> orderInstrumentLifecycleKafkaListenerContainerFactory(
            @Qualifier("orderStateConsumerFactory") ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1_000L, Long.MAX_VALUE)));
        return factory;
    }
}
