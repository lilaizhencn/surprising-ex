package com.surprising.liquidation.provider.task;

import com.surprising.liquidation.provider.service.LiquidationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LiquidationMaintenanceTask {

    private final LiquidationService liquidationService;

    public LiquidationMaintenanceTask(LiquidationService liquidationService) {
        this.liquidationService = liquidationService;
    }

    @Scheduled(fixedDelayString = "${surprising.liquidation.coordinator.delay-ms:25}")
    public void processWork() {
        liquidationService.processWork();
    }
}
