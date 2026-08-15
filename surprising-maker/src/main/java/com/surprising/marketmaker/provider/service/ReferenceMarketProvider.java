package com.surprising.marketmaker.provider.service;

import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookSnapshot;
import com.surprising.product.api.ProductLine;

public interface ReferenceMarketProvider {

    ReferenceOrderBookSnapshot snapshot(String symbol, ProductLine productLine, InstrumentResponse instrument);

    static ReferenceMarketProvider disabled() {
        return (symbol, productLine, instrument) -> null;
    }
}
