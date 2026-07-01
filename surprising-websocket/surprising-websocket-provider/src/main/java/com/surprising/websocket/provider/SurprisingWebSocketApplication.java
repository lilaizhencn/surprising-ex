package com.surprising.websocket.provider;

import com.surprising.websocket.provider.config.WebSocketProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.annotation.EnableKafka;

@EnableKafka
@SpringBootApplication
@EnableConfigurationProperties(WebSocketProperties.class)
public class SurprisingWebSocketApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingWebSocketApplication.class, args);
    }
}
