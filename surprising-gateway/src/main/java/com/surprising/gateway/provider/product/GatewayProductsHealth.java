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
    @Bean("taskScheduler")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler gatewayScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("gateway-shared-scheduled-");
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
    @Bean("gatewayProductActivationScheduler")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler activationScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("gateway-product-activation-");
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
    @Bean
    com.surprising.gateway.provider.local.LocalBusinessApi sharedBusinessApi(
            com.surprising.instrument.provider.service.InstrumentRequestService instruments,
            tools.jackson.databind.ObjectMapper mapper, jakarta.validation.Validator validator,
            com.surprising.websocket.provider.service.SubscriptionRegistry websocket) {
        return com.surprising.gateway.provider.local.LocalBusinessApi.shared(instruments, mapper, validator, websocket);
    }

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
    HealthIndicator cores(GatewayProductServices products, GatewayProductSettings settings) {
        return () -> {
            var details = new LinkedHashMap<String, Object>();
            boolean ready = true;
            for (var desired : settings.list()) {
                var product = desired.productLine();
                if (!desired.enabled()) continue;
                if (!products.enabled().contains(product)) {
                    details.put(product.name(), products.errors().getOrDefault(product, "INITIALIZING"));
                    ready = false;
                    continue;
                }
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
