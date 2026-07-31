package com.surprising.marketmaker.provider.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.product.api.ProductLine;
import jakarta.annotation.PostConstruct;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 启动时只通过统一 Instrument 聚合接口构建做市 JVM 快照。 */
@Service
public class InstrumentSnapshotInitializer {

    private final MarketMakerProperties properties;
    private final InstrumentRpcApi instrumentRpcApi;
    private final InstrumentSnapshotCache snapshotCache;

    public InstrumentSnapshotInitializer(MarketMakerProperties properties,
                                          InstrumentRpcApi instrumentRpcApi,
                                          InstrumentSnapshotCache snapshotCache) {
        this.properties = properties;
        this.instrumentRpcApi = instrumentRpcApi;
        this.snapshotCache = snapshotCache;
    }

    @PostConstruct
    public void initialize() {
        Set<ProductLine> productLines = EnumSet.noneOf(ProductLine.class);
        properties.getStrategies().stream().filter(MarketMakerProperties.Strategy::isEnabled)
                .map(MarketMakerProperties.Strategy::getProductLine).forEach(productLines::add);
        properties.getReferenceMarket().getSources().stream()
                .filter(MarketMakerProperties.ReferenceMarket.Source::isEnabled)
                .map(MarketMakerProperties.ReferenceMarket.Source::getProductLine).forEach(productLines::add);
        if (productLines.isEmpty()) {
            return;
        }
        for (ProductLine productLine : productLines) {
            var snapshot = instrumentRpcApi.snapshot(productLine);
            if (snapshot == null || snapshot.productLine() != productLine) {
                throw new IllegalStateException("做市合约快照产品线不匹配: " + productLine);
            }
            snapshotCache.replace(productLine, snapshot.instruments(), snapshot.assetScales());
            if (!snapshotCache.ready(productLine)) {
                throw new IllegalStateException("做市服务合约快照为空，拒绝启动做市流量: " + productLine);
            }
        }
    }
}
