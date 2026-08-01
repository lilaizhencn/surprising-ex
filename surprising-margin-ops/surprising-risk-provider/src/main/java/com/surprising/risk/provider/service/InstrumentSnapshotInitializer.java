package com.surprising.risk.provider.service;

import com.surprising.instrument.api.cache.AbstractInstrumentSnapshotInitializer;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.product.api.ProductLine;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 风险服务启动时加载指定产品线的完整合约快照。
 */
@Service
public class InstrumentSnapshotInitializer extends AbstractInstrumentSnapshotInitializer {

    private final RiskProperties properties;

    public InstrumentSnapshotInitializer(InstrumentRpcApi instrumentRpcApi,
                                         InstrumentSnapshotCache snapshotCache,
                                         RiskProperties properties) {
        super(instrumentRpcApi, snapshotCache);
        this.properties = properties;
    }

    @Override
    protected Set<ProductLine> productLines() {
        return Set.of(properties.getKafka().getProductLine());
    }

    @Override
    protected String serviceName() {
        return "风险服务";
    }
}
