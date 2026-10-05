package com.surprising.price.mark.task;

import com.surprising.price.mark.service.MarkPriceAuditRetentionService;
import com.surprising.price.mark.service.MarkPriceService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.surprising.price.mark.config.MarkPriceProperties;
import org.springframework.context.annotation.DependsOn;

/**
 * 标记价格模块定时任务入口，只负责调用服务层。
 */
@Component
@DependsOn("priceBusinessSettingsService")
public class MarkPriceMaintenanceTask {

    private final MarkPriceProperties properties;
    private long publishMarkPricesNanos;

    private final MarkPriceService markPriceService;
    private final MarkPriceAuditRetentionService auditRetentionService;

    public MarkPriceMaintenanceTask(MarkPriceService markPriceService,
                                    MarkPriceAuditRetentionService auditRetentionService, MarkPriceProperties properties) {
        this.properties = properties;
        this.markPriceService = markPriceService;
        this.auditRetentionService = auditRetentionService;
    }

    @Scheduled(fixedDelay = 25)
    public void publishMarkPrices() {
        long now = System.nanoTime();
        if (publishMarkPricesNanos != 0 && now - publishMarkPricesNanos < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(properties.getCalculation().getPublishIntervalMs())) return;
        publishMarkPricesNanos = now;
        markPriceService.publishMarkPrices();
    }

    @Scheduled(fixedDelayString = "${surprising.price.mark.audit.cleanup-delay-ms:60000}")
    public void cleanupAudit() {
        auditRetentionService.deleteExpiredAuditRows();
    }
}
