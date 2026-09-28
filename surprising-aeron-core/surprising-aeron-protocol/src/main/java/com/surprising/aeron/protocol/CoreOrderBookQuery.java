package com.surprising.aeron.protocol;

public record CoreOrderBookQuery(String instrumentId, int depth) {

    public static final int DEFAULT_DEPTH = 30;
    public static final int MAX_DEPTH = 100;

    public CoreOrderBookQuery {
        com.surprising.product.api.InstrumentIds.parse(instrumentId);
        if (depth == 0) depth = DEFAULT_DEPTH;
        if (depth < 1 || depth > MAX_DEPTH) {
            throw new IllegalArgumentException("invalid book depth");
        }
    }

    static boolean validSymbol(String value) {
        return com.surprising.product.api.InstrumentIds.valid(value);
    }
}
