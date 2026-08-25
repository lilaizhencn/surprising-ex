package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CommandFingerprint;
import java.util.List;
import java.util.Objects;

record PendingMatching(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                       List<Long> preMatchingCancellationOrderIds) {

    PendingMatching(long sequence, Operation operation, CoreMessage command) {
        this(sequence, operation, command, CommandFingerprint.of(command), List.of());
    }

    PendingMatching(long sequence, Operation operation, CoreMessage command,
                    List<Long> preMatchingCancellationOrderIds) {
        this(sequence, operation, command, CommandFingerprint.of(command), preMatchingCancellationOrderIds);
    }

    PendingMatching {
        if (sequence <= 0 || operation == null || command == null || fingerprint == null
                || preMatchingCancellationOrderIds == null
                || command.header().kind() != com.surprising.aeron.protocol.WireMessageKind.COMMAND) {
            throw new IllegalArgumentException("invalid pending matching request");
        }
        Objects.requireNonNull(command.header().commandId(), "commandId");
        preMatchingCancellationOrderIds = List.copyOf(preMatchingCancellationOrderIds);
    }

    PendingMatching withCommand(CoreMessage nextCommand) {
        return new PendingMatching(sequence, operation, nextCommand, fingerprint, preMatchingCancellationOrderIds);
    }

    PendingMatching withPreMatchingCancellations(List<Long> orderIds) {
        return new PendingMatching(sequence, operation, command, fingerprint, orderIds);
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
