package com.surprising.trading.entry;

import com.surprising.price.consumer.MarkPriceConsumerProperties;
import com.surprising.trading.order.SurprisingOrderApplication;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.service.FeeScheduleSnapshotConsumer;
import com.surprising.trading.order.service.InstrumentOrderDrainConsumer;
import com.surprising.trading.order.service.InstrumentSnapshotConsumer;
import com.surprising.trading.order.service.LeverageSettingSnapshotConsumer;
import com.surprising.trading.order.service.OpenInterestSnapshotConsumer;
import com.surprising.trading.order.service.OrderAccountCommandResultConsumer;
import com.surprising.trading.order.service.OrderAccountStateSnapshotConsumer;
import com.surprising.trading.order.service.OrderMatchResultConsumer;
import com.surprising.trading.order.service.OrderPositionMaintenanceConsumer;
import com.surprising.trading.order.service.OrderStateSnapshotConsumer;
import com.surprising.trading.order.service.OrderUserCommandConsumer;
import com.surprising.trading.order.service.OrderUserCommandResultWaiter;
import com.surprising.trading.trigger.SurprisingTriggerApplication;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.service.InstrumentTriggerDrainConsumer;
import com.surprising.trading.trigger.service.PositionClosedTriggerConsumer;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ImportRuntimeHints;
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
@ImportRuntimeHints(TradingEntryRuntimeHints.class)
public class SurprisingTradingEntryApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(SurprisingTradingEntryApplication.class);
        application.setDefaultProperties(Map.of("spring.config.name", "surprising-trading-entry-provider"));
        application.run(args);
    }
}
