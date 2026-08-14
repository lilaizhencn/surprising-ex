package com.surprising.liquidation.provider;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.surprising.liquidation.provider")
@EnableScheduling
@EnableConfigurationProperties(LiquidationProperties.class)
public class SurprisingLiquidationApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingLiquidationApplication.class, args);
    }
}
