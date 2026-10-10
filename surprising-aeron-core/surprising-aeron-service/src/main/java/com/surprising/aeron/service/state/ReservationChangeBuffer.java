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

    private void ensurePublicationCapacity(int required) {
        if (required <= released.length) return;
        int capacity = released.length;
        while (capacity < required) capacity = Math.multiplyExact(capacity, 2);
        released = java.util.Arrays.copyOf(released, capacity);
        consumed = java.util.Arrays.copyOf(consumed, capacity);
    }
}
