package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import java.util.Objects;

record PendingMatching(long sequence, Operation operation, CoreMessage command, long attemptDeadline) {

    static final long ATTEMPT_TIMEOUT_MILLIS = 30_000;

    PendingMatching(long sequence, Operation operation, CoreMessage command) {
        this(sequence, operation, command, Long.MAX_VALUE);
    }

    PendingMatching {
        if (sequence <= 0 || operation == null || command == null
                || command.header().kind() != com.surprising.aeron.protocol.WireMessageKind.COMMAND) {
            throw new IllegalArgumentException("invalid pending matching request");
        }
        Objects.requireNonNull(command.header().commandId(), "commandId");
        if (attemptDeadline < 0) {
            throw new IllegalArgumentException("invalid matching attempt");
        }
    }

    PendingMatching withCommand(CoreMessage nextCommand) {
        return new PendingMatching(sequence, operation, nextCommand, attemptDeadline);
    }

    enum Operation {
        PLACE,
        CANCEL,
        REPLACE,
        AMEND,
        TRIGGER,
        LIQUIDATION,
        LIQUIDATION_BATCH,
        SETTLEMENT
    }
}
