package com.surprising.price.index;

import com.surprising.price.index.config.IndexPriceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.surprising")
@EnableFeignClients(basePackages = "com.surprising")
@EnableScheduling
@EnableConfigurationProperties(IndexPriceProperties.class)
public class SurprisingIndexPriceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingIndexPriceApplication.class, args);
    }
}
