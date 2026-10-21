package com.surprising.gateway.c2c.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public class C2cRuntime {
    @Bean("c2cSettlementScheduler")
    ThreadPoolTaskScheduler c2cSettlementScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("gateway-c2c-settlement-");
        scheduler.setDaemon(true);
        scheduler.setAwaitTerminationSeconds(3);
        return scheduler;
    }
    @Bean("c2cChatScheduler")
    ThreadPoolTaskScheduler c2cChatScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("gateway-c2c-chat-");scheduler.setDaemon(true);scheduler.setAwaitTerminationSeconds(3);
        return scheduler;
    }
}
