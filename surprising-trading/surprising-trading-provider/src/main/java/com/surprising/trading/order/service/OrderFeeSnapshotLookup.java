package com.surprising.trading.order.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.cache.FeeScheduleSnapshotCache;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class OrderFeeSnapshotLookup {

    private final TradingOrderProperties properties;
    private final InstrumentSnapshotCache instrumentSnapshotCache;
    private final FeeScheduleSnapshotCache feeScheduleSnapshotCache;

    public OrderFeeSnapshotLookup(TradingOrderProperties properties,
                                  @Qualifier("orderInstrumentSnapshotCache") InstrumentSnapshotCache instrumentSnapshotCache,
                                  FeeScheduleSnapshotCache feeScheduleSnapshotCache) {
        this.properties = properties;
        this.instrumentSnapshotCache = instrumentSnapshotCache;
        this.feeScheduleSnapshotCache = feeScheduleSnapshotCache;
    }

    public Optional<OrderFeeSnapshot> lookup(ProductLine requestedProductLine,
                                             long userId,
                                             String symbol,
                                             long instrumentVersion,
                                             Instant now) {
        ProductLine productLine = requestedProductLine == null
                ? properties.getKafka().getProductLine() : requestedProductLine;
        var instrument = instrumentSnapshotCache.version(productLine, symbol, instrumentVersion);
        if (instrument.isEmpty()) {
            return Optional.empty();
        }
        var value = instrument.get();
        Optional<FeeScheduleResponse> schedule = feeScheduleSnapshotCache.effective(
                productLine, userId, symbol, now);
        if (schedule.isEmpty()) {
            return Optional.of(new OrderFeeSnapshot(productLine, value.makerFeeRatePpm(), value.takerFeeRatePpm(),
                    "INSTRUMENT"));
        }
        FeeScheduleResponse selected = schedule.get();
        String scope = selected.symbol() == null ? "GLOBAL" : "SYMBOL";
        return Optional.of(new OrderFeeSnapshot(productLine, selected.makerFeeRatePpm(),
                selected.takerFeeRatePpm(), selected.sourceType().name() + "_" + scope));
    }
}
