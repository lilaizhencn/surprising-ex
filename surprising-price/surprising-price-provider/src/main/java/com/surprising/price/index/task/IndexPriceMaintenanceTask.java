package com.surprising.price.index.task;

import com.surprising.price.index.service.ExchangeRateService;
import com.surprising.price.index.service.ExternalSpotConnectionService;
import com.surprising.price.index.service.IndexPriceAuditRetentionService;
import com.surprising.price.index.service.IndexPriceService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.surprising.price.index.config.IndexPriceProperties;
import org.springframework.context.annotation.DependsOn;

/**
 * 指数价格模块定时任务入口，只负责调用服务层。
 */
@Component
@DependsOn("priceBusinessSettingsService")
public class IndexPriceMaintenanceTask {

    private final IndexPriceProperties properties;
    private long refreshExternalConnectionsNanos;
    private long refreshFiatRatesNanos;
    private long refreshStableCoinRateNanos;
    private long calculateAndPublishNanos;

    private final ExternalSpotConnectionService externalSpotConnectionService;
    private final ExchangeRateService exchangeRateService;
    private final IndexPriceAuditRetentionService auditRetentionService;
    private final IndexPriceService indexPriceService;

    public IndexPriceMaintenanceTask(ExternalSpotConnectionService externalSpotConnectionService,
                                     ExchangeRateService exchangeRateService,
                                     IndexPriceAuditRetentionService auditRetentionService,
                                     IndexPriceService indexPriceService, IndexPriceProperties properties) {
        this.properties = properties;
        this.externalSpotConnectionService = externalSpotConnectionService;
        this.exchangeRateService = exchangeRateService;
        this.auditRetentionService = auditRetentionService;
        this.indexPriceService = indexPriceService;
    }

    @Scheduled(fixedDelay = 25)
    public void refreshExternalConnections() {
        long now = System.nanoTime();
        if (refreshExternalConnectionsNanos != 0 && now - refreshExternalConnectionsNanos < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(properties.getWebSocket().getRefreshDelayMs())) return;
        refreshExternalConnectionsNanos = now;
        externalSpotConnectionService.refreshConnections();
    }

    @Scheduled(fixedDelay = 25)
    public void refreshFiatRates() {
        long now = System.nanoTime();
        if (refreshFiatRatesNanos != 0 && now - refreshFiatRatesNanos < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(properties.getFiat().getRefreshDelayMs())) return;
        refreshFiatRatesNanos = now;
        exchangeRateService.refreshFiatRates();
    }

    @Scheduled(fixedDelay = 25)
    public void refreshStableCoinRate() {
        long now = System.nanoTime();
        if (refreshStableCoinRateNanos != 0 && now - refreshStableCoinRateNanos < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(properties.getFiat().getStableCoin().getRefreshDelayMs())) return;
        refreshStableCoinRateNanos = now;
        exchangeRateService.refreshStableCoinRate();
    }

    @Scheduled(fixedDelayString = "${surprising.price.index.audit.cleanup-delay-ms:60000}")
    public void cleanupAudit() {
        auditRetentionService.deleteExpiredAuditRows();
    }

    @Scheduled(fixedDelay = 25)
    public void calculateAndPublish() {
        long now = System.nanoTime();
        if (calculateAndPublishNanos != 0 && now - calculateAndPublishNanos < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(properties.getCalculation().getPollDelayMs())) return;
        calculateAndPublishNanos = now;
        indexPriceService.pollAndPublish();
    }
}
