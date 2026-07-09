package com.surprising.risk.provider;

import com.surprising.risk.provider.config.RiskProperties;
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
@EnableConfigurationProperties(RiskProperties.class)
public class SurprisingRiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingRiskApplication.class, args);
    }
}
