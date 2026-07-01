package com.surprising.trading.matching.model;

public record MatchingSymbol(
        String symbol,
        int symbolId,
        int baseCurrencyId,
        int quoteCurrencyId) {
}
