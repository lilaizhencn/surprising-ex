package com.surprising.aeron.service.state;

/**
 * Binary compatibility marker for old reflection and snapshot tooling.
 * New code uses {@link com.surprising.aeron.service.lane.SettlementLaneWorker}.
 */
@Deprecated
public final class SettlementLaneWorker {
    private SettlementLaneWorker() { }
    @Deprecated
    public interface Command extends com.surprising.aeron.service.lane.SettlementLaneWorker.Command { }
}
