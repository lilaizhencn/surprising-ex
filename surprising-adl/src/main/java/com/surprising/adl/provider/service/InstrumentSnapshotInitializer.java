package com.surprising.adl.provider.service;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.instrument.api.cache.AbstractInstrumentSnapshotInitializer;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.product.api.ProductLine;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * ADL 服务启动时加载本产品线完整合约快照。
 */
@Service("adlInstrumentSnapshotInitializer")
public class InstrumentSnapshotInitializer extends AbstractInstrumentSnapshotInitializer {

    private final AdlProperties properties;

    public InstrumentSnapshotInitializer(InstrumentRpcApi instrumentRpcApi,
                                         @org.springframework.beans.factory.annotation.Qualifier("adlInstrumentSnapshotCache")
                                         InstrumentSnapshotCache snapshotCache,
                                         AdlProperties properties) {
        super(instrumentRpcApi, snapshotCache);
        this.properties = properties;
    }

    @Override
    protected Set<ProductLine> productLines() {
        return Set.of(properties.getKafka().getProductLine());
    }

    @Override
    protected String serviceName() {
        return "ADL 服务";
    }
}
