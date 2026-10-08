package com.surprising.gateway.provider;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.websocket.provider.config.WebSocketProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.surprising.gateway.provider",
        "com.surprising.websocket.provider",
        "com.surprising.instrument.provider",
        "com.surprising.asset"
})
@org.springframework.context.annotation.ComponentScan(basePackages = {
        "com.surprising.gateway.provider", "com.surprising.websocket.provider",
        "com.surprising.instrument.provider", "com.surprising.asset"
}, excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
        type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
        classes = {com.surprising.websocket.provider.service.KafkaFanoutConsumer.class,
                com.surprising.websocket.provider.config.WebSocketKafkaConfiguration.class}))
@EnableConfigurationProperties({GatewayProperties.class, WebSocketProperties.class,
        com.surprising.gateway.provider.product.GatewayProductsProperties.class,
        com.surprising.instrument.provider.config.InstrumentProperties.class})
@EnableKafka
@EnableScheduling
public class SurprisingGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingGatewayApplication.class, args);
    }
}
