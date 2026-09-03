package com.surprising.instrument.provider;

import com.surprising.instrument.provider.config.InstrumentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.surprising")
@EnableFeignClients(basePackages = "com.surprising")
@EnableScheduling
@EnableConfigurationProperties(InstrumentProperties.class)
public class SurprisingInstrumentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingInstrumentApplication.class, args);
    }
}
