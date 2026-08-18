package com.surprising.trading.trigger.task;

import com.surprising.trading.trigger.service.TriggerOrderService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TriggerOrderMaintenanceTask {

    private final TriggerOrderService triggerOrderService;

    public TriggerOrderMaintenanceTask(TriggerOrderService triggerOrderService) {
        this.triggerOrderService = triggerOrderService;
    }

    @Scheduled(fixedDelayString = "${surprising.trading.trigger.execution.maintenance-delay-ms:1000}")
    public void maintainTriggerOrders() {
        triggerOrderService.maintenance();
    }
}
