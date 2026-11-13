package com.surprising.gateway.provider.config;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** 将 spring.kafka.bootstrap-servers 统一作为各业务模块 Kafka 地址的默认回退。 */
public class GatewayKafkaEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "gatewayKafkaDefaults";
    private static final String DEFAULT_VALUE =
            "${spring.kafka.bootstrap-servers:${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}}";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, Map.of(
                "surprising.account.kafka.bootstrap-servers", DEFAULT_VALUE,
                "surprising.instrument.kafka.bootstrap-servers", DEFAULT_VALUE,
                "surprising.trading.order.kafka.bootstrap-servers", DEFAULT_VALUE,
                "surprising.price.index.kafka.bootstrap-servers", DEFAULT_VALUE,
                "surprising.price.mark.kafka.bootstrap-servers", DEFAULT_VALUE,
                "surprising.candlestick.kafka.bootstrap-servers", DEFAULT_VALUE,
                "surprising.funding.kafka.bootstrap-servers", DEFAULT_VALUE,
                "surprising.insurance.kafka.bootstrap-servers", DEFAULT_VALUE)));
    }
}
