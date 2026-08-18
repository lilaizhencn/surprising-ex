package com.surprising.trading.order.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** 直接从本地合约 JVM 快照读取下单规则。 */
@Service
public class InstrumentSnapshotRuleLookup implements InstrumentRuleLookup {

    private final TradingOrderProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public InstrumentSnapshotRuleLookup(TradingOrderProperties properties,
                                        @Qualifier("orderInstrumentSnapshotCache") InstrumentSnapshotCache snapshotCache) {
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    @Override
    public Optional<InstrumentRule> currentRule(String symbol) {
        var productLine = properties.getKafka().getProductLine();
        if (!snapshotCache.initialized(productLine)) {
            throw new IllegalStateException("下单合约 JVM 快照尚未就绪");
        }
        return snapshotCache.current(productLine, symbol).map(this::toRule);
    }

    private InstrumentRule toRule(InstrumentResponse value) {
        return new InstrumentRule(value.symbol(), value.version(), value.status().name(), value.instrumentType(),
                value.contractType(), value.baseAsset(), value.quoteAsset(), value.settleAsset(),
                value.supportedOrderTypes() == null ? Set.of() : Set.copyOf(value.supportedOrderTypes()),
                value.supportedTimeInForce() == null ? Set.of() : Set.copyOf(value.supportedTimeInForce()),
                value.marketOrderEnabled(), value.postOnlyEnabled(), value.reduceOnlyEnabled(),
                value.quantityStepUnits(), value.minQuantitySteps(), value.maxQuantitySteps(),
                value.minNotionalUnits(), value.maxNotionalUnits(), value.notionalMultiplierUnits(),
                value.maxLeveragePpm(), value.initialMarginRatePpm());
    }

}
