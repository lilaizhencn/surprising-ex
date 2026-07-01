package com.surprising.liquidation.provider;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.surprising")
@EnableFeignClients(basePackages = "com.surprising")
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties(LiquidationProperties.class)
public class SurprisingLiquidationApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingLiquidationApplication.class, args);
    }
}
