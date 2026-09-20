package com.surprising.aeron.protocol;

public final class CoreResponse {
    private final ResponseStatus status;
    private final ResponseStatus commandStatus;
    private final CoreResultCode resultCode;
    private final int routeVersion;
    private final long committedCoreSequence;
    private final long appliedCommandCount;
    private final byte[] data;
    private final int dataOffset;
    private final int dataLength;

    private static final byte[] EMPTY_DATA = new byte[0];

    public CoreResponse(ResponseStatus status, ResponseStatus commandStatus, CoreResultCode resultCode,
                        int routeVersion, long committedCoreSequence, long appliedCommandCount,
                        byte[] data) {
        this(status, commandStatus, resultCode, routeVersion, committedCoreSequence, appliedCommandCount,
                data, false);
    }

    private CoreResponse(ResponseStatus status, ResponseStatus commandStatus, CoreResultCode resultCode,
                         int routeVersion, long committedCoreSequence, long appliedCommandCount,
                         byte[] data, boolean ownedData) {
        this(status, commandStatus, resultCode, routeVersion, committedCoreSequence, appliedCommandCount,
                data, 0, data == null ? 0 : data.length, ownedData);
    }

    private CoreResponse(ResponseStatus status, ResponseStatus commandStatus, CoreResultCode resultCode,
                         int routeVersion, long committedCoreSequence, long appliedCommandCount,
                         byte[] data,
                         int dataOffset, int dataLength, boolean ownedData) {
        if (status == null || commandStatus == null || resultCode == null
                || routeVersion != CoreRoute.DEFAULT.version() || committedCoreSequence < 0
                || appliedCommandCount < 0 || dataOffset < 0
                || dataLength < 0 || data == null && (dataOffset != 0 || dataLength != 0)
                || data != null && dataOffset > data.length - dataLength) {
            throw new IllegalArgumentException("invalid core response");
        }
        this.status = status;
        this.commandStatus = commandStatus;
        this.resultCode = resultCode;
        this.routeVersion = routeVersion;
        this.committedCoreSequence = committedCoreSequence;
        this.appliedCommandCount = appliedCommandCount;
        if (data == null || dataLength == 0) {
            this.data = EMPTY_DATA;
            this.dataOffset = 0;
            this.dataLength = 0;
        } else if (ownedData && dataOffset == 0 && dataLength == data.length) {
            this.data = data;
            this.dataOffset = 0;
            this.dataLength = dataLength;
        } else if (ownedData) {
            this.data = data;
            this.dataOffset = dataOffset;
            this.dataLength = dataLength;
        } else {
            this.data = java.util.Arrays.copyOfRange(data, dataOffset, dataOffset + dataLength);
            this.dataOffset = 0;
            this.dataLength = dataLength;
        }
    }

    public CoreResponse(ResponseStatus status, ResponseStatus commandStatus, CoreResultCode resultCode,
                        long appliedCommandCount, byte[] data) {
        this(status, commandStatus, resultCode, CoreRoute.DEFAULT.version(), appliedCommandCount,
                appliedCommandCount, data);
    }

    public CoreResponse(ResponseStatus status, long appliedCommandCount) {
        this(status, status, CoreResultCode.NONE, CoreRoute.DEFAULT.version(), appliedCommandCount,
                appliedCommandCount, EMPTY_DATA);
    }

    public CoreResponse(ResponseStatus status, long appliedCommandCount, byte[] data) {
        this(status, status, CoreResultCode.NONE, CoreRoute.DEFAULT.version(), appliedCommandCount,
                appliedCommandCount, data);
    }

    public CoreResponse(
            ResponseStatus status,
            ResponseStatus commandStatus,
            long appliedCommandCount) {
        this(status, commandStatus, CoreResultCode.NONE, CoreRoute.DEFAULT.version(), appliedCommandCount,
                appliedCommandCount, EMPTY_DATA);
    }

    public CoreResponse(
            ResponseStatus status,
            ResponseStatus commandStatus,
            CoreResultCode resultCode,
            long appliedCommandCount) {
        this(status, commandStatus, resultCode, CoreRoute.DEFAULT.version(), appliedCommandCount,
                appliedCommandCount, EMPTY_DATA);
    }

    /* Public reads never expose storage transferred by the encoder. */
    public byte[] data() {
        return dataLength == 0 ? EMPTY_DATA : java.util.Arrays.copyOfRange(data, dataOffset, dataOffset + dataLength);
    }

    /**
     * Transfers an encoded payload into immutable response storage. The caller must never
     * mutate the array again, including through other retained references. Read-only result
     * retention may share it; reusable transport buffers must never be passed here.
     */
    public static CoreResponse owned(ResponseStatus status, ResponseStatus commandStatus,
                                     CoreResultCode resultCode, long appliedCommandCount,
                                     byte[] data) {
        return new CoreResponse(status, commandStatus, resultCode, CoreRoute.DEFAULT.version(),
                appliedCommandCount, appliedCommandCount, data, true);
    }

    /** Transfers a bounded slice of stable storage without copying it. */
    public static CoreResponse owned(ResponseStatus status, ResponseStatus commandStatus,
                                     CoreResultCode resultCode, long appliedCommandCount,
                                     byte[] data, int offset, int length) {
        return new CoreResponse(status, commandStatus, resultCode, CoreRoute.DEFAULT.version(),
                appliedCommandCount, appliedCommandCount,
                data, offset, length, true);
    }

    static CoreResponse decoded(ResponseStatus status, ResponseStatus commandStatus, CoreResultCode resultCode,
                                int routeVersion, long committedCoreSequence, long appliedCommandCount,
                                byte[] data) {
        return new CoreResponse(status, commandStatus, resultCode, routeVersion, committedCoreSequence,
                appliedCommandCount, data, true);
    }

    public ResponseStatus status() { return status; }
    public ResponseStatus commandStatus() { return commandStatus; }
    public CoreResultCode resultCode() { return resultCode; }
    public int routeVersion() { return routeVersion; }
    public long committedCoreSequence() { return committedCoreSequence; }
    public long appliedCommandCount() { return appliedCommandCount; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CoreResponse value)) return false;
        return status == value.status
                && commandStatus == value.commandStatus
                && resultCode == value.resultCode
                && routeVersion == value.routeVersion
                && committedCoreSequence == value.committedCoreSequence
                && appliedCommandCount == value.appliedCommandCount
                && data == value.data;
    }

    @Override
    public int hashCode() {
        int hash = 0;
        hash = 31 * hash + status.hashCode();
        hash = 31 * hash + commandStatus.hashCode();
        hash = 31 * hash + resultCode.hashCode();
        hash = 31 * hash + routeVersion;
        hash = 31 * hash + Long.hashCode(committedCoreSequence);
        hash = 31 * hash + Long.hashCode(appliedCommandCount);
        hash = 31 * hash + data.hashCode();
        return hash;
    }

    @Override
    public String toString() {
        return "CoreResponse[status=" + status + ", commandStatus=" + commandStatus
                + ", resultCode=" + resultCode + ", routeVersion=" + routeVersion
                + ", committedCoreSequence=" + committedCoreSequence + ", appliedCommandCount=" + appliedCommandCount
                + ", data=" + data + "]";
    }

    int dataLength() {
        return dataLength;
    }

    /** Internal no-copy access for the fixed response arena lifecycle. */
    public byte[] dataUnsafe() {
        return data;
    }

    int dataOffsetUnsafe() {
        return dataOffset;
    }
}
