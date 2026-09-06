package com.surprising.aeron.protocol;

/** Authoritative instrument gate. A task retains ownership until explicitly released. */
public record CoreInstrumentMaintenance(long taskId, Mode mode, long settlementPriceTicks) {
    public enum Mode { TRADING, REDUCE_ONLY, HALTED, SETTLEMENT, CLOSED }
    public static final CoreInstrumentMaintenance TRADING = new CoreInstrumentMaintenance(0, Mode.TRADING, 0);

    public CoreInstrumentMaintenance {
        if (mode == null || taskId < 0 || settlementPriceTicks < 0
                || (mode != Mode.TRADING && taskId == 0)
                || (mode == Mode.TRADING && (taskId != 0 || settlementPriceTicks != 0))
                || ((mode == Mode.SETTLEMENT || mode == Mode.CLOSED) != (settlementPriceTicks > 0))) {
            throw new IllegalArgumentException("invalid instrument maintenance state");
        }
    }
}
