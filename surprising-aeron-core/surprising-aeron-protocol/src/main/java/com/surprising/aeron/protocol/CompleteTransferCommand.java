package com.surprising.aeron.protocol;

public record CompleteTransferCommand(long transferId) {

    public CompleteTransferCommand {
        if (transferId <= 0) throw new IllegalArgumentException("transferId must be positive");
    }
}
