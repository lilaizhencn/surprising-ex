package com.surprising.adl.provider;

import com.surprising.adl.provider.config.AdlProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AdlProperties.class)
public class SurprisingAdlApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingAdlApplication.class, args);
    }
}
