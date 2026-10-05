package com.surprising.funding.provider.task;

import com.surprising.funding.provider.service.FundingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 资金费模块定时任务入口，只负责调用资金费服务层。
 */
@Component
public class FundingMaintenanceTask {

    private final FundingService fundingService;
    private final com.surprising.funding.provider.config.FundingProperties properties;
    private long lastPublishNanos;
    private long lastSettlementNanos;
    public FundingMaintenanceTask(FundingService fundingService, com.surprising.funding.provider.config.FundingProperties properties) {
        this.fundingService = fundingService;
        this.properties = properties;
    }

    @Scheduled(scheduler = "fundingScheduler", fixedDelay = 25)
    public void publishRates() {
        long now = System.nanoTime();
        if (lastPublishNanos != 0 && now - lastPublishNanos < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(properties.getCalculation().getPublishDelayMs())) return;
        lastPublishNanos = now;
        fundingService.publishRates();
    }

    @Scheduled(scheduler = "fundingScheduler", fixedDelay = 25)
    public void settleDueRates() {
        long now = System.nanoTime();
        if (lastSettlementNanos != 0 && now - lastSettlementNanos < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(properties.getSettlement().getSettleDelayMs())) return;
        lastSettlementNanos = now;
        fundingService.settleDueRates();
    }

}
