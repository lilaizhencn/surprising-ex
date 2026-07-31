package com.surprising.trading.trigger.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.cache.InstrumentSnapshotSupport;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.trading.trigger.config.TriggerProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

/**
 * 条件单服务启动时加载本产品线完整合约快照。
 */
@Service
public class InstrumentSnapshotInitializer {

    private final InstrumentRpcApi instrumentRpcApi;
    private final InstrumentSnapshotCache snapshotCache;
    private final TriggerProperties properties;

    public InstrumentSnapshotInitializer(InstrumentRpcApi instrumentRpcApi,
                                         InstrumentSnapshotCache snapshotCache,
                                         TriggerProperties properties) {
        this.instrumentRpcApi = instrumentRpcApi;
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        var productLine = properties.getKafka().getProductLine();
        InstrumentSnapshotSupport.initialize(instrumentRpcApi, snapshotCache, productLine, "条件单服务");
    }
}
