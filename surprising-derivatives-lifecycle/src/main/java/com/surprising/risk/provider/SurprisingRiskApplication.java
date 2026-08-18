package com.surprising.risk.provider;

import com.surprising.risk.provider.config.RiskProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = "com.surprising")
@EnableConfigurationProperties(RiskProperties.class)
public class SurprisingRiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingRiskApplication.class, args);
    }
}
