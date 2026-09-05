package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.TransferFundsCommand;
import java.util.Objects;

public record TransferRuntime(long userId, TransferFundsCommand command) {

    public TransferRuntime {
        if (userId <= 0) throw new IllegalArgumentException("transfer userId must be positive");
        Objects.requireNonNull(command, "command");
    }

    public long transferId() {
        return command.transferId();
    }
}
