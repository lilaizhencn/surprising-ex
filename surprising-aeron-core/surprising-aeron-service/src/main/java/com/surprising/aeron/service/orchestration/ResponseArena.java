package com.surprising.aeron.service.orchestration;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Shared fixed response storage for the common command-result path.
 *
 * <p>Slots are returned only after both the result ledger and transport release them. Lease
 * metadata is shared by Lane encoders and the Owner, so allocation and reference changes are
 * claimed with CAS while payload encoding remains outside the claim path. Large responses use an
 * ordinary exact-sized array and do not force the arena to grow.</p>
 */
final class ResponseArena {
    /**
     * A response can be referenced simultaneously by the Owner command window and the
     * idempotency ledger. The formal 256-entry window therefore needs its own slots in addition
     * to the 128 retained ledger results; otherwise ledger eviction can recycle a payload that is
     * still waiting in the command FIFO to be sent.
     */
    static final int DEFAULT_SLOT_COUNT = 512;
    static final int DEFAULT_SLOT_BYTES = 64 * 1024;

    private final byte[][] slots;
    private final AtomicIntegerArray leaseStates;
    private final int[] transportReferences;
    private final int[] ledgerReferences;
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
        this.leaseStates = new AtomicIntegerArray(slotCount);
        this.transportReferences = new int[slotCount];
        this.ledgerReferences = new int[slotCount];
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
            if (!leaseStates.compareAndSet(index, 0, 1)) continue;
            byte[] slot = slots[index];
            if (slot == null || slot.length < requiredLength) slots[index] = slot = new byte[slotBytes];
            transportReferences[index] = 1;
            ledgerReferences[index] = 0;
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
            if (requiredLength > slotBytes) break;
            if (!leaseStates.compareAndSet(index, 0, 1)) continue;
            byte[] slot = slots[index];
            if (slot == null || slot.length < requiredLength) slots[index] = slot = new byte[slotBytes];
            transportReferences[index] = 1;
            ledgerReferences[index] = 0;
            Slot descriptor = slotDescriptors[index];
            descriptor.storage = slot;
            descriptor.length = 0;
            return descriptor;
        }
        // Oversized and temporarily exhausted responses retain the old exact-size fallback.
        // The normal single-order path never reaches this branch after the ring is warm.
        return new Slot(new byte[requiredLength], false);
    }

    /** Acquires a stable fixed-size ring descriptor; callers carry the logical payload length. */
    Slot acquireExactSlot(int requiredLength) {
        if (requiredLength < 0) throw new IllegalArgumentException("negative response length");
        if (requiredLength == 0) return EMPTY_SLOT;
        if (requiredLength > slotBytes) return new Slot(new byte[requiredLength], false);
        for (int index = 0; index < slots.length; index++) {
            if (!leaseStates.compareAndSet(index, 0, 1)) continue;
            byte[] slot = slots[index];
            if (slot == null || slot.length != slotBytes) slots[index] = slot = new byte[slotBytes];
            transportReferences[index] = 1;
            ledgerReferences[index] = 0;
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
                if (leaseStates.get(index) == 0 || transportReferences[index] <= 0) {
                    throw new IllegalStateException("response arena transport slot released more than once");
                }
                transportReferences[index]--;
                if (transportReferences[index] == 0 && ledgerReferences[index] == 0) {
                    leaseStates.compareAndSet(index, 1, 0);
                }
                return;
            }
        }
    }

    /** Retains a response for another transport/output owner. */
    void retain(byte[] storage) {
        if (storage == null) return;
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == storage) {
                if (leaseStates.get(index) == 0) {
                    throw new IllegalStateException("response arena slot is not leased");
                }
                transportReferences[index]++;
                return;
            }
        }
    }

    /** Retains a response for the idempotency ledger independently of transport lifetime. */
    void retainLedger(byte[] storage) {
        if (storage == null) return;
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == storage) {
                if (leaseStates.get(index) == 0) throw new IllegalStateException("response arena slot is not leased");
                ledgerReferences[index]++;
                return;
            }
        }
    }

    /** Releases the ledger's retained reference after eviction or replacement. */
    void releaseLedger(byte[] storage) {
        if (storage == null) return;
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == storage) {
                if (leaseStates.get(index) == 0 || ledgerReferences[index] <= 0) {
                    throw new IllegalStateException("response arena ledger slot released more than once");
                }
                ledgerReferences[index]--;
                if (transportReferences[index] == 0 && ledgerReferences[index] == 0) {
                    leaseStates.compareAndSet(index, 1, 0);
                }
                return;
            }
        }
    }

    void clear() {
        for (int index = 0; index < leaseStates.length(); index++) leaseStates.set(index, 0);
        java.util.Arrays.fill(transportReferences, 0);
        java.util.Arrays.fill(ledgerReferences, 0);
    }
}
