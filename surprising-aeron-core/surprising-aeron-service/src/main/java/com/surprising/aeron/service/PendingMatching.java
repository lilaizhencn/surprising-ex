package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CommandFingerprint;
import java.util.Objects;

record PendingMatching(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint) {

    PendingMatching(long sequence, Operation operation, CoreMessage command) {
        this(sequence, operation, command, CommandFingerprint.of(command));
    }

    PendingMatching {
        if (sequence <= 0 || operation == null || command == null || fingerprint == null
                || command.header().kind() != com.surprising.aeron.protocol.WireMessageKind.COMMAND) {
            throw new IllegalArgumentException("invalid pending matching request");
        }
        Objects.requireNonNull(command.header().commandId(), "commandId");
    }

    PendingMatching withCommand(CoreMessage nextCommand) {
        return new PendingMatching(sequence, operation, nextCommand, fingerprint);
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
