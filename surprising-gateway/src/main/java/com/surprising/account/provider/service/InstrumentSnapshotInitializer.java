package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.instrument.provider.service.InstrumentService;
import jakarta.annotation.PostConstruct;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;

import com.surprising.product.api.ProductLine;
import org.springframework.stereotype.Service;

/**
 * 账户服务启动时加载指定产品线的完整合约快照。
 */
@Service
public class InstrumentSnapshotInitializer {

    private final InstrumentService instrumentService;
    private final InstrumentSnapshotCache snapshotCache;
    private final AccountProperties properties;

    public InstrumentSnapshotInitializer(InstrumentService instrumentService,
                                         @org.springframework.beans.factory.annotation.Qualifier("accountInstrumentSnapshotCache") InstrumentSnapshotCache snapshotCache,
                                         AccountProperties properties) {
        this.instrumentService = instrumentService;
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        ProductLine productLine = properties.getKafka().getProductLine();
        var snapshot = instrumentService.snapshot(productLine);
        if (snapshot == null || snapshot.productLine() != productLine) {
            throw new IllegalStateException("合约快照产品线不匹配: " + productLine);
        }
        snapshotCache.replace(productLine, snapshot.instruments(), snapshot.assetScales());
        if (!snapshotCache.ready(productLine)) {
            throw new IllegalStateException("合约快照为空，拒绝启动: " + productLine);
        }
    }
}
