package com.surprising.trading.order.service;

import com.surprising.instrument.provider.service.InstrumentService;
import jakarta.annotation.PostConstruct;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;

import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.product.api.ProductLine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 订单服务启动时加载指定产品线的完整合约快照。
 */
@Service("orderInstrumentSnapshotInitializer")
public class InstrumentSnapshotInitializer {

    private final InstrumentService instrumentService;
    private final InstrumentSnapshotCache snapshotCache;
    private final TradingOrderProperties properties;

    public InstrumentSnapshotInitializer(InstrumentService instrumentService,
                                         @Qualifier("orderInstrumentSnapshotCache") InstrumentSnapshotCache snapshotCache,
                                         TradingOrderProperties properties) {
        this.instrumentService = instrumentService;
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        ProductLine productLine = properties.getKafka().getProductLine();
        var snapshot = instrumentService.snapshot(productLine);
        if (snapshot == null || snapshot.productLine() != productLine) {
            throw new IllegalStateException("合约快照产品线不匹配: " + productLine);
        }
        snapshotCache.replace(productLine, snapshot.instruments(), snapshot.assetScales());
        if (!snapshotCache.ready(productLine)) {
            throw new IllegalStateException("合约快照为空，拒绝启动: " + productLine);
        }
    }
}
