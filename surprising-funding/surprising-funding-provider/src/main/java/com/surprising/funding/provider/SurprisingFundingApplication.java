package com.surprising.funding.provider;

import com.surprising.funding.provider.config.FundingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(FundingProperties.class)
public class SurprisingFundingApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingFundingApplication.class, args);
    }
}
