package com.surprising.price.index.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.cache.InstrumentSnapshotSupport;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.price.index.config.IndexPriceProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 指数服务统一通过 Instrument 聚合 RPC 初始化本地合约快照。 */
@Component
public class InstrumentSnapshotInitializer {

    private final IndexPriceProperties properties;
    private final InstrumentRpcApi instrumentRpcApi;
    private final InstrumentSnapshotCache snapshotCache;
    private final IndexInstrumentConfigService configService;

    public InstrumentSnapshotInitializer(IndexPriceProperties properties,
                                         InstrumentRpcApi instrumentRpcApi,
                                         InstrumentSnapshotCache snapshotCache,
                                         IndexInstrumentConfigService configService) {
        this.properties = properties;
        this.instrumentRpcApi = instrumentRpcApi;
        this.snapshotCache = snapshotCache;
        this.configService = configService;
    }

    @PostConstruct
    public void initialize() {
        var productLine = properties.getKafka().getProductLine();
        InstrumentSnapshotSupport.initialize(instrumentRpcApi, snapshotCache, productLine, "指数价格服务");
        configService.refresh();
    }
}
