package com.surprising.gateway.provider.product;

import com.surprising.account.provider.service.AccountAeronGateway;
import com.surprising.aeron.protocol.*;
import com.surprising.trading.order.service.MarkPriceReadinessHealthIndicator;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GatewayProductsHealth {
    @Bean("markPriceReadiness")
    HealthIndicator prices(GatewayProductServices products) {
        return () -> {
            var details = new LinkedHashMap<String, Object>();
            boolean ready = true;
            for (var product : products.enabled()) {
                var health = products.service(product, MarkPriceReadinessHealthIndicator.class).health();
                details.put(product.name(), health);
                ready &= "UP".equals(health.getStatus().getCode());
            }
            return (ready ? Health.up() : Health.outOfService()).withDetails(details).build();
        };
    }
    @Bean("gatewayProducts")
    HealthIndicator cores(GatewayProductServices products) {
        return () -> {
            var details = new LinkedHashMap<String, Object>();
            boolean ready = true;
            for (var product : products.enabled()) {
                try {
                    var response = products.service(product, AccountAeronGateway.class)
                            .query(CoreMessageType.LANE_METRICS_QUERY, UUID.randomUUID(), new byte[0]);
                    boolean up = response.status() == ResponseStatus.OK;
                    details.put(product.name(), up ? "UP" : response.resultCode().name());
                    ready &= up;
                } catch (RuntimeException ex) {
                    details.put(product.name(), "UNAVAILABLE");
                    ready = false;
                }
            }
            return (ready ? Health.up() : Health.outOfService()).withDetails(details).build();
        };
    }
}
