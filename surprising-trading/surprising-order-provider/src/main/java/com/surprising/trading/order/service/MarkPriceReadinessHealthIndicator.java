package com.surprising.trading.order.service;

import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.price.consumer.MarkPriceConsumerProperties;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("markPriceReadiness")
public class MarkPriceReadinessHealthIndicator implements HealthIndicator {

    private final LatestMarkPriceCache cache;
    private final MarkPriceConsumerProperties properties;

    public MarkPriceReadinessHealthIndicator(LatestMarkPriceCache cache,
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
        ProductLine productLine = properties.getProductLine();
        boolean ready = missingSymbols.isEmpty();
        Health.Builder builder = ready ? Health.up() : Health.outOfService();
        return builder.withDetail("productLine", productLine.name())
                .withDetail("requiredSymbols", requiredSymbols.size())
                .withDetail("readySymbols", requiredSymbols.size() - missingSymbols.size())
                .withDetail("missingSymbols", missingSymbols)
                .build();
    }
}
