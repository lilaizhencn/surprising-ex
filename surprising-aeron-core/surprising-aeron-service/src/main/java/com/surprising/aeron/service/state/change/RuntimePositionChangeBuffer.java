package com.surprising.aeron.service.state.change;

import com.surprising.aeron.service.state.position.PositionRuntime;
import com.surprising.aeron.service.state.position.RuntimePositionIndexValue;

/** Position after-images move with their physical buffer until its consumer releases them. */
public final class RuntimePositionChangeBuffer
        extends RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue> {
    private PositionRuntime[] copies = new PositionRuntime[8];

    public @Override int put(long key, PositionRuntime value) {
        int slot = indexOf(key);
        if (slot < 0) slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        if (value != null) value = copies[slot] = value.copyIntoPublicationSlot(copies[slot]);
        return super.put(key, value);
    }

    @Override RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue> emptyLike() {
        return new RuntimePositionChangeBuffer();
    }

    @Override void swapWith(RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue> other) {
        if (!(other instanceof RuntimePositionChangeBuffer positions))
            throw new IllegalStateException("position after-images require a position change buffer");
        super.swapWith(other);
        PositionRuntime[] previous = copies;
        copies = positions.copies;
        positions.copies = previous;
    }

    public @Override void clear() {
        for (int slot = 0; slot < size; slot++)
            if (copies[slot] != null) copies[slot].clearPublicationSlot();
        super.clear();
    }
}
