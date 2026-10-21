package com.surprising.aeron.service.orchestration.result;

import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.aeron.service.orchestration.pipeline.ClusterCommandWindow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Shared fixed response storage for the common command-result path.
 *
 * <p>Slots are returned only after both the result ledger and transport release them. Lease
 * metadata is shared by Lane encoders and the Owner, so allocation and reference changes are
 * claimed with lock-free atomic CAS while payload encoding remains outside the claim path. Large responses use an
 * ordinary exact-sized array and do not force the arena to grow.</p>
 */
public final class ResponseArena {
    /**
     * A response can be referenced simultaneously by the Owner command window and the
     * idempotency ledger. The formal 256-entry window therefore needs its own slots in addition
     * to the 128 retained ledger results; otherwise ledger eviction can recycle a payload that is
     * still waiting in the command FIFO to be sent.
     */
    static final int DEFAULT_SLOT_COUNT = 512;
    static final int DEFAULT_SLOT_BYTES = 64 * 1024;

    private static final int TRANSPORT_SHIFT = 32;
    private static final long TRANSPORT_UNIT = 1L << TRANSPORT_SHIFT;
    private static final long LEDGER_MASK = 0xFFFFFFFFL;

    private final byte[][] slots;
    private final AtomicLongArray slotStates;
    private final Slot[] slotDescriptors;
    private final int slotBytes;
    private final int slotCount;
    private final boolean isPowerOfTwo;
    private final int slotMask;
    private final AtomicInteger allocateCursor = new AtomicInteger();

    // Fast O(1) identity lookup: storage byte[] -> slotIndex
    private final int identityMask;
    private final byte[][] identityKeys;
    private final int[] identityTable;

    private static final Slot EMPTY_SLOT = new Slot(TradingCoreRuntime.EMPTY_RESPONSE_DATA, false, -1);

    /**
     * A leased response is a ring slot, not an ephemeral payload.  The byte array is allocated
     * once for the slot and remains owned by the result ledger until eviction.  Lane encoders
     * use the same descriptor, so the Owner never has to copy a Lane response into another
     * array just to retain it for idempotency.
     */
    public static final class Slot {
        byte[] storage;
        final boolean arenaBacked;
        final int slotIndex;
        int length;
        /** Same lease as storage; only the claiming encoder touches its position. */
        private java.nio.ByteBuffer writer;

        private Slot(byte[] storage, boolean arenaBacked, int slotIndex) {
            this.storage = storage;
            this.arenaBacked = arenaBacked;
            this.slotIndex = slotIndex;
        }

        public java.nio.ByteBuffer writer() {
            if (writer == null || writer.array() != storage) writer = java.nio.ByteBuffer.wrap(storage);
            return writer.clear();
        }

    /** Core 内部业务协作入口；状态仍由原所属线程或有效读取屏障持有。 */
    public void length(int value) { this.length = value; }

    public byte[] storage() { return storage; }
}

    public ResponseArena() {
        this(defaultSlotCount(), DEFAULT_SLOT_BYTES);
    }

    private static int defaultSlotCount() {
        int configuredWindow = Integer.getInteger(
                "surprising.aeron.owner-command-window", ClusterCommandWindow.DEFAULT_CAPACITY);
        int required = Math.max(DEFAULT_SLOT_COUNT, Math.max(64, configuredWindow) + 256);
        int slots = DEFAULT_SLOT_COUNT;
        while (slots < required) slots <<= 1;
        return slots;
    }

    public ResponseArena(int slotCount, int slotBytes) {
        if (slotCount <= 0 || slotBytes <= 0) throw new IllegalArgumentException("invalid response arena");
        this.slotCount = slotCount;
        this.slotBytes = slotBytes;
        this.isPowerOfTwo = (slotCount & (slotCount - 1)) == 0;
        this.slotMask = slotCount - 1;
        this.slots = new byte[slotCount][];
        this.slotStates = new AtomicLongArray(slotCount);
        this.slotDescriptors = new Slot[slotCount];

        int capacity = Integer.highestOneBit(slotCount) << 2;
        if (capacity < 16) capacity = 16;
        this.identityMask = capacity - 1;
        this.identityKeys = new byte[capacity][];
        this.identityTable = new int[capacity];
        java.util.Arrays.fill(this.identityTable, -1);

        for (int index = 0; index < slotCount; index++) {
            byte[] storage = new byte[slotBytes];
            this.slots[index] = storage;
            this.slotDescriptors[index] = new Slot(storage, true, index);

            int h = System.identityHashCode(storage);
            int probe = h & identityMask;
            while (identityKeys[probe] != null) {
                probe = (probe + 1) & identityMask;
            }
            identityKeys[probe] = storage;
            identityTable[probe] = index;
        }
    }

    /**
     * Acquires stable storage. The returned array may be larger than the logical response; callers
     * must carry the explicit length to the response and ledger descriptors.
     */
    byte[] acquire(int requiredLength) {
        if (requiredLength == 0) return TradingCoreRuntime.EMPTY_RESPONSE_DATA;
        int index = claimPooledSlot(requiredLength);
        return index < 0 ? new byte[requiredLength] : slots[index];
    }

    /**
     * Common responses lease a fixed slot. Large responses and responses produced while all
     * slots are retained own dedicated storage: a completed trade must still have a response
     * while transport or idempotency retention delays the return of pooled leases.
     */
    public Slot acquireSlot(int requiredLength) {
        if (requiredLength == 0) return EMPTY_SLOT;
        int index = claimPooledSlot(requiredLength);
        if (index < 0) return new Slot(new byte[requiredLength], false, -1);
        Slot descriptor = slotDescriptors[index];
        descriptor.length = 0;
        return descriptor;
    }

    /** Both APIs share the claim; byte-array callers never allocate a discarded Slot descriptor. */
    private int claimPooledSlot(int requiredLength) {
        if (requiredLength < 0) throw new IllegalArgumentException("negative response length");
        if (requiredLength > slotBytes) return -1;
        int start = allocateCursor.getAndIncrement() & 0x7FFFFFFF;
        boolean powerOfTwo = this.isPowerOfTwo;
        int mask = this.slotMask;
        int count = this.slotCount;
        for (int i = 0; i < count; i++) {
            int index = powerOfTwo ? ((start + i) & mask) : ((start + i) % count);
            if (slotStates.get(index) == 0L && slotStates.compareAndSet(index, 0L, TRANSPORT_UNIT)) {
                return index;
            }
        }
        return -1;
    }

    private int findSlotIndex(byte[] storage) {
        if (storage == null) return -1;
        int mask = identityMask;
        int h = System.identityHashCode(storage);
        int probe = h & mask;
        while (true) {
            byte[] key = identityKeys[probe];
            if (key == storage) return identityTable[probe];
            if (key == null) return -1;
            probe = (probe + 1) & mask;
        }
    }

    public void release(Slot slot) {
        if (slot == null || !slot.arenaBacked || slot.slotIndex < 0) return;
        releaseSlot(slot.slotIndex);
    }

    /** Returns an arena slot by identity; non-arena arrays are intentionally ignored. */
    public void release(byte[] storage) {
        if (storage == null) return;
        int index = findSlotIndex(storage);
        if (index < 0) return;
        releaseSlot(index);
    }

    private void releaseSlot(int index) {
        while (true) {
            long state = slotStates.get(index);
            long transport = state >>> TRANSPORT_SHIFT;
            if (state == 0L || transport <= 0) {
                throw new IllegalStateException("response arena transport slot released more than once");
            }
            long next = state - TRANSPORT_UNIT;
            if (slotStates.compareAndSet(index, state, next)) {
                return;
            }
        }
    }

    /** Retains a response for another transport/output owner. */
    public void retain(byte[] storage) {
        if (storage == null) return;
        int index = findSlotIndex(storage);
        if (index < 0) return;
        retainSlot(index);
    }

    private void retainSlot(int index) {
        while (true) {
            long state = slotStates.get(index);
            if (state == 0L) {
                throw new IllegalStateException("response arena slot is not leased");
            }
            if (slotStates.compareAndSet(index, state, state + TRANSPORT_UNIT)) {
                return;
            }
        }
    }

    /** Retains a response for the idempotency ledger independently of transport lifetime. */
    void retainLedger(byte[] storage) {
        if (storage == null) return;
        int index = findSlotIndex(storage);
        if (index < 0) return;
        retainLedgerSlot(index);
    }

    private void retainLedgerSlot(int index) {
        while (true) {
            long state = slotStates.get(index);
            if (state == 0L) {
                throw new IllegalStateException("response arena slot is not leased");
            }
            if (slotStates.compareAndSet(index, state, state + 1L)) {
                return;
            }
        }
    }

    /** Releases the ledger's retained reference after eviction or replacement. */
    void releaseLedger(byte[] storage) {
        if (storage == null) return;
        int index = findSlotIndex(storage);
        if (index < 0) return;
        releaseLedgerSlot(index);
    }

    private void releaseLedgerSlot(int index) {
        while (true) {
            long state = slotStates.get(index);
            int ledger = (int) (state & LEDGER_MASK);
            if (state == 0L || ledger <= 0) {
                throw new IllegalStateException("response arena ledger slot released more than once");
            }
            long next = state - 1L;
            if (slotStates.compareAndSet(index, state, next)) {
                return;
            }
        }
    }

    void clear() {
        for (int index = 0; index < slotCount; index++) {
            slotStates.set(index, 0L);
        }
    }
}
