package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import java.util.Objects;

record PendingMatching(long sequence, Operation operation, CoreMessage command,
                       long attemptGeneration, long attemptDeadline, int recoveryAttempts,
                       long attemptToken) {

    static final long ATTEMPT_TIMEOUT_MILLIS = 30_000;
    static final int MAX_RECOVERY_ATTEMPTS = 1;

    PendingMatching(long sequence, Operation operation, CoreMessage command) {
        this(sequence, operation, command, 0, Long.MAX_VALUE, 0, 1);
    }

    PendingMatching {
        if (sequence <= 0 || operation == null || command == null
                || command.header().kind() != com.surprising.aeron.protocol.WireMessageKind.COMMAND) {
            throw new IllegalArgumentException("invalid pending matching request");
        }
        Objects.requireNonNull(command.header().commandId(), "commandId");
        if (attemptGeneration < 0 || attemptDeadline < 0 || recoveryAttempts < 0
                || recoveryAttempts > MAX_RECOVERY_ATTEMPTS || attemptToken <= 0) {
            throw new IllegalArgumentException("invalid matching attempt");
        }
    }

    PendingMatching withAttempt(long generation, long deadline, int retries) {
        return new PendingMatching(sequence, operation, command, generation, deadline, retries,
                Math.addExact(attemptToken, 1));
    }

    PendingMatching withCommand(CoreMessage nextCommand) {
        return new PendingMatching(sequence, operation, nextCommand, attemptGeneration, attemptDeadline,
                recoveryAttempts, attemptToken);
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
