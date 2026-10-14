package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.account.UserRuntime;

/** User before/after values belong to the existing command buffer, never to the live Lane table. */
final class RuntimeUserChangeBuffer extends RuntimeIndexedChangeBuffer<UserRuntime, UserRuntime> {
    private UserRuntime[] copies = new UserRuntime[8];

    @Override int put(long key, UserRuntime value) {
        int slot = indexOf(key);
        if (slot < 0) slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        if (value != null) value = copies[slot] = value.copyIntoCommandSlot(copies[slot]);
        return super.put(key, value);
    }

    /** Preserve the original authority identity as well as its independent before-value. */
    void captureBefore(long key, UserRuntime authority) {
        if (containsKey(key)) return;
        put(key, authority);
        putPrepared(key, authority);
    }

    @Override void clear() {
        for (int slot = 0; slot < size; slot++) if (copies[slot] != null) copies[slot].clearCommandSlot();
        super.clear();
    }
}
