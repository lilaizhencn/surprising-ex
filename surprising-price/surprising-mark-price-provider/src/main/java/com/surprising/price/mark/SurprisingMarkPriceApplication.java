package com.surprising.price.mark;

import com.surprising.price.mark.config.MarkPriceProperties;
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
@EnableConfigurationProperties(MarkPriceProperties.class)
public class SurprisingMarkPriceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingMarkPriceApplication.class, args);
    }
}
