package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("positionCache")
public class PositionCacheHealthIndicator implements HealthIndicator {

    private final RedisPositionCache cache;
    private final AccountProperties properties;

    public PositionCacheHealthIndicator(RedisPositionCache cache, AccountProperties properties) {
        this.cache = cache;
        this.properties = properties;
    }

    @Override
    public Health health() {
        boolean ready = cache.ready(properties.getKafka().getProductLine());
        Health.Builder builder = ready ? Health.up() : Health.outOfService();
        return builder.withDetail("productLine", properties.getKafka().getProductLine())
                .withDetail("ready", ready)
                .build();
    }
}
