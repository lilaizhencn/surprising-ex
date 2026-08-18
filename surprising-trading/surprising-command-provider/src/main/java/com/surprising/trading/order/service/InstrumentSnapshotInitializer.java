package com.surprising.trading.order.service;

import com.surprising.instrument.api.cache.AbstractInstrumentSnapshotInitializer;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.product.api.ProductLine;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 订单服务启动时加载指定产品线的完整合约快照。
 */
@Service("orderInstrumentSnapshotInitializer")
public class InstrumentSnapshotInitializer extends AbstractInstrumentSnapshotInitializer {

    private final TradingOrderProperties properties;

    public InstrumentSnapshotInitializer(InstrumentRpcApi instrumentRpcApi,
                                         @Qualifier("orderInstrumentSnapshotCache") InstrumentSnapshotCache snapshotCache,
                                         TradingOrderProperties properties) {
        super(instrumentRpcApi, snapshotCache);
        this.properties = properties;
    }

    @Override
    protected Set<ProductLine> productLines() {
        return Set.of(properties.getKafka().getProductLine());
    }

    @Override
    protected String serviceName() {
        return "订单服务";
    }
}
