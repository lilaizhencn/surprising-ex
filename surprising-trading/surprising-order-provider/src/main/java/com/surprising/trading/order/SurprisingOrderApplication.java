package com.surprising.trading.order;

import com.surprising.trading.order.config.TradingOrderProperties;
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
@EnableConfigurationProperties(TradingOrderProperties.class)
public class SurprisingOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingOrderApplication.class, args);
    }
}
