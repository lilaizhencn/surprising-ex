package com.surprising.trading.matching.service;

import com.surprising.instrument.api.cache.AbstractInstrumentSnapshotInitializer;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.product.api.ProductLine;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 撮合服务启动时加载指定产品线的完整合约快照。
 */
@Service
public class InstrumentSnapshotInitializer extends AbstractInstrumentSnapshotInitializer {

    private final MatchingProperties properties;

    public InstrumentSnapshotInitializer(InstrumentRpcApi instrumentRpcApi,
                                         InstrumentSnapshotCache snapshotCache,
                                         MatchingProperties properties) {
        super(instrumentRpcApi, snapshotCache);
        this.properties = properties;
    }

    @Override
    protected Set<ProductLine> productLines() {
        return Set.of(properties.getKafka().getProductLine());
    }

    @Override
    protected String serviceName() {
        return "撮合服务";
    }
}
