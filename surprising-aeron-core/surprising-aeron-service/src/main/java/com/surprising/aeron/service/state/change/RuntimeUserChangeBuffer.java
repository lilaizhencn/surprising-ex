package com.surprising.aeron.service.state.change;

/** User before/after values belong to the existing command buffer, never to the live Lane table. */
public final class RuntimeUserChangeBuffer extends RuntimeIndexedChangeBuffer<com.surprising.aeron.service.state.account.UserRuntime, com.surprising.aeron.service.state.account.UserRuntime> {
    private com.surprising.aeron.service.state.account.UserRuntime[] copies = new com.surprising.aeron.service.state.account.UserRuntime[8];

    public @Override int put(long key, com.surprising.aeron.service.state.account.UserRuntime value) {
        int slot = indexOf(key);
        if (slot < 0) slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        if (value != null) value = copies[slot] = value.copyIntoCommandSlot(copies[slot]);
        return super.put(key, value);
    }

    /** Preserve the original authority identity as well as its independent before-value. */
    public void captureBefore(long key, com.surprising.aeron.service.state.account.UserRuntime authority) {
        if (containsKey(key)) return;
        put(key, authority);
        putPrepared(key, authority);
    }

    public @Override void clear() {
        for (int slot = 0; slot < size; slot++) if (copies[slot] != null) copies[slot].clearCommandSlot();
        super.clear();
    }
}
