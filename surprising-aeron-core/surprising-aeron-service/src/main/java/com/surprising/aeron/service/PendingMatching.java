package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import java.util.Objects;

record PendingMatching(long sequence, Operation operation, CoreMessage command) {

    PendingMatching {
        if (sequence <= 0 || operation == null || command == null
                || command.header().kind() != com.surprising.aeron.protocol.WireMessageKind.COMMAND) {
            throw new IllegalArgumentException("invalid pending matching request");
        }
        Objects.requireNonNull(command.header().commandId(), "commandId");
    }

    enum Operation {
        PLACE,
        CANCEL,
        REPLACE,
        AMEND,
        TRIGGER,
        LIQUIDATION,
        SETTLEMENT
    }
}
