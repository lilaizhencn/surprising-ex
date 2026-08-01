package com.surprising.price.mark.service;

import com.surprising.instrument.api.cache.AbstractInstrumentSnapshotInitializer;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.product.api.ProductLine;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 标记价格服务启动时加载指定产品线的完整合约快照。
 */
@Service("markInstrumentSnapshotInitializer")
public class InstrumentSnapshotInitializer extends AbstractInstrumentSnapshotInitializer {

    private final MarkPriceProperties properties;

    public InstrumentSnapshotInitializer(InstrumentRpcApi instrumentRpcApi,
                                         @Qualifier("markInstrumentSnapshotCache") InstrumentSnapshotCache snapshotCache,
                                         MarkPriceProperties properties) {
        super(instrumentRpcApi, snapshotCache);
        this.properties = properties;
    }

    @Override
    protected Set<ProductLine> productLines() {
        return Set.of(properties.getKafka().getProductLine());
    }

    @Override
    protected String serviceName() {
        return "标记价格服务";
    }
}
