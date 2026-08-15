package com.surprising.price.mark.model;

public record MarkPriceEncoding(
        long instrumentVersion,
        long quoteScaleUnits,
        long priceTickUnits) {

    public MarkPriceEncoding {
        if (instrumentVersion <= 0 || quoteScaleUnits <= 0 || priceTickUnits <= 0) {
            throw new IllegalArgumentException("mark price encoding values must be positive");
        }
    }
}
