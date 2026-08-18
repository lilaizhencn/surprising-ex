package com.surprising.derivatives.lifecycle;

import com.surprising.instrument.api.cache.AbstractInstrumentSnapshotInitializer;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.provider.config.RiskProperties;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class DerivativesInstrumentSnapshotInitializer extends AbstractInstrumentSnapshotInitializer {

    private final RiskProperties properties;

    public DerivativesInstrumentSnapshotInitializer(InstrumentRpcApi instrumentRpcApi,
                                                    @Qualifier("derivativesInstrumentSnapshotCache")
                                                    InstrumentSnapshotCache snapshotCache,
                                                    RiskProperties properties) {
        super(instrumentRpcApi, snapshotCache);
        this.properties = properties;
    }

    @Override
    protected Set<ProductLine> productLines() {
        return Set.of(properties.getProductLine());
    }

    @Override
    protected String serviceName() {
        return "衍生品生命周期服务";
    }
}
