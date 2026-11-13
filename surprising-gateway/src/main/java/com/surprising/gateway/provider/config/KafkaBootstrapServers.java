package com.surprising.gateway.provider.config;

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
}
