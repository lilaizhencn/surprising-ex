package com.surprising.funding.provider.service;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.cache.InstrumentSnapshotSupport;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

/** 启动时通过 Instrument 聚合 RPC 加载资金费计算所需的完整快照。 */
@Service
public class InstrumentSnapshotInitializer {

    private final FundingProperties properties;
    private final InstrumentRpcApi instrumentRpcApi;
    private final InstrumentSnapshotCache snapshotCache;

    public InstrumentSnapshotInitializer(FundingProperties properties,
                                         InstrumentRpcApi instrumentRpcApi,
                                         InstrumentSnapshotCache snapshotCache) {
        this.properties = properties;
        this.instrumentRpcApi = instrumentRpcApi;
        this.snapshotCache = snapshotCache;
    }

    @PostConstruct
    public void initialize() {
        var productLine = properties.getKafka().getProductLine();
        InstrumentSnapshotSupport.initialize(instrumentRpcApi, snapshotCache, productLine, "资金费服务");
    }
}
