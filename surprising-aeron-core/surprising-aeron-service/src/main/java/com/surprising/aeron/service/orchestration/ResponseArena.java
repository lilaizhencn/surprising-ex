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
    private final Slot[] slotDescriptors;
    private final int slotBytes;
    private static final Slot EMPTY_SLOT = new Slot(TradingCoreRuntime.EMPTY_RESPONSE_DATA, false);

    /**
     * A leased response is a ring slot, not an ephemeral payload.  The byte array is allocated
     * once for the slot and remains owned by the result ledger until eviction.  Lane encoders
     * use the same descriptor, so the Owner never has to copy a Lane response into another
     * array just to retain it for idempotency.
     */
    static final class Slot {
        byte[] storage;
        final boolean arenaBacked;
        int length;

        private Slot(byte[] storage, boolean arenaBacked) {
            this.storage = storage;
            this.arenaBacked = arenaBacked;
        }
    }

    ResponseArena() {
        this(DEFAULT_SLOT_COUNT, DEFAULT_SLOT_BYTES);
    }

    ResponseArena(int slotCount, int slotBytes) {
        if (slotCount <= 0 || slotBytes <= 0) throw new IllegalArgumentException("invalid response arena");
        this.slots = new byte[slotCount][];
        this.leased = new boolean[slotCount];
        this.slotDescriptors = new Slot[slotCount];
        for (int index = 0; index < slotCount; index++) slotDescriptors[index] = new Slot(null, true);
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
            if (slot == null || slot.length < requiredLength) slots[index] = slot = new byte[slotBytes];
            leased[index] = true;
            return slot;
        }
        // A full ledger can still receive a replacement or a large fan-out response. Preserve
        // correctness by falling back to exact storage instead of blocking the Owner.
        return new byte[requiredLength];
    }

    Slot acquireSlot(int requiredLength) {
        if (requiredLength < 0) throw new IllegalArgumentException("negative response length");
        if (requiredLength == 0) return EMPTY_SLOT;
        for (int index = 0; index < slots.length; index++) {
            if (leased[index]) continue;
            byte[] slot = slots[index];
            if (requiredLength > slotBytes) break;
            if (slot == null || slot.length < requiredLength) slots[index] = slot = new byte[slotBytes];
            leased[index] = true;
            Slot descriptor = slotDescriptors[index];
            descriptor.storage = slot;
            descriptor.length = 0;
            return descriptor;
        }
        // Oversized and temporarily exhausted responses retain the old exact-size fallback.
        // The normal single-order path never reaches this branch after the ring is warm.
        return new Slot(new byte[requiredLength], false);
    }

    /**
     * Acquires a stable ring descriptor whose backing length is exactly the requested payload
     * length.  Batch protocol callers expose the backing array to legacy decoders, so the
     * descriptor keeps exact wire boundaries while still reusing the ring slot across batches.
     */
    Slot acquireExactSlot(int requiredLength) {
        if (requiredLength < 0) throw new IllegalArgumentException("negative response length");
        if (requiredLength == 0) return EMPTY_SLOT;
        for (int index = 0; index < slots.length; index++) {
            if (leased[index]) continue;
            byte[] slot = slots[index];
            if (slot == null || slot.length != requiredLength) slots[index] = slot = new byte[requiredLength];
            leased[index] = true;
            Slot descriptor = slotDescriptors[index];
            descriptor.storage = slot;
            descriptor.length = 0;
            return descriptor;
        }
        return new Slot(new byte[requiredLength], false);
    }

    void release(Slot slot) {
        if (slot == null || !slot.arenaBacked) return;
        release(slot.storage);
    }

    /** Returns an arena slot by identity; non-arena arrays are intentionally ignored. */
    void release(byte[] storage) {
        if (storage == null) return;
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
