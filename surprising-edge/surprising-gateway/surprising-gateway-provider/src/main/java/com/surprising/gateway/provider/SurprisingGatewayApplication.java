package com.surprising.gateway.provider;

import com.surprising.gateway.provider.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
@EnableScheduling
public class SurprisingGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingGatewayApplication.class, args);
    }
}
