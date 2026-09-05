package com.surprising.marketmaker.provider.service;

import com.surprising.instrument.api.cache.AbstractInstrumentSnapshotInitializer;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.product.api.ProductLine;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 启动时只通过统一 Instrument 聚合接口构建做市 JVM 快照。 */
@Service
public class InstrumentSnapshotInitializer extends AbstractInstrumentSnapshotInitializer {

    private final MarketMakerProperties properties;

    public InstrumentSnapshotInitializer(MarketMakerProperties properties,
                                          InstrumentRpcApi instrumentRpcApi,
                                          InstrumentSnapshotCache snapshotCache) {
        super(instrumentRpcApi, snapshotCache);
        this.properties = properties;
    }

    @Override
    protected Set<ProductLine> productLines() {
        Set<ProductLine> productLines = EnumSet.noneOf(ProductLine.class);
        properties.getStrategies().stream().filter(MarketMakerProperties.Strategy::isEnabled)
                .map(MarketMakerProperties.Strategy::getProductLine).forEach(productLines::add);
        properties.getReferenceMarket().getSources().stream()
                .filter(MarketMakerProperties.ReferenceMarket.Source::isEnabled)
                .map(MarketMakerProperties.ReferenceMarket.Source::getProductLine).forEach(productLines::add);
        if (productLines.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(productLines);
    }

    @Override
    protected String serviceName() {
        return "做市服务";
    }
}
