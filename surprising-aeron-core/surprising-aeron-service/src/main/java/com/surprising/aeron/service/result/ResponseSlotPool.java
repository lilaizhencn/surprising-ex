package com.surprising.aeron.service.result;

import com.surprising.aeron.service.core.TradingCoreRuntime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import lombok.Getter;
import lombok.experimental.Accessors;

/** 响应字节的固定槽池：小响应可借预分配数组，超大响应或槽全被保留时使用独立精确长度数组。Lane 编码器、Owner 与出口通过原子计数共同持有槽。 */
public final class ResponseSlotPool {
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
    /** 并发借槽的轮转起点计数器。 */
    private final AtomicInteger allocateCursor = new AtomicInteger();

    private final int identityMask;
    private final byte[][] identityKeys;
    private final int[] identityTable;

    private static final Slot EMPTY_SLOT = new Slot(TradingCoreRuntime.EMPTY_RESPONSE_DATA, false, -1);

    /** 一次响应编码可使用的数组描述符，保存池归属和槽号。 */
    public static final class Slot {
        /** 借出底层数组，必须遵守描述符借用期并另带有效长度。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        byte[] storage;
        final boolean 槽池Backed;
        final int slotIndex;
        private java.nio.ByteBuffer writer;

        private Slot(byte[] storage, boolean 槽池Backed, int slotIndex) {
            this.storage = storage;
            this.槽池Backed = 槽池Backed;
            this.slotIndex = slotIndex;
        }

        /** 复用底层数组对应的 ByteBuffer 并清读写位置。 */
        public java.nio.ByteBuffer writer() {
            if (writer == null || writer.array() != storage) writer = java.nio.ByteBuffer.wrap(storage);
            return writer.clear();
        }

}

    /** 按 Owner 命令窗口配置计算固定槽数，使用默认每槽六十四 KiB 容量建池。 */
    public ResponseSlotPool() {
        this(com.surprising.aeron.config.CoreRuntimeConfiguration.standalone());
    }

    /** 读取 JVM 命令窗口配置，按至少六十四的窗口预算另留二百五十六槽，再取至少五百一十二的二次幂容量。 */
    public ResponseSlotPool(com.surprising.aeron.config.CoreRuntimeConfiguration configuration) {
        this(defaultSlotCount(configuration.ownerCommandWindow()), DEFAULT_SLOT_BYTES);
    }

    private static int defaultSlotCount(int configuredWindow) {
        int required = Math.max(DEFAULT_SLOT_COUNT, Math.max(64, configuredWindow) + 256);
        int slots = DEFAULT_SLOT_COUNT;
        while (slots < required) slots <<= 1;
        return slots;
    }

    /** 要求槽数和每槽字节数为正，预分配固定数组、描述符与原子状态，并建立按数组对象身份查槽的探测表。 */
    public ResponseSlotPool(int slotCount, int slotBytes) {
        if (slotCount <= 0 || slotBytes <= 0) throw new IllegalArgumentException("invalid response slot pool");
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

    /** 零长返回共享空数组。其余尝试原子借固定槽，不能借时创建精确长度独立数组。池数组可能大于有效响应，调用方必须另带有效长度。 */
    byte[] acquire(int requiredLength) {
        if (requiredLength == 0) return TradingCoreRuntime.EMPTY_RESPONSE_DATA;
        int index = claimPooledSlot(requiredLength);
        return index < 0 ? new byte[requiredLength] : slots[index];
    }

    /** 零长返回共享空描述符。 */
    public Slot acquireSlot(int requiredLength) {
        if (requiredLength == 0) return EMPTY_SLOT;
        int index = claimPooledSlot(requiredLength);
        if (index < 0) return new Slot(new byte[requiredLength], false, -1);
        return slotDescriptors[index];
    }

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

    /** 只对本池描述符归还一份发送引用。 */
    public void release(Slot slot) {
        if (slot == null || !slot.槽池Backed || slot.slotIndex < 0) return;
        releaseSlot(slot.slotIndex);
    }

    /** 先按数组身份查槽，只归还本池数组的一份发送引用。 */
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
                throw new IllegalStateException("response slot pool transport slot released more than once");
            }
            long next = state - TRANSPORT_UNIT;
            if (slotStates.compareAndSet(index, state, next)) {
                return;
            }
        }
    }

    /** 数组属于本池时增加一份发送或输出持有引用。 */
    public void retain(byte[] storage) {
        if (storage == null) return;
        int index = findSlotIndex(storage);
        if (index < 0) return;
        retainSlot(index);
    }

    /** 已有任意借用引用时用 CAS 增加一份发送引用，完全空闲槽不能凭旧数组引用重新续借。 */
    private void retainSlot(int index) {
        while (true) {
            long state = slotStates.get(index);
            if (state == 0L) {
                throw new IllegalStateException("response slot pool slot is not leased");
            }
            if (slotStates.compareAndSet(index, state, state + TRANSPORT_UNIT)) {
                return;
            }
        }
    }

    /** 本池数组新增一份独立账本引用，避免首次发送结束后字节提前被覆盖。 */
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
                throw new IllegalStateException("response slot pool slot is not leased");
            }
            if (slotStates.compareAndSet(index, state, state + 1L)) {
                return;
            }
        }
    }

    /** 淘汰或替换结果后归还本池数组的一份账本引用。 */
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
                throw new IllegalStateException("response slot pool ledger slot released more than once");
            }
            long next = state - 1L;
            if (slotStates.compareAndSet(index, state, next)) {
                return;
            }
        }
    }

    /** 在生命周期清理边界强制把全部槽计数清零。 */
    void clear() {
        for (int index = 0; index < slotCount; index++) {
            slotStates.set(index, 0L);
        }
    }
}
