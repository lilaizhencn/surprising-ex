package com.surprising.marketmaker.provider.service;

import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.marketmaker.provider.model.ExternalTrade;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookSnapshot;
import com.surprising.product.api.ProductLine;
import java.util.List;

public interface ReferenceMarketProvider {

    ReferenceOrderBookSnapshot snapshot(String instrumentId, ProductLine productLine, InstrumentResponse instrument);

    /** Newest third-party prints first. Providers without a trade feed return an empty list. */
    default List<ExternalTrade> drainTrades(String instrumentId, ProductLine productLine, int limit) {
        return List.of();
    }

    static ReferenceMarketProvider disabled() {
        return (instrumentId, productLine, instrument) -> null;
    }
}
