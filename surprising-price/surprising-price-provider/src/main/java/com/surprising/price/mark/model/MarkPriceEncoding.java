package com.surprising.price.mark.model;

public record MarkPriceEncoding(
        long instrumentVersion,
        long quoteScaleUnits,
        long priceTickUnits,
        long baseScaleUnits,
        long quantityStepUnits) {

    public MarkPriceEncoding {
        if (instrumentVersion <= 0 || quoteScaleUnits <= 0 || priceTickUnits <= 0
                || baseScaleUnits <= 0 || quantityStepUnits <= 0) {
            throw new IllegalArgumentException("mark price encoding values must be positive");
        }
    }
}
