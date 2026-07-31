package com.surprising.price.index.task;

import com.surprising.price.index.service.ExchangeRateService;
import com.surprising.price.index.service.ExternalSpotConnectionService;
import com.surprising.price.index.service.IndexPriceAuditRetentionService;
import com.surprising.price.index.service.IndexPriceService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 指数价格模块定时任务入口，只负责调用服务层。
 */
@Component
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

    @Scheduled(fixedDelayString = "${surprising.price.index.web-socket.refresh-delay-ms:30000}")
    public void refreshExternalConnections() {
        externalSpotConnectionService.refreshConnections();
    }

    @Scheduled(
            initialDelayString = "${surprising.price.index.fiat.refresh-initial-delay-ms:1000}",
            fixedDelayString = "${surprising.price.index.fiat.refresh-delay-ms:3600000}")
    public void refreshFiatRates() {
        exchangeRateService.refreshFiatRates();
    }

    @Scheduled(
            initialDelayString = "${surprising.price.index.fiat.stable-coin.refresh-initial-delay-ms:1000}",
            fixedDelayString = "${surprising.price.index.fiat.stable-coin.refresh-delay-ms:10000}")
    public void refreshStableCoinRate() {
        exchangeRateService.refreshStableCoinRate();
    }

    @Scheduled(fixedDelayString = "${surprising.price.index.audit.cleanup-delay-ms:60000}")
    public void cleanupAudit() {
        auditRetentionService.deleteExpiredAuditRows();
    }

    @Scheduled(fixedDelayString = "${surprising.price.index.calculation.poll-delay-ms:1000}")
    public void calculateAndPublish() {
        indexPriceService.pollAndPublish();
    }
}
