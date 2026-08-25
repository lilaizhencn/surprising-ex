package com.surprising.aeron.protocol;

public record CoreResponse(
        ResponseStatus status,
        ResponseStatus commandStatus,
        CoreResultCode resultCode,
        int routeVersion,
        long committedCoreSequence,
        long appliedCommandCount,
        long requiredExportSequence,
        long stateHash,
        byte[] data) {

    public CoreResponse {
        if (status == null || commandStatus == null || resultCode == null
                || routeVersion != CoreRoute.DEFAULT.version() || committedCoreSequence < 0
                || committedCoreSequence > appliedCommandCount || appliedCommandCount < 0
                || requiredExportSequence < 0) {
            throw new IllegalArgumentException("invalid core response");
        }
        data = data == null ? new byte[0] : data.clone();
    }

    public CoreResponse(ResponseStatus status, ResponseStatus commandStatus, CoreResultCode resultCode,
                        long appliedCommandCount, long stateHash, byte[] data) {
        this(status, commandStatus, resultCode, CoreRoute.DEFAULT.version(), appliedCommandCount,
                appliedCommandCount, 0, stateHash, data);
    }

    public CoreResponse(ResponseStatus status, long appliedCommandCount, long stateHash) {
        this(status, status, CoreResultCode.NONE, CoreRoute.DEFAULT.version(), appliedCommandCount,
                appliedCommandCount, 0, stateHash, new byte[0]);
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
                appliedCommandCount, 0, stateHash, new byte[0]);
    }

    public CoreResponse(
            ResponseStatus status,
            ResponseStatus commandStatus,
            CoreResultCode resultCode,
            long appliedCommandCount,
            long stateHash) {
        this(status, commandStatus, resultCode, CoreRoute.DEFAULT.version(), appliedCommandCount,
                appliedCommandCount, 0, stateHash, new byte[0]);
    }

    public CoreResponse(ResponseStatus status, ResponseStatus commandStatus, CoreResultCode resultCode,
                        long appliedCommandCount, long requiredExportSequence, long stateHash, byte[] data) {
        this(status, commandStatus, resultCode, CoreRoute.DEFAULT.version(), appliedCommandCount,
                appliedCommandCount, requiredExportSequence, stateHash, data);
    }

    public CoreResponse withCommittedCoreSequence(long sequence) {
        return new CoreResponse(status, commandStatus, resultCode, routeVersion, sequence,
                appliedCommandCount, requiredExportSequence, stateHash, data);
    }

    @Override
    public byte[] data() {
        return data.clone();
    }
}
