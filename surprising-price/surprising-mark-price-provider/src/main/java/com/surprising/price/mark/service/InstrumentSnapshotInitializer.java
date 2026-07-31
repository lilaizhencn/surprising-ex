package com.surprising.price.mark.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.price.mark.config.MarkPriceProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

/**
 * 标记价格服务启动时加载指定产品线的完整合约快照。
 */
@Service
public class InstrumentSnapshotInitializer {

    private final InstrumentRpcApi instrumentRpcApi;
    private final InstrumentSnapshotCache snapshotCache;
    private final MarkPriceProperties properties;

    public InstrumentSnapshotInitializer(InstrumentRpcApi instrumentRpcApi,
                                         InstrumentSnapshotCache snapshotCache,
                                         MarkPriceProperties properties) {
        this.instrumentRpcApi = instrumentRpcApi;
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        var productLine = properties.getKafka().getProductLine();
        var snapshot = instrumentRpcApi.snapshot(productLine);
        if (snapshot == null || snapshot.productLine() != productLine) {
            throw new IllegalStateException("标记价格服务合约快照产品线不匹配: " + productLine);
        }
        snapshotCache.replace(productLine, snapshot.instruments(), snapshot.assetScales());
        if (!snapshotCache.ready(productLine)) {
            throw new IllegalStateException("标记价格服务合约快照为空，拒绝启动行情流量: " + productLine);
        }
    }
}
