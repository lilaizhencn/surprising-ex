package com.surprising.price.mark.task;

import com.surprising.price.mark.service.MarkPriceAuditRetentionService;
import com.surprising.price.mark.service.MarkPriceService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 标记价格模块定时任务入口，只负责调用服务层。
 */
@Component
public class MarkPriceMaintenanceTask {

    private final MarkPriceService markPriceService;
    private final MarkPriceAuditRetentionService auditRetentionService;

    public MarkPriceMaintenanceTask(MarkPriceService markPriceService,
                                    MarkPriceAuditRetentionService auditRetentionService) {
        this.markPriceService = markPriceService;
        this.auditRetentionService = auditRetentionService;
    }

    @Scheduled(fixedRateString = "${surprising.price.mark.calculation.publish-interval-ms:1000}")
    public void publishMarkPrices() {
        markPriceService.publishMarkPrices();
    }

    @Scheduled(fixedDelayString = "${surprising.price.mark.audit.cleanup-delay-ms:60000}")
    public void cleanupAudit() {
        auditRetentionService.deleteExpiredAuditRows();
    }
}
