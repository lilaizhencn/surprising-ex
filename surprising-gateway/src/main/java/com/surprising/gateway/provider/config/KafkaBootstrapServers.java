package com.surprising.gateway.provider.config;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/** 解析模块 Kafka 地址：优先使用模块显式覆盖，否则回退到全局 spring.kafka.bootstrap-servers。 */
public final class KafkaBootstrapServers {

    private static final String LOCAL_DEFAULT = "localhost:9092";

    private KafkaBootstrapServers() {
    }

    public static String resolve(String override, String fallback) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        return fallback != null && !fallback.isBlank() ? fallback : LOCAL_DEFAULT;
    }

    public static Map<String, Object> jsonProducerConfig(String bootstrapServers) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, resolve(bootstrapServers, LOCAL_DEFAULT));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return config;
    }

    /** 仅暴露 ProducerFactory 接口，避免子容器关闭时销毁父容器共享的 DefaultKafkaProducerFactory。 */
    @SuppressWarnings("unchecked")
    public static <K, V> ProducerFactory<K, V> nonDisposableDelegate(ProducerFactory<K, V> delegate) {
        return (ProducerFactory<K, V>) Proxy.newProxyInstance(
                ProducerFactory.class.getClassLoader(),
                new Class<?>[]{ProducerFactory.class},
                (proxy, method, args) -> method.invoke(delegate, args));
    }
}
