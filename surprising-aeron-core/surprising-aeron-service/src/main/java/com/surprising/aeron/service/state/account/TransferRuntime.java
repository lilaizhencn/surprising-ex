package com.surprising.aeron.service.state.account;

import com.surprising.aeron.protocol.TransferFundsCommand;
import java.util.Objects;

public record TransferRuntime(long userId, TransferFundsCommand command) {

    public TransferRuntime {
        if (userId <= 0) throw new IllegalArgumentException("transfer userId must be positive");
        Objects.requireNonNull(command, "command");
        if (userId != command.sourceUserId()) throw new IllegalArgumentException("pending transfer sender mismatch");
    }

    public long transferId() {
        return command.transferId();
    }
}
