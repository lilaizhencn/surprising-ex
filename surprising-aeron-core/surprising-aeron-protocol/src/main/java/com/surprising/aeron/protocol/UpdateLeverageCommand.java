package com.surprising.aeron.protocol;

public record UpdateLeverageCommand(String instrumentId, CoreMarginMode marginMode, long leveragePpm) {
    public UpdateLeverageCommand {
        if (instrumentId == null || instrumentId.isBlank() || marginMode == null || leveragePpm < 1_000_000L) {
            throw new IllegalArgumentException("invalid leverage command");
        }
    }
}
