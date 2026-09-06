package com.surprising.instrument.provider.repository;

/** Identifies the sole current configuration of a product-line instrument. */
public record InstrumentKey(com.surprising.product.api.ProductLine productLine, String symbol) {
}
