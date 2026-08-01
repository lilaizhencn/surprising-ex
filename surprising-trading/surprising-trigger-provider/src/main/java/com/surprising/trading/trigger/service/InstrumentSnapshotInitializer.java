package com.surprising.trading.trigger.service;

import com.surprising.instrument.api.cache.AbstractInstrumentSnapshotInitializer;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.product.api.ProductLine;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 条件单服务启动时加载本产品线完整合约快照。
 */
@Service("triggerInstrumentSnapshotInitializer")
public class InstrumentSnapshotInitializer extends AbstractInstrumentSnapshotInitializer {

    private final TriggerProperties properties;

    public InstrumentSnapshotInitializer(InstrumentRpcApi instrumentRpcApi,
                                         @Qualifier("triggerInstrumentSnapshotCache") InstrumentSnapshotCache snapshotCache,
                                         TriggerProperties properties) {
        super(instrumentRpcApi, snapshotCache);
        this.properties = properties;
    }

    @Override
    protected Set<ProductLine> productLines() {
        return Set.of(properties.getKafka().getProductLine());
    }

    @Override
    protected String serviceName() {
        return "条件单服务";
    }
}
