package com.surprising.aeron.protocol;

public record UpdateLeverageCommand(String symbol, CoreMarginMode marginMode, long leveragePpm) {
    public UpdateLeverageCommand {
        if (symbol == null || symbol.isBlank() || marginMode == null || leveragePpm < 1_000_000L) {
            throw new IllegalArgumentException("invalid leverage command");
        }
    }
}
