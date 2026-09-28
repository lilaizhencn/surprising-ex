package com.surprising.aeron.protocol;

public record CoreLeverageView(String instrumentId, CoreMarginMode marginMode, long leveragePpm) {
    public CoreLeverageView {
        if (instrumentId == null || instrumentId.isBlank() || marginMode == null || leveragePpm < 1_000_000L) {
            throw new IllegalArgumentException("invalid leverage view");
        }
    }
}
