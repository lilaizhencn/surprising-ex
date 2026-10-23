package com.surprising.aeron.service.result;

import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.order.ClusterCommandWindow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 响应字节的固定槽池：小响应可借预分配数组，超大响应或槽全被保留时使用独立精确长度数组。Lane 编码器、Owner 与出口通过原子计数共同持有槽；账本引用和发送引用分开计数，两类都归零才可再借。
 */
public final class ResponseArena {
    /** 默认槽数下限五百一十二，兼顾命令窗口与账本同时持有响应。 */
    static final int DEFAULT_SLOT_COUNT = 512;
    /** 默认固定槽字节容量六十四 KiB。 */
    static final int DEFAULT_SLOT_BYTES = 64 * 1024;

    /** 高三十二位存发送引用计数的位移。 */
    private static final int TRANSPORT_SHIFT = 32;
    /** 增加或归还一份发送引用对应的状态增量。 */
    private static final long TRANSPORT_UNIT = 1L << TRANSPORT_SHIFT;
    /** 取低三十二位账本引用计数的掩码。 */
    private static final long LEDGER_MASK = 0xFFFFFFFFL;

    /** 固定槽数组，建池时分配后不因大响应扩容。 */
    private final byte[][] slots;
    /** 各槽的原子复合引用计数，零表示可重新借用。 */
    private final AtomicLongArray slotStates;
    /** 与固定数组一一对应的复用编码描述符。 */
    private final Slot[] slotDescriptors;
    /** 每个固定槽的数组容量。 */
    private final int slotBytes;
    /** 固定槽总数，显式构造时不要求二次幂。 */
    private final int slotCount;
    /** 槽数是否为二次幂，用来选择掩码还是取模下标。 */
    private final boolean isPowerOfTwo;
    /** 槽数减一，仅二次幂槽数时作为下标掩码。 */
    private final int slotMask;
    /** 并发借槽的轮转起点计数器。 */
    private final AtomicInteger allocateCursor = new AtomicInteger();

    // 按 byte[] 对象身份建立到固定槽号的索引，归还时不用逐槽扫描数组。
    /** 数组身份探测表的二次幂掩码。 */
    private final int identityMask;
    /** 身份探测表里保存的各固定数组引用。 */
    private final byte[][] identityKeys;
    /** 身份探测槽对应的响应池槽号，空项为负一。 */
    private final int[] identityTable;

    /** 零长响应共用的非池描述符，不占固定槽引用。 */
    private static final Slot EMPTY_SLOT = new Slot(TradingCoreRuntime.EMPTY_RESPONSE_DATA, false, -1);

    /**
     * 一次响应编码可使用的数组描述符，保存池归属和槽号。池内描述符与数组一起复用，独立响应有自己的描述符；编码 writer 只由当前持有者使用，有效长度由原编码及响应交接流程维护。
     */
    public static final class Slot {
        /**  当前描述符借用的底层数组，池内数组可能大于有效长度。 借出底层数组，必须遵守描述符借用期并另带有效长度。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        byte[] storage;
        /** 底层数组是否属于本池固定槽，决定是否参与引用归还。 */
        final boolean arenaBacked;
        /** 池内固定槽号，独立数组和空描述符为负一。 */
        final int slotIndex;
        /** 与当前数组配对的复用 ByteBuffer，仅当前编码持有者修改其位置。 */
        private java.nio.ByteBuffer writer;

        /**
         * 保存该描述符的底层数组、本池归属及槽号，不在这里增加任何引用计数。
         * @param storage 要描述、定位或借还引用的底层数组
         * @param arenaBacked 该描述符是否对应本池固定数组
         * @param slotIndex 本池槽号，独立数组传负一
         */
        private Slot(byte[] storage, boolean arenaBacked, int slotIndex) {
            this.storage = storage;
            this.arenaBacked = arenaBacked;
            this.slotIndex = slotIndex;
        }

        /**
         * 复用底层数组对应的 ByteBuffer 并清读写位置；clear 重设 position 和 limit，不擦除已有字节，只有当前编码持有者操作它。
         */
        public java.nio.ByteBuffer writer() {
            if (writer == null || writer.array() != storage) writer = java.nio.ByteBuffer.wrap(storage);
            return writer.clear();
        }

}

    /**
     * 按 Owner 命令窗口配置计算固定槽数，使用默认每槽六十四 KiB 容量建池。
     */
    public ResponseArena() {
        this(com.surprising.aeron.config.CoreRuntimeConfiguration.standalone());
    }

    /**
     * 读取 JVM 命令窗口配置，按至少六十四的窗口预算另留二百五十六槽，再取至少五百一十二的二次幂容量；本入口不替命令窗口执行完整配置范围验证。
     */
    public ResponseArena(com.surprising.aeron.config.CoreRuntimeConfiguration configuration) {
        this(defaultSlotCount(configuration.ownerCommandWindow()), DEFAULT_SLOT_BYTES);
    }

    private static int defaultSlotCount(int configuredWindow) {
        // 在窗口及额外余量要求下计算的最少槽数
        int required = Math.max(DEFAULT_SLOT_COUNT, Math.max(64, configuredWindow) + 256);
        // 逐步翻倍至满足要求的候选槽数
        int slots = DEFAULT_SLOT_COUNT;
        while (slots < required) slots <<= 1;
        return slots;
    }

    /**
     * 要求槽数和每槽字节数为正，预分配固定数组、描述符与原子状态，并建立按数组对象身份查槽的探测表；显式构造也支持非二次幂槽数。
     * @param slotCount 要预分配的正槽数
     * @param slotBytes 每个固定槽的正字节容量
     */
    public ResponseArena(int slotCount, int slotBytes) {
        if (slotCount <= 0 || slotBytes <= 0) throw new IllegalArgumentException("invalid response arena");
        this.slotCount = slotCount;
        this.slotBytes = slotBytes;
        this.isPowerOfTwo = (slotCount & (slotCount - 1)) == 0;
        this.slotMask = slotCount - 1;
        this.slots = new byte[slotCount][];
        this.slotStates = new AtomicLongArray(slotCount);
        this.slotDescriptors = new Slot[slotCount];

        // 数组身份查找表的二次幂容量，最少十六
        int capacity = Integer.highestOneBit(slotCount) << 2;
        if (capacity < 16) capacity = 16;
        this.identityMask = capacity - 1;
        this.identityKeys = new byte[capacity][];
        this.identityTable = new int[capacity];
        java.util.Arrays.fill(this.identityTable, -1);

        for (/* 当前建立、查找或借还的池槽号，查找未命中时可为负一 */ int index = 0; index < slotCount; index++) {
            // 当前预分配的固定槽数组
            byte[] storage = new byte[slotBytes];
            this.slots[index] = storage;
            this.slotDescriptors[index] = new Slot(storage, true, index);

            // 底层数组的对象身份哈希
            int h = System.identityHashCode(storage);
            // 当前数组身份表的探测下标
            int probe = h & identityMask;
            while (identityKeys[probe] != null) {
                probe = (probe + 1) & identityMask;
            }
            identityKeys[probe] = storage;
            identityTable[probe] = index;
        }
    }

    /**
     * 零长返回共享空数组；其余尝试原子借固定槽，不能借时创建精确长度独立数组。池数组可能大于有效响应，调用方必须另带有效长度。
     * @param requiredLength 本次响应实际需要的有效字节数，必须非负
     */
    byte[] acquire(int requiredLength) {
        if (requiredLength == 0) return TradingCoreRuntime.EMPTY_RESPONSE_DATA;
        // 当前建立、查找或借还的池槽号，查找未命中时可为负一
        int index = claimPooledSlot(requiredLength);
        return index < 0 ? new byte[requiredLength] : slots[index];
    }

    /**
     * 零长返回共享空描述符；成功借池槽则返回复用描述符，池满或过大则创建独立数组和描述符。
     * @param requiredLength 本次响应实际需要的有效字节数，必须非负
     */
    public Slot acquireSlot(int requiredLength) {
        if (requiredLength == 0) return EMPTY_SLOT;
        // 当前建立、查找或借还的池槽号，查找未命中时可为负一
        int index = claimPooledSlot(requiredLength);
        if (index < 0) return new Slot(new byte[requiredLength], false, -1);
        return slotDescriptors[index];
    }

    /**
     * 拒绝负长度，超过单槽容量直接返回负一；从轮转起点最多检查全部槽，只把状态零原子改成一份发送引用，失败不覆盖仍被持有的数组。
     * @param requiredLength 本次响应实际需要的有效字节数，必须非负
     */
    private int claimPooledSlot(int requiredLength) {
        if (requiredLength < 0) throw new IllegalArgumentException("negative response length");
        if (requiredLength > slotBytes) return -1;
        // 原子取得并去掉符号位的本轮轮转起点
        int start = allocateCursor.getAndIncrement() & 0x7FFFFFFF;
        // 本轮缓存的槽数是否为二次幂
        boolean powerOfTwo = this.isPowerOfTwo;
        // 当前循环采用的槽或身份表下标掩码
        int mask = this.slotMask;
        // 本轮最多检查的固定槽数
        int count = this.slotCount;
        for (/* 当前借槽扫描的相对位置 */ int i = 0; i < count; i++) {
            // 当前建立、查找或借还的池槽号，查找未命中时可为负一
            int index = powerOfTwo ? ((start + i) & mask) : ((start + i) % count);
            if (slotStates.get(index) == 0L && slotStates.compareAndSet(index, 0L, TRANSPORT_UNIT)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 按 byte[] 对象身份查本池槽号，空数组引用或非本池数组返回负一，不按数组内容相等判断归属。
     * @param storage 要描述、定位或借还引用的底层数组
     */
    private int findSlotIndex(byte[] storage) {
        if (storage == null) return -1;
        // 当前循环采用的槽或身份表下标掩码
        int mask = identityMask;
        // 底层数组的对象身份哈希
        int h = System.identityHashCode(storage);
        // 当前数组身份表的探测下标
        int probe = h & mask;
        while (true) {
            // 身份探测表当前保存的数组引用，用对象身份比较
            byte[] key = identityKeys[probe];
            if (key == storage) return identityTable[probe];
            if (key == null) return -1;
            probe = (probe + 1) & mask;
        }
    }

    /**
     * 只对本池描述符归还一份发送引用；空描述符、独立数组描述符或负槽号不处理。
     * @param slot 需归还发送引用的可空描述符
     */
    public void release(Slot slot) {
        if (slot == null || !slot.arenaBacked || slot.slotIndex < 0) return;
        releaseSlot(slot.slotIndex);
    }

    /**
     * 先按数组身份查槽，只归还本池数组的一份发送引用；独立数组由正常对象生命周期管理。
     * @param storage 要描述、定位或借还引用的底层数组
     */
    public void release(byte[] storage) {
        if (storage == null) return;
        // 当前建立、查找或借还的池槽号，查找未命中时可为负一
        int index = findSlotIndex(storage);
        if (index < 0) return;
        releaseSlot(index);
    }

    /**
     * 用 CAS 扣一份发送引用；没有发送引用时再次归还明确报错，账本引用仍在则槽继续占用。
     * @param index 本池固定槽号，由内部已完成的查找或借槽产生
     */
    private void releaseSlot(int index) {
        while (true) {
            // 本轮 CAS 前读到的复合引用计数状态
            long state = slotStates.get(index);
            // 状态高三十二位的发送引用数
            long transport = state >>> TRANSPORT_SHIFT;
            if (state == 0L || transport <= 0) {
                throw new IllegalStateException("response arena transport slot released more than once");
            }
            // 扣除一份对应引用后准备 CAS 写入的状态
            long next = state - TRANSPORT_UNIT;
            if (slotStates.compareAndSet(index, state, next)) {
                return;
            }
        }
    }

    /**
     * 数组属于本池时增加一份发送或输出持有引用；空值和独立数组不参与池计数。
     * @param storage 要描述、定位或借还引用的底层数组
     */
    public void retain(byte[] storage) {
        if (storage == null) return;
        // 当前建立、查找或借还的池槽号，查找未命中时可为负一
        int index = findSlotIndex(storage);
        if (index < 0) return;
        retainSlot(index);
    }

    /**
     * 已有任意借用引用时用 CAS 增加一份发送引用，完全空闲槽不能凭旧数组引用重新续借。
     * @param index 本池固定槽号，由内部已完成的查找或借槽产生
     */
    private void retainSlot(int index) {
        while (true) {
            // 本轮 CAS 前读到的复合引用计数状态
            long state = slotStates.get(index);
            if (state == 0L) {
                throw new IllegalStateException("response arena slot is not leased");
            }
            if (slotStates.compareAndSet(index, state, state + TRANSPORT_UNIT)) {
                return;
            }
        }
    }

    /**
     * 本池数组新增一份独立账本引用，避免首次发送结束后字节提前被覆盖。
     * @param storage 要描述、定位或借还引用的底层数组
     */
    void retainLedger(byte[] storage) {
        if (storage == null) return;
        // 当前建立、查找或借还的池槽号，查找未命中时可为负一
        int index = findSlotIndex(storage);
        if (index < 0) return;
        retainLedgerSlot(index);
    }

    /**
     * 对仍被持有的槽用 CAS 增加低位账本引用，完全空闲槽报错。
     * @param index 本池固定槽号，由内部已完成的查找或借槽产生
     */
    private void retainLedgerSlot(int index) {
        while (true) {
            // 本轮 CAS 前读到的复合引用计数状态
            long state = slotStates.get(index);
            if (state == 0L) {
                throw new IllegalStateException("response arena slot is not leased");
            }
            if (slotStates.compareAndSet(index, state, state + 1L)) {
                return;
            }
        }
    }

    /**
     * 淘汰或替换结果后归还本池数组的一份账本引用；非本池数组不处理。
     * @param storage 要描述、定位或借还引用的底层数组
     */
    void releaseLedger(byte[] storage) {
        if (storage == null) return;
        // 当前建立、查找或借还的池槽号，查找未命中时可为负一
        int index = findSlotIndex(storage);
        if (index < 0) return;
        releaseLedgerSlot(index);
    }

    /**
     * 用 CAS 扣一份账本引用，无账本引用时重复归还报错；发送仍未结束则槽继续占用。
     * @param index 本池固定槽号，由内部已完成的查找或借槽产生
     */
    private void releaseLedgerSlot(int index) {
        while (true) {
            // 本轮 CAS 前读到的复合引用计数状态
            long state = slotStates.get(index);
            // 状态低三十二位的账本引用数
            int ledger = (int) (state & LEDGER_MASK);
            if (state == 0L || ledger <= 0) {
                throw new IllegalStateException("response arena ledger slot released more than once");
            }
            // 扣除一份对应引用后准备 CAS 写入的状态
            long next = state - 1L;
            if (slotStates.compareAndSet(index, state, next)) {
                return;
            }
        }
    }

    /**
     * 在生命周期清理边界强制把全部槽计数清零；不擦除字节、不重建描述符，调用方须先保证其他借用方不再访问。
     */
    void clear() {
        for (/* 当前建立、查找或借还的池槽号，查找未命中时可为负一 */ int index = 0; index < slotCount; index++) {
            slotStates.set(index, 0L);
        }
    }
}
