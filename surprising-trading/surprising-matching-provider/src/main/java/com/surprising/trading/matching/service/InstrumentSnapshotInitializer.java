package com.surprising.trading.matching.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.trading.matching.config.MatchingProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

/**
 * 撮合服务启动时加载指定产品线的完整合约快照。
 */
@Service
public class InstrumentSnapshotInitializer {

    private final InstrumentRpcApi instrumentRpcApi;
    private final InstrumentSnapshotCache snapshotCache;
    private final MatchingProperties properties;

    public InstrumentSnapshotInitializer(InstrumentRpcApi instrumentRpcApi,
                                         InstrumentSnapshotCache snapshotCache,
                                         MatchingProperties properties) {
        this.instrumentRpcApi = instrumentRpcApi;
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        var productLine = properties.getKafka().getProductLine();
        var snapshot = instrumentRpcApi.snapshot(productLine);
        if (snapshot == null || snapshot.productLine() != productLine) {
            throw new IllegalStateException("撮合服务合约快照产品线不匹配: " + productLine);
        }
        snapshotCache.replace(productLine, snapshot.instruments(), snapshot.assetScales());
        if (!snapshotCache.ready(productLine)) {
            throw new IllegalStateException("撮合服务合约快照为空，拒绝启动撮合流量: " + productLine);
        }
    }
}
