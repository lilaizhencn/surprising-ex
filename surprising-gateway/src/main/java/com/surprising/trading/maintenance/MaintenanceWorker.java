package com.surprising.trading.maintenance;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceWorker {
    private final MaintenanceService service;
    public MaintenanceWorker(MaintenanceService service) { this.service = service; }
    @Scheduled(fixedDelayString = "${surprising.trading.maintenance.step-delay-ms:1000}")
    public void run() { service.tick(); }
}
