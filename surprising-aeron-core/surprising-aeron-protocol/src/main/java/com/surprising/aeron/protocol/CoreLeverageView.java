package com.surprising.aeron.protocol;

public record CoreLeverageView(String symbol, CoreMarginMode marginMode, long leveragePpm) {
    public CoreLeverageView {
        if (symbol == null || symbol.isBlank() || marginMode == null || leveragePpm < 1_000_000L) {
            throw new IllegalArgumentException("invalid leverage view");
        }
    }
}
