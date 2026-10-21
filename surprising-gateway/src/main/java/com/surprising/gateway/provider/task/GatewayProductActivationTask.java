package com.surprising.gateway.provider.task;

import com.surprising.gateway.provider.product.GatewayProductServices;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 共享容器发起激活；产品运行时仍由 GatewayProductServices 独占管理。 */
@Component
public class GatewayProductActivationTask {
    private final GatewayProductServices products;
    public GatewayProductActivationTask(GatewayProductServices products) { this.products = products; }

    @Scheduled(fixedDelay = 5000, scheduler = "gatewayProductActivationScheduler")
    public void activate() { products.reload(); }
}
