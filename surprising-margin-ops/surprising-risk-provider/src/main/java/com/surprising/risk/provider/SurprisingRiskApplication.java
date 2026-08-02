package com.surprising.risk.provider;

import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.service.RiskLocalProjectionStore;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

@SpringBootApplication(scanBasePackages = "com.surprising")
@EnableFeignClients(basePackages = "com.surprising")
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties(RiskProperties.class)
public class SurprisingRiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingRiskApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    RiskLocalProjectionStore riskLocalProjectionStore(RiskProperties properties, ObjectMapper objectMapper) {
        return new RiskLocalProjectionStore(Path.of(properties.getLocalState().getWalDirectory()), objectMapper);
    }
}
