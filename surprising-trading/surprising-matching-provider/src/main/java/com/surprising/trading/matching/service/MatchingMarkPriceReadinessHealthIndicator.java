package com.surprising.trading.matching.service;

import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.price.consumer.MarkPriceConsumerProperties;
import java.util.List;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("matchingMarkPriceReadiness")
public class MatchingMarkPriceReadinessHealthIndicator implements HealthIndicator {

    private final LatestMarkPriceCache cache;
    private final MarkPriceConsumerProperties properties;

    public MatchingMarkPriceReadinessHealthIndicator(LatestMarkPriceCache cache,
                                                     MarkPriceConsumerProperties properties) {
        this.cache = cache;
        this.properties = properties;
    }

    @Override
    public Health health() {
        List<String> requiredSymbols = properties.getRequiredSymbols();
        List<String> missingSymbols = requiredSymbols.stream()
                .filter(symbol -> cache.fresh(symbol).isEmpty())
                .toList();
        boolean ready = missingSymbols.isEmpty();
        Health.Builder builder = ready ? Health.up() : Health.outOfService();
        return builder.withDetail("productLine", properties.getProductLine().name())
                .withDetail("requiredSymbols", requiredSymbols.size())
                .withDetail("readySymbols", requiredSymbols.size() - missingSymbols.size())
                .withDetail("missingSymbols", missingSymbols)
                .build();
    }
}
