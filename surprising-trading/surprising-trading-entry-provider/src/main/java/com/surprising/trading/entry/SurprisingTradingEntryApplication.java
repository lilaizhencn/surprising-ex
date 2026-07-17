package com.surprising.trading.entry;

import com.surprising.trading.order.SurprisingOrderApplication;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.trigger.SurprisingTriggerApplication;
import com.surprising.trading.trigger.config.TriggerProperties;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = {
                "com.surprising.trading.order",
                "com.surprising.trading.trigger",
                "com.surprising.price.consumer"
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        SurprisingOrderApplication.class,
                        SurprisingTriggerApplication.class
                }
        )
)
@EnableFeignClients(basePackages = "com.surprising")
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties({
        TradingOrderProperties.class,
        TriggerProperties.class
})
public class SurprisingTradingEntryApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(SurprisingTradingEntryApplication.class);
        application.setDefaultProperties(Map.of("spring.config.name", "surprising-trading-entry-provider"));
        application.run(args);
    }
}
