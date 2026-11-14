package com.surprising.gateway.config;

import com.surprising.websocket.provider.consumer.KafkaFanoutConsumer;

import com.surprising.account.provider.config.AccountProperties;
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
        "com.surprising.realtime.provider", "com.surprising.candlestick.provider"
}, excludeFilters = @ComponentScan.Filter(
        type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
        classes = {
                com.surprising.realtime.provider.RealtimeRouter.class,
                com.surprising.realtime.provider.RealtimeApplication.class,
                com.surprising.price.index.controller.ExchangeRateController.class,
                com.surprising.price.index.service.ExchangeRateService.class,
                com.surprising.price.index.repository.ExchangeRateRepository.class,
                com.surprising.price.index.service.ExchangeRateSnapshotCache.class,
                com.surprising.price.index.client.FiatRateClient.class,
                com.surprising.price.index.client.ExternalSpotPriceClient.class
        }))
@EnableConfigurationProperties({
        AccountProperties.class, TriggerProperties.class,
        com.surprising.candlestick.provider.config.CandlestickProperties.class})
@Import({com.surprising.realtime.api.ValkeyUserQueries.class,
        com.surprising.websocket.provider.config.WebSocketKafkaConfiguration.class,
        com.surprising.websocket.provider.consumer.KafkaFanoutConsumer.class})
@EnableKafka @EnableScheduling @EnableTransactionManagement
public class ProductBusinessConfiguration {
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
