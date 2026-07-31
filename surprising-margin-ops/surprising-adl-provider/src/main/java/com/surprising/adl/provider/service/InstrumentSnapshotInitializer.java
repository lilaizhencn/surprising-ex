package com.surprising.adl.provider.service;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

/**
 * ADL 服务启动时加载本产品线完整合约快照。
 */
@Service
public class InstrumentSnapshotInitializer {

    private final InstrumentRpcApi instrumentRpcApi;
    private final InstrumentSnapshotCache snapshotCache;
    private final AdlProperties properties;

    public InstrumentSnapshotInitializer(InstrumentRpcApi instrumentRpcApi,
                                         InstrumentSnapshotCache snapshotCache,
                                         AdlProperties properties) {
        this.instrumentRpcApi = instrumentRpcApi;
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        var productLine = properties.getKafka().getProductLine();
        var snapshot = instrumentRpcApi.snapshot(productLine);
        if (snapshot == null || snapshot.productLine() != productLine) {
            throw new IllegalStateException("ADL 服务合约快照产品线不匹配: " + productLine);
        }
        snapshotCache.replace(productLine, snapshot.instruments(), snapshot.assetScales());
        if (!snapshotCache.ready(productLine)) {
            throw new IllegalStateException("ADL 服务合约快照为空，拒绝启动 ADL 流量: " + productLine);
        }
    }
}
