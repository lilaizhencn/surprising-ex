package com.surprising.aeron.protocol;

public final class CoreResponse {
    private final ResponseStatus status;
    private final ResponseStatus commandStatus;
    private final CoreResultCode resultCode;
    private final int routeVersion;
    private final long committedCoreSequence;
    private final long appliedCommandCount;
    private final long requiredExportSequence;
    private final long stateHash;
    private final byte[] data;

    private static final byte[] EMPTY_DATA = new byte[0];

    public CoreResponse(ResponseStatus status, ResponseStatus commandStatus, CoreResultCode resultCode,
                        int routeVersion, long committedCoreSequence, long appliedCommandCount,
                        long requiredExportSequence, long stateHash, byte[] data) {
        this(status, commandStatus, resultCode, routeVersion, committedCoreSequence, appliedCommandCount, requiredExportSequence, stateHash, data, false);
    }

    private CoreResponse(ResponseStatus status, ResponseStatus commandStatus, CoreResultCode resultCode,
                         int routeVersion, long committedCoreSequence, long appliedCommandCount,
                         long requiredExportSequence, long stateHash, byte[] data, boolean ownedData) {
        if (status == null || commandStatus == null || resultCode == null
                || routeVersion != CoreRoute.DEFAULT.version() || committedCoreSequence < 0
                || appliedCommandCount < 0 || requiredExportSequence < 0) {
            throw new IllegalArgumentException("invalid core response");
        }
        this.status = status;
        this.commandStatus = commandStatus;
        this.resultCode = resultCode;
        this.routeVersion = routeVersion;
        this.committedCoreSequence = committedCoreSequence;
        this.appliedCommandCount = appliedCommandCount;
        this.requiredExportSequence = requiredExportSequence;
        this.stateHash = stateHash;
        this.data = data == null || data.length == 0 ? EMPTY_DATA : (ownedData ? data : data.clone());
    }

    public CoreResponse(ResponseStatus status, ResponseStatus commandStatus, CoreResultCode resultCode,
                        long appliedCommandCount, long stateHash, byte[] data) {
        this(status, commandStatus, resultCode, CoreRoute.DEFAULT.version(), appliedCommandCount,
                appliedCommandCount, 0, stateHash, data);
    }

    public CoreResponse(ResponseStatus status, long appliedCommandCount, long stateHash) {
        this(status, status, CoreResultCode.NONE, CoreRoute.DEFAULT.version(), appliedCommandCount,
                appliedCommandCount, 0, stateHash, EMPTY_DATA);
    }

    public CoreResponse(ResponseStatus status, long appliedCommandCount, long stateHash, byte[] data) {
        this(status, status, CoreResultCode.NONE, CoreRoute.DEFAULT.version(), appliedCommandCount,
                appliedCommandCount, 0, stateHash, data);
    }

    public CoreResponse(
            ResponseStatus status,
            ResponseStatus commandStatus,
            long appliedCommandCount,
            long stateHash) {
        this(status, commandStatus, CoreResultCode.NONE, CoreRoute.DEFAULT.version(), appliedCommandCount,
                appliedCommandCount, 0, stateHash, EMPTY_DATA);
    }

    public CoreResponse(
            ResponseStatus status,
            ResponseStatus commandStatus,
            CoreResultCode resultCode,
            long appliedCommandCount,
            long stateHash) {
        this(status, commandStatus, resultCode, CoreRoute.DEFAULT.version(), appliedCommandCount,
                appliedCommandCount, 0, stateHash, EMPTY_DATA);
    }

    public CoreResponse(ResponseStatus status, ResponseStatus commandStatus, CoreResultCode resultCode,
                        long appliedCommandCount, long requiredExportSequence, long stateHash, byte[] data) {
        this(status, commandStatus, resultCode, CoreRoute.DEFAULT.version(), appliedCommandCount,
                appliedCommandCount, requiredExportSequence, stateHash, data);
    }

    public CoreResponse withCommittedCoreSequence(long sequence) {
        return new CoreResponse(status, commandStatus, resultCode, routeVersion, sequence,
                appliedCommandCount, requiredExportSequence, stateHash, data, true);
    }

    /* Public reads never expose storage transferred by the encoder. */
    public byte[] data() {
        return data.length == 0 ? EMPTY_DATA : data.clone();
    }

    /**
     * Transfers an encoded payload into immutable response storage. The caller must never
     * mutate the array again, including through other retained references. Read-only result
     * retention may share it; reusable transport buffers must never be passed here.
     */
    public static CoreResponse owned(ResponseStatus status, ResponseStatus commandStatus,
                                     CoreResultCode resultCode, long appliedCommandCount,
                                     long requiredExportSequence, long stateHash, byte[] data) {
        return new CoreResponse(status, commandStatus, resultCode, CoreRoute.DEFAULT.version(),
                appliedCommandCount, appliedCommandCount, requiredExportSequence, stateHash, data, true);
    }

    static CoreResponse decoded(ResponseStatus status, ResponseStatus commandStatus, CoreResultCode resultCode,
                                int routeVersion, long committedCoreSequence, long appliedCommandCount,
                                long requiredExportSequence, long stateHash, byte[] data) {
        return new CoreResponse(status, commandStatus, resultCode, routeVersion, committedCoreSequence, appliedCommandCount, requiredExportSequence, stateHash, data, true);
    }

    public ResponseStatus status() { return status; }
    public ResponseStatus commandStatus() { return commandStatus; }
    public CoreResultCode resultCode() { return resultCode; }
    public int routeVersion() { return routeVersion; }
    public long committedCoreSequence() { return committedCoreSequence; }
    public long appliedCommandCount() { return appliedCommandCount; }
    public long requiredExportSequence() { return requiredExportSequence; }
    public long stateHash() { return stateHash; }

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
                && requiredExportSequence == value.requiredExportSequence
                && stateHash == value.stateHash
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
        hash = 31 * hash + Long.hashCode(requiredExportSequence);
        hash = 31 * hash + Long.hashCode(stateHash);
        hash = 31 * hash + data.hashCode();
        return hash;
    }

    @Override
    public String toString() {
        return "CoreResponse[status=" + status + ", commandStatus=" + commandStatus
                + ", resultCode=" + resultCode + ", routeVersion=" + routeVersion
                + ", committedCoreSequence=" + committedCoreSequence + ", appliedCommandCount=" + appliedCommandCount
                + ", requiredExportSequence=" + requiredExportSequence + ", stateHash=" + stateHash
                + ", data=" + data + "]";
    }

    int dataLength() {
        return data.length;
    }

    byte[] dataUnsafe() {
        return data;
    }
}
