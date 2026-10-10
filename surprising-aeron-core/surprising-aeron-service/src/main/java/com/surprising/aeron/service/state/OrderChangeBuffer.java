package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreOrderStatus;

/** Lane-owned order changes with allocation-free primitive after-images. */
final class OrderChangeBuffer extends RuntimeIndexedChangeBuffer<OrderRuntime, Void> {
    private long[] executed = new long[8];
    private long[] remaining = new long[8];
    private long[] cumulativeFee = new long[8];
    private long[] executedValueHigh = new long[8];
    private long[] executedValueLow = new long[8];
    private long[] createdAt = new long[8];
    private long[] updatedAt = new long[8];
    private long[] clusterPosition = new long[8];
    private long[] revision = new long[8];
    private CoreOrderStatus[] status = new CoreOrderStatus[8];

    void capturePublicationValues() {
        ensurePublicationCapacity(size());
        for (int index = 0; index < size(); index++) {
            OrderRuntime value = valueAt(index);
            if (value == null) continue;
            executed[index] = value.executedQuantitySteps();
            remaining[index] = value.remainingQuantitySteps();
            cumulativeFee[index] = value.cumulativeFeeUnits();
            executedValueHigh[index] = value.executedValueHigh();
            executedValueLow[index] = value.executedValueLow();
            createdAt[index] = value.createdAtEpochMillis();
            updatedAt[index] = value.updatedAtEpochMillis();
            clusterPosition[index] = value.clusterPosition();
            revision[index] = value.revision();
            status[index] = value.status();
        }
    }

    private void ensurePublicationCapacity(int required) {
        if (required <= executed.length) return;
        int capacity = executed.length;
        while (capacity < required) capacity = Math.multiplyExact(capacity, 2);
        executed = java.util.Arrays.copyOf(executed, capacity);
        remaining = java.util.Arrays.copyOf(remaining, capacity);
        cumulativeFee = java.util.Arrays.copyOf(cumulativeFee, capacity);
        executedValueHigh = java.util.Arrays.copyOf(executedValueHigh, capacity);
        executedValueLow = java.util.Arrays.copyOf(executedValueLow, capacity);
        createdAt = java.util.Arrays.copyOf(createdAt, capacity);
        updatedAt = java.util.Arrays.copyOf(updatedAt, capacity);
        clusterPosition = java.util.Arrays.copyOf(clusterPosition, capacity);
        revision = java.util.Arrays.copyOf(revision, capacity);
        status = java.util.Arrays.copyOf(status, capacity);
    }
}
