package com.surprising.trading.trigger;

import com.surprising.trading.trigger.config.TriggerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.surprising")
@EnableFeignClients(basePackages = "com.surprising")
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties(TriggerProperties.class)
@ImportRuntimeHints(TriggerRuntimeHints.class)
public class SurprisingTriggerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingTriggerApplication.class, args);
    }
}
