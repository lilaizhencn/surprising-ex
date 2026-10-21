package com.surprising.trading.maintenance.task;

import com.surprising.trading.maintenance.service.MaintenanceService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceTaskRunner {
    private final MaintenanceService service;
    public MaintenanceTaskRunner(MaintenanceService service) { this.service = service; }
    @Scheduled(fixedDelayString = "${surprising.trading.maintenance.step-delay-ms:1000}")
    public void run() { service.tick(); }
}
