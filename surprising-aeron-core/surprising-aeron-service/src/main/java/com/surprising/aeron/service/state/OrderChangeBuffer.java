package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreOrderStatus;

/** Lane-owned order changes with allocation-free primitive after-images. */
final class OrderChangeBuffer extends RuntimeIndexedChangeBuffer<OrderRuntime, Void> {
    private long[] executed = new long[8];
    private long[] remaining = new long[8];
    private long[] cumulativeFee = new long[8];
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
            createdAt[index] = value.createdAtEpochMillis();
            updatedAt[index] = value.updatedAtEpochMillis();
            clusterPosition[index] = value.clusterPosition();
            revision[index] = value.revision();
            status[index] = value.status();
        }
    }

    OrderRuntime applyPublished(int index, LanePublishedMap<OrderRuntime> target,
                                OwnerSettlementMergeEvent timing) {
        long key = keyAt(index);
        OrderRuntime source = valueAt(index);
        if (source == null) {
            target.removePublished(key, timing, false);
            return null;
        }
        OrderRuntime ownerValue = target.get(key);
        if (ownerValue == null) {
            // The ordered Lane completion is the publication fence. Keep the Lane's canonical
            // object instead of allocating and maintaining an Owner mirror of the same order.
            ownerValue = source;
            target.put(key, ownerValue);
        } else if (ownerValue != source) {
            // Recovery/control paths can still start from an independent published value.
            ownerValue.applyPublishedStateInPlace(source, executed[index], remaining[index],
                    cumulativeFee[index], createdAt[index], updatedAt[index], clusterPosition[index],
                    status[index], revision[index]);
        }
        setValueAt(index, ownerValue);
        return ownerValue;
    }

    private void ensurePublicationCapacity(int required) {
        if (required <= executed.length) return;
        int capacity = executed.length;
        while (capacity < required) capacity = Math.multiplyExact(capacity, 2);
        executed = java.util.Arrays.copyOf(executed, capacity);
        remaining = java.util.Arrays.copyOf(remaining, capacity);
        cumulativeFee = java.util.Arrays.copyOf(cumulativeFee, capacity);
        createdAt = java.util.Arrays.copyOf(createdAt, capacity);
        updatedAt = java.util.Arrays.copyOf(updatedAt, capacity);
        clusterPosition = java.util.Arrays.copyOf(clusterPosition, capacity);
        revision = java.util.Arrays.copyOf(revision, capacity);
        status = java.util.Arrays.copyOf(status, capacity);
    }
}
