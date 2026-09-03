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
        "com.surprising.websocket.provider"
})
@EnableConfigurationProperties({GatewayProperties.class, WebSocketProperties.class})
@EnableKafka
@EnableScheduling
public class SurprisingGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingGatewayApplication.class, args);
    }
}
