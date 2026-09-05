package com.surprising.aeron.protocol;

public record UpdatePositionModeCommand(CorePositionMode positionMode) {
    public UpdatePositionModeCommand {
        if (positionMode == null) {
            throw new IllegalArgumentException("positionMode is required");
        }
    }
}
