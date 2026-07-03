package com.surprising.marketmaker.provider.service;

import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookSnapshot;

public interface ReferenceMarketProvider {

    ReferenceOrderBookSnapshot snapshot(String symbol, InstrumentResponse instrument);

    static ReferenceMarketProvider disabled() {
        return (symbol, instrument) -> null;
    }
}
