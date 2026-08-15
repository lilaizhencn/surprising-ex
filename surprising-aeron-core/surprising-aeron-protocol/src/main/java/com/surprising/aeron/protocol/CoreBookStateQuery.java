package com.surprising.aeron.protocol;

public record CoreBookStateQuery(String symbol, int depth) {
    public CoreBookStateQuery {
        symbol = symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
        if (depth < 1 || depth > 1_000) {
            throw new IllegalArgumentException("invalid book depth");
        }
    }
}
