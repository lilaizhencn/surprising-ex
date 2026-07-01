package com.surprising.insurance.provider;

import com.surprising.insurance.provider.config.InsuranceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(InsuranceProperties.class)
public class SurprisingInsuranceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingInsuranceApplication.class, args);
    }
}
