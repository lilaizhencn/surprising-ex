package com.surprising.aeron.service.state.change;

import com.surprising.aeron.service.state.order.OrderRuntime;

/** Command-local order after-images. Their objects move with the physical change buffer. */
public final class RuntimeOrderChangeBuffer extends RuntimeIndexedChangeBuffer<OrderRuntime, Void> {
    private OrderRuntime[] copies = new OrderRuntime[8];

    /** Only the Lane uses live references, until its final publication fence. */
    public void putLive(long key, OrderRuntime value) { super.put(key, value); }

    public void freezeValues() {
        for (int slot = 0; slot < size; slot++) put(keyAt(slot), valueAt(slot));
    }

    public @Override int put(long key, OrderRuntime value) {
        int slot = indexOf(key);
        if (slot < 0) slot = super.put(key, null);
        if (slot >= copies.length) copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        if (value != null) value = copies[slot] = value.copyIntoCommandSlot(copies[slot]);
        return super.put(key, value);
    }

    @Override RuntimeIndexedChangeBuffer<OrderRuntime, Void> emptyLike() {
        return new RuntimeOrderChangeBuffer();
    }

    public @Override void swapWith(RuntimeIndexedChangeBuffer<OrderRuntime, Void> other) {
        if (!(other instanceof RuntimeOrderChangeBuffer orders))
            throw new IllegalStateException("order after-images require an order change buffer");
        super.swapWith(other);
        OrderRuntime[] previous = copies; copies = orders.copies; orders.copies = previous;
    }

    public @Override void clear() {
        for (int slot = 0; slot < size; slot++) if (copies[slot] != null) copies[slot].clearCommandSlot();
        super.clear();
    }
}
