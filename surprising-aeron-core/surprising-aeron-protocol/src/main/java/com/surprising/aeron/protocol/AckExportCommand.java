package com.surprising.aeron.protocol;

public record AckExportCommand(long throughSequence) {
    public AckExportCommand {
        if (throughSequence <= 0) {
            throw new IllegalArgumentException("export ack sequence must be positive");
        }
    }
}
