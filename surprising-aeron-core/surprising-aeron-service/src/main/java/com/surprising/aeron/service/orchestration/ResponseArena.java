package com.surprising.aeron.service.orchestration;

/**
 * Owner-confined response storage for the common command-result path.
 *
 * <p>Slots are returned only when the result ledger evicts the corresponding retained result.
 * A slot is therefore never reused while it is part of the idempotency or snapshot state. Large
 * responses use an ordinary exact-sized array and do not force the arena to grow.</p>
 */
final class ResponseArena {
    static final int DEFAULT_SLOT_COUNT = TradingCoreRuntime.MAX_IDEMPOTENCY_RESULTS;
    static final int DEFAULT_SLOT_BYTES = 64 * 1024;

    private final byte[][] slots;
    private final boolean[] leased;
    private final int slotBytes;

    ResponseArena() {
        this(DEFAULT_SLOT_COUNT, DEFAULT_SLOT_BYTES);
    }

    ResponseArena(int slotCount, int slotBytes) {
        if (slotCount <= 0 || slotBytes <= 0) throw new IllegalArgumentException("invalid response arena");
        this.slots = new byte[slotCount][];
        this.leased = new boolean[slotCount];
        this.slotBytes = slotBytes;
    }

    /**
     * Acquires stable storage. The returned array may be larger than the logical response; callers
     * must carry the explicit length to the response and ledger descriptors.
     */
    byte[] acquire(int requiredLength) {
        if (requiredLength < 0) throw new IllegalArgumentException("negative response length");
        if (requiredLength == 0) return TradingCoreRuntime.EMPTY_RESPONSE_DATA;
        if (requiredLength > slotBytes) return new byte[requiredLength];
        for (int index = 0; index < slots.length; index++) {
            if (leased[index]) continue;
            byte[] slot = slots[index];
            if (slot == null) slots[index] = slot = new byte[slotBytes];
            leased[index] = true;
            return slot;
        }
        // A full ledger can still receive a replacement or a large fan-out response. Preserve
        // correctness by falling back to exact storage instead of blocking the Owner.
        return new byte[requiredLength];
    }

    /** Returns an arena slot by identity; non-arena arrays are intentionally ignored. */
    void release(byte[] storage) {
        if (storage == null || storage.length != slotBytes) return;
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == storage) {
                leased[index] = false;
                return;
            }
        }
    }

    void clear() {
        java.util.Arrays.fill(leased, false);
    }
}
