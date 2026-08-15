package com.surprising.adl.provider.task;

import com.surprising.adl.provider.service.AdlService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADL 模块定时任务入口，只负责调用 ADL 服务层。
 */
@Component
public class AdlMaintenanceTask {

    private final AdlService adlService;
    public AdlMaintenanceTask(AdlService adlService) {
        this.adlService = adlService;
    }

    @Scheduled(fixedDelayString = "${surprising.adl.scanner.scan-delay-ms:1000}")
    public void processResidualDeficits() {
        adlService.processResidualDeficits();
    }
}
