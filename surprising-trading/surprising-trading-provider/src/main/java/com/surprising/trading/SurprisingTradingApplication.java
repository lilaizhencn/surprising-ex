package com.surprising.trading;

import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.trigger.config.TriggerProperties;
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
@EnableConfigurationProperties({TradingOrderProperties.class, TriggerProperties.class})
public class SurprisingTradingApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingTradingApplication.class, args);
    }
}
