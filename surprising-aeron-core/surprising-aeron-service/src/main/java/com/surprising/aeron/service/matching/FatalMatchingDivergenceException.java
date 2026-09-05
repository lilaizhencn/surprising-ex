package com.surprising.aeron.service.matching;

public final class FatalMatchingDivergenceException extends IllegalStateException {

    private final String operation;
    private final long coreSequence;
    private final long snapshotId;

    public FatalMatchingDivergenceException(
            String operation,
            long coreSequence,
            long snapshotId,
            String detail) {
        super(operation + " failed at coreSequence=" + coreSequence + ", snapshotId=" + snapshotId
                + ": " + detail);
        this.operation = operation;
        this.coreSequence = coreSequence;
        this.snapshotId = snapshotId;
    }

    public FatalMatchingDivergenceException(
            String operation,
            long coreSequence,
            long snapshotId,
            String detail,
            Throwable cause) {
        super(operation + " failed at coreSequence=" + coreSequence + ", snapshotId=" + snapshotId
                + ": " + detail, cause);
        this.operation = operation;
        this.coreSequence = coreSequence;
        this.snapshotId = snapshotId;
    }

    public String operation() {
        return operation;
    }

    public long coreSequence() {
        return coreSequence;
    }

    public long snapshotId() {
        return snapshotId;
    }
}
