package com.surprising.insurance.provider.service;

import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.cache.InstrumentSnapshotSupport;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

/**
 * 保险基金服务启动时加载本产品线完整合约快照。
 */
@Service
public class InstrumentSnapshotInitializer {

    private final InstrumentRpcApi instrumentRpcApi;
    private final InstrumentSnapshotCache snapshotCache;
    private final InsuranceProperties properties;

    public InstrumentSnapshotInitializer(InstrumentRpcApi instrumentRpcApi,
                                         InstrumentSnapshotCache snapshotCache,
                                         InsuranceProperties properties) {
        this.instrumentRpcApi = instrumentRpcApi;
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        var productLine = properties.getKafka().getProductLine();
        InstrumentSnapshotSupport.initialize(instrumentRpcApi, snapshotCache, productLine, "保险基金服务");
    }
}
