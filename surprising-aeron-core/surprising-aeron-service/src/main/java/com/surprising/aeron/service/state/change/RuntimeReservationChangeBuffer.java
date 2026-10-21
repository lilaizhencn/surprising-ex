package com.surprising.aeron.service.state.change;

import com.surprising.aeron.service.state.order.ReservationRuntime;

/** Frozen reservation values belong to this Lane output buffer until its consumer clears it. */
public final class RuntimeReservationChangeBuffer extends RuntimeChangeBuffer<ReservationRuntime> {
    private ReservationRuntime[] copies = new ReservationRuntime[8];

    public void putLive(long key, ReservationRuntime value) { super.put(key, value); }

    public void freezeValues() {
        for (int slot = 0; slot < size; slot++) put(keyAt(slot), valueAt(slot));
    }

    public @Override int put(long key, ReservationRuntime value) {
        int slot = indexOf(key);
        if (slot < 0) slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        if (value != null) value = copies[slot] = value.copyIntoPublicationSlot(copies[slot]);
        return super.put(key, value);
    }

    public @Override void clear() {
        // Inline live changes may exceed the number of materialized publication slots.
        for (int slot = 0, count = Math.min(size, copies.length); slot < count; slot++)
            if (copies[slot] != null) copies[slot].clearPublicationSlot();
        super.clear();
    }
}
