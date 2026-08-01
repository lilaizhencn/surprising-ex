package com.surprising.price.index.service;

import com.surprising.instrument.api.cache.AbstractInstrumentSnapshotInitializer;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.product.api.ProductLine;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 指数服务统一通过 Instrument 聚合 RPC 初始化本地合约快照。 */
@Component
public class InstrumentSnapshotInitializer extends AbstractInstrumentSnapshotInitializer {

    private final IndexPriceProperties properties;
    private final IndexInstrumentConfigService configService;

    public InstrumentSnapshotInitializer(IndexPriceProperties properties,
                                         InstrumentRpcApi instrumentRpcApi,
                                         InstrumentSnapshotCache snapshotCache,
                                         IndexInstrumentConfigService configService) {
        super(instrumentRpcApi, snapshotCache);
        this.properties = properties;
        this.configService = configService;
    }

    @Override
    protected Set<ProductLine> productLines() {
        return Set.of(properties.getKafka().getProductLine());
    }

    @Override
    protected String serviceName() {
        return "指数价格服务";
    }

    @Override
    protected void afterInitialize() {
        configService.refresh();
    }
}
