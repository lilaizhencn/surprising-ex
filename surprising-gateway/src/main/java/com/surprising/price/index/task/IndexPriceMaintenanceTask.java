package com.surprising.price.index.task;

import com.surprising.price.index.service.ExchangeRateService;
import com.surprising.price.index.service.ExternalSpotConnectionService;
import com.surprising.price.index.service.IndexPriceAuditRetentionService;
import com.surprising.price.index.service.IndexPriceService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;

/**
 * 指数价格模块定时任务入口，只负责调用服务层。
 */
@Component
@DependsOn("priceBusinessSettingsService")
public class IndexPriceMaintenanceTask {


    private final ExternalSpotConnectionService externalSpotConnectionService;
    private final ExchangeRateService exchangeRateService;
    private final IndexPriceAuditRetentionService auditRetentionService;
    private final IndexPriceService indexPriceService;

    public IndexPriceMaintenanceTask(ExternalSpotConnectionService externalSpotConnectionService,
                                     ExchangeRateService exchangeRateService,
                                     IndexPriceAuditRetentionService auditRetentionService,
                                     IndexPriceService indexPriceService) {
        this.externalSpotConnectionService = externalSpotConnectionService;
        this.exchangeRateService = exchangeRateService;
        this.auditRetentionService = auditRetentionService;
        this.indexPriceService = indexPriceService;
    }

    public void refreshExternalConnections() {
        externalSpotConnectionService.refreshConnections();
    }

    public void refreshFiatRates() {
        exchangeRateService.refreshFiatRates();
    }

    public void refreshStableCoinRate() {
        exchangeRateService.refreshStableCoinRate();
    }

    @Scheduled(fixedDelayString = "${surprising.price.index.audit.cleanup-delay-ms:60000}")
    public void cleanupAudit() {
        auditRetentionService.deleteExpiredAuditRows();
    }

    public void calculateAndPublish() {
        indexPriceService.pollAndPublish();
    }
}
