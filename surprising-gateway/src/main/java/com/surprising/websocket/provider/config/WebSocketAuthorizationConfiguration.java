package com.surprising.websocket.provider.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
public class WebSocketAuthorizationConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    public ThreadPoolExecutor websocketAuthorizationChecks(WebSocketProperties properties) {
        int workers = properties.getSecurity().getAuthorizationWorkers();
        int capacity = properties.getSecurity().getAuthorizationQueueCapacity();
        if (workers < 1 || workers > 64 || capacity < 1 || capacity > 100_000)
            throw new IllegalArgumentException("invalid websocket authorization capacity");
        var checks = new ThreadPoolExecutor(workers, workers, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), Thread.ofVirtual().name("ws-authorization-", 0).factory());
        checks.allowCoreThreadTimeOut(true);
        return checks;
    }
}
