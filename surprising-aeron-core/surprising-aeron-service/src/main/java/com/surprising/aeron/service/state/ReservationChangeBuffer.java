package com.surprising.aeron.service.state;

/** Lane-owned reservation changes with allocation-free primitive after-images. */
final class ReservationChangeBuffer extends RuntimeChangeBuffer<ReservationRuntime> {
    private long[] released = new long[8];
    private long[] consumed = new long[8];

    void capturePublicationValues() {
        ensurePublicationCapacity(size());
        for (int index = 0; index < size(); index++) {
            ReservationRuntime value = valueAt(index);
            if (value == null) continue;
            released[index] = value.releasedUnits();
            consumed[index] = value.consumedUnits();
        }
    }

    ReservationRuntime applyPublished(int index, LanePublishedMap<ReservationRuntime> target) {
        long key = keyAt(index);
        ReservationRuntime source = valueAt(index);
        if (source == null) {
            target.remove(key);
            return null;
        }
        ReservationRuntime ownerValue = target.get(key);
        if (ownerValue == null) {
            ownerValue = source.publishedCopy(released[index], consumed[index]);
            target.put(key, ownerValue);
        } else {
            ownerValue.applyPublishedStateInPlace(source, released[index], consumed[index]);
        }
        setValueAt(index, ownerValue);
        return ownerValue;
    }

    private void ensurePublicationCapacity(int required) {
        if (required <= released.length) return;
        int capacity = released.length;
        while (capacity < required) capacity = Math.multiplyExact(capacity, 2);
        released = java.util.Arrays.copyOf(released, capacity);
        consumed = java.util.Arrays.copyOf(consumed, capacity);
    }
}
