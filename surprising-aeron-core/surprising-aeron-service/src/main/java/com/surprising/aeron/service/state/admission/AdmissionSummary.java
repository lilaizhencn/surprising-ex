package com.surprising.aeron.service.state.admission;

/** Reusable owner-confined result of an active-order admission query. */
public final class AdmissionSummary {
    private long pendingQuantity;
    private long reduceOnlyQuantity;
    private int marginModeCount;

    public long pendingQuantity() {
        return pendingQuantity;
    }

    public long reduceOnlyQuantity() {
        return reduceOnlyQuantity;
    }

    public int marginModeCount() {
        return marginModeCount;
    }

    public AdmissionSummary set(long pendingQuantity, long reduceOnlyQuantity, int marginModeCount) {
        this.pendingQuantity = pendingQuantity;
        this.reduceOnlyQuantity = reduceOnlyQuantity;
        this.marginModeCount = marginModeCount;
        return this;
    }
}
