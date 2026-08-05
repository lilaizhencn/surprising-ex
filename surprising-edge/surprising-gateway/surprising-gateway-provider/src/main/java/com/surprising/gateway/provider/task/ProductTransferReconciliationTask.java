package com.surprising.gateway.provider.task;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.ProductTransferCoordinator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProductTransferReconciliationTask {

    private final ProductTransferCoordinator coordinator;
    private final GatewayProperties properties;

    public ProductTransferReconciliationTask(ProductTransferCoordinator coordinator,
                                             GatewayProperties properties) {
        this.coordinator = coordinator;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${surprising.gateway.product-transfer.reconciliation-delay:5s}")
    public void reconcileProductTransfers() {
        coordinator.reconcile(properties.getProductTransfer().getReconciliationBatchSize());
    }
}
