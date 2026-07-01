package com.surprising.gateway.provider;

import com.surprising.gateway.provider.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class SurprisingGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingGatewayApplication.class, args);
    }
}
