package com.surprising.aeron.protocol;

import java.util.Objects;

public record CorePendingTransferView(long userId, TransferFundsCommand command) {

    public CorePendingTransferView {
        if (userId <= 0) throw new IllegalArgumentException("pending transfer userId must be positive");
        Objects.requireNonNull(command, "command");
    }
}
