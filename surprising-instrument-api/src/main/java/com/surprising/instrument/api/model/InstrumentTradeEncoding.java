package com.surprising.instrument.api.model;

/** Read-only units for decoding a committed trade; never an executable instrument specification. */
public record InstrumentTradeEncoding(long priceTickUnits, long quantityStepUnits, long baseScaleUnits, long quoteScaleUnits) {
    public InstrumentTradeEncoding {
        if (priceTickUnits<=0 || quantityStepUnits<=0 || baseScaleUnits<=0 || quoteScaleUnits<=0)
            throw new IllegalArgumentException("invalid trade encoding");
    }
}
