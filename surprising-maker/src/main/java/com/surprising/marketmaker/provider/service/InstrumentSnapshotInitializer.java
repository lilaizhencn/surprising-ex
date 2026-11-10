package com.surprising.marketmaker.provider.service;

import com.surprising.instrument.api.cache.AbstractInstrumentSnapshotInitializer;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentSnapshotResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.consumer.MakerQuoteWakeups;
import com.surprising.product.api.ProductLine;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 做市商合约规格快照初始化与定时动态刷新服务。
 * <p>
 * 1. 启动时通过 Instrument 聚合 RPC 构建做市 JVM 快照；
 * 2. 运行时定时拉取最新合约规格与状态，完全解耦 Kafka；
 * 3. 一旦检测到合约状态（如停牌/恢复）或规格（变动步长/费率/乘数）变更，实时调整做市状态并排空或重新报价。
 */
@Service
@Slf4j
public class InstrumentSnapshotInitializer extends AbstractInstrumentSnapshotInitializer {

    private final MarketMakerProperties properties;
    private final InstrumentRpcApi instrumentRpcApi;
    private final MakerQuoteWakeups wakeups;
    private final ObjectProvider<MarketMakerService> marketMakerServiceProvider;

    public InstrumentSnapshotInitializer(MarketMakerProperties properties,
                                          InstrumentRpcApi instrumentRpcApi,
                                          InstrumentSnapshotCache snapshotCache,
                                          MakerQuoteWakeups wakeups,
                                          ObjectProvider<MarketMakerService> marketMakerServiceProvider) {
        super(instrumentRpcApi, snapshotCache);
        this.properties = properties;
        this.instrumentRpcApi = instrumentRpcApi;
        this.wakeups = wakeups;
        this.marketMakerServiceProvider = marketMakerServiceProvider;
    }

    @Override
    protected Set<ProductLine> productLines() {
        return Set.of(com.surprising.product.api.ProductLineConfiguration.require(
                properties.getProductLine(), "market-maker instrument snapshot"));
    }

    @Override
    protected String serviceName() {
        return "做市服务";
    }

    /**
     * 定时通过 RPC 拉取合约最新快照，检测状态或规格变更并实时调整做市状态。
     */
    @Scheduled(fixedDelayString = "${surprising.market-maker.instrument.refresh-interval:5000}", initialDelay = 5000)
    public void refreshSnapshots() {
        for (ProductLine productLine : productLines()) {
            try {
                refreshProductLine(productLine);
            } catch (Exception ex) {
                log.warn("做市商定时刷新合约规格快照失败 productLine={}: {}", productLine, ex.getMessage());
            }
        }
    }

    private void refreshProductLine(ProductLine productLine) {
        InstrumentSnapshotResponse snapshot = instrumentRpcApi.snapshot(productLine);
        if (snapshot == null || snapshot.instruments() == null) return;

        MarketMakerService service = marketMakerServiceProvider.getIfAvailable();

        for (InstrumentResponse latest : snapshot.instruments()) {
            if (latest == null || latest.contractType() == null || latest.contractType().productLine() != productLine) {
                continue;
            }
            int instrumentId = latest.instrumentId();
            String idStr = String.valueOf(instrumentId);
            var currentOpt = snapshotCache.current(productLine, instrumentId);

            boolean isChanged = false;
            if (currentOpt.isEmpty()) {
                isChanged = true;
            } else {
                InstrumentResponse existing = currentOpt.get();
                if (existing.status() != latest.status()
                        || existing.changeId() != latest.changeId()
                        || !Objects.equals(existing.makerFeeRatePpm(), latest.makerFeeRatePpm())
                        || existing.priceTickUnits() != latest.priceTickUnits()
                        || existing.quantityStepUnits() != latest.quantityStepUnits()) {
                    isChanged = true;
                    log.info("检测到合约规格/状态变更: productLine={} instrumentId={} 旧状态={} 新状态={} 版本={}",
                            productLine, instrumentId, existing.status(), latest.status(), latest.changeId());
                }
            }

            if (isChanged) {
                // 如果合约变为非交易状态（例如停牌、维护、交割），立即排空盘口残留挂单
                boolean isTradable = latest.status() == InstrumentStatus.TRADING;
                if (!isTradable && service != null) {
                    service.onInstrumentStatusChanged(productLine, idStr, false);
                }
                // 唤醒做市策略感知最新合约规格与状态
                wakeups.changed(productLine, idStr);
            }
        }

        // 原子替换本地快照缓存
        snapshotCache.replace(productLine, snapshot.instruments(), snapshot.assetScales());
    }
}
