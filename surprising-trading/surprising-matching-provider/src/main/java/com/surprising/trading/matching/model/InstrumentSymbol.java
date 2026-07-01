package com.surprising.trading.matching.model;

public record InstrumentSymbol(
        String symbol,
        String baseAsset,
        String quoteAsset,
        String settleAsset) {
}
