package com.surprising.gateway.config;

import com.surprising.websocket.provider.consumer.KafkaFanoutConsumer;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.aeron.client.SurprisingAeronClient;
import io.aeron.driver.MediaDriver;
import java.nio.file.Path;
import java.util.UUID;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.websocket.provider.config.WebSocketProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 仅注册进指定产品线的业务容器；公共 Gateway 扫描范围不包括此配置。 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = {
        "com.surprising.account.provider", "com.surprising.trading",
        "com.surprising.price",
        "com.surprising.realtime.provider", "com.surprising.candlestick.provider",
        "com.surprising.derivatives.lifecycle",
        "com.surprising.risk.provider", "com.surprising.liquidation.provider",
        "com.surprising.insurance.provider", "com.surprising.adl.provider"
}, excludeFilters = @ComponentScan.Filter(
        type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
        classes = {
                com.surprising.realtime.provider.RealtimeRouter.class,
                com.surprising.realtime.provider.RealtimeApplication.class,
                com.surprising.derivatives.lifecycle.SurprisingDerivativesLifecycleApplication.class,
                com.surprising.derivatives.lifecycle.config.LifecycleSchedulingConfiguration.class,
                com.surprising.price.index.controller.ExchangeRateController.class,
                com.surprising.price.index.service.ExchangeRateService.class,
                com.surprising.price.index.repository.ExchangeRateRepository.class,
                com.surprising.price.index.service.ExchangeRateSnapshotCache.class,
                com.surprising.price.index.client.FiatRateClient.class,
                com.surprising.price.index.client.ExternalSpotPriceClient.class
        }))
@EnableConfigurationProperties({
        AccountProperties.class, TriggerProperties.class,
        com.surprising.candlestick.provider.config.CandlestickProperties.class,
        com.surprising.risk.provider.config.RiskProperties.class})
@Import({com.surprising.realtime.api.ValkeyUserQueries.class,
        com.surprising.websocket.provider.config.WebSocketKafkaConfiguration.class,
        com.surprising.websocket.provider.consumer.KafkaFanoutConsumer.class})
@EnableKafka @EnableScheduling @EnableTransactionManagement
public class ProductBusinessConfiguration {
    /** Product context owns transport; business pools own only their separate sessions and queues. */
    @Bean(name = "productCommandMediaDriver", destroyMethod = "close")
    public MediaDriver productCommandMediaDriver(AccountProperties properties) {
        String directory = Path.of(System.getProperty("java.io.tmpdir"),
                "surprising-gateway-command-" + ProcessHandle.current().pid() + "-"
                        + properties.getProductLine() + "-" + UUID.randomUUID()).toString();
        return SurprisingAeronClient.newMediaDriver(directory);
    }

    // 公共 WebSocket 的配置位于父容器；每条产品线的 Kafka 订阅必须另行绑定。
    @org.springframework.context.annotation.Bean("surprising.websocket-com.surprising.websocket.provider.config.WebSocketProperties")
    public WebSocketProperties webSocketProperties(org.springframework.core.env.Environment environment) {
        return org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("surprising.websocket", org.springframework.boot.context.properties.bind.Bindable.of(WebSocketProperties.class))
                .orElseThrow(() -> new IllegalArgumentException("missing WebSocket configuration"));
    }
    @org.springframework.context.annotation.Bean("taskScheduler")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler productScheduler(
            AccountProperties properties,
            @org.springframework.beans.factory.annotation.Value("${surprising.gateway.product-scheduler-pool-size:2}") int poolSize) {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, poolSize));
        scheduler.setThreadNamePrefix("gateway-" + properties.getProductLine() + "-scheduled-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

}
