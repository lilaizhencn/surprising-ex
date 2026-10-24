package com.surprising.aeron.service.order;

import java.util.UUID;

/**
 * Owner 有界待撮合窗口的 UUID 派生索引。预分配高低位数组和命令槽引用，用线性探测定位；删除后重插后续探测簇。它不拥有命令生命周期、不自行扩容，也不是可多线程写的 Map；调用方把占用控制在正常窗口容量内。
 */
final class PendingCommandIdIndex {
    /** 各物理条目 UUID 高六十四位。 */
    private final long[] mostSignificantBits;
    /** 各物理条目 UUID 低六十四位。 */
    private final long[] leastSignificantBits;
    /** 各物理位置的命令槽引用，空引用表示未使用。 */
    private final CommandSlot[] values;
    /** 固定数组容量减一的探测环绕掩码。 */
    private final int mask;
    /** 当前索引中非空条目数量。 */
    private int size;

    /**
     * 要求预期条目数为正，按其两倍向上取二次幂建立数组，使正常窗口最多半满；本构造未单独检查倍增的 int 溢出。
     * @param expectedEntries 正常有界窗口最多需要索引的命令数
     */
    PendingCommandIdIndex(int expectedEntries) {
        if (expectedEntries <= 0) throw new IllegalArgumentException("expected entries must be positive");
        // 按预期条目数两倍向上取二次幂的数组容量。
        int capacity = 1;
        while (capacity < expectedEntries * 2) capacity <<= 1;
        mostSignificantBits = new long[capacity];
        leastSignificantBits = new long[capacity];
        values = new CommandSlot[capacity];
        mask = capacity - 1;
    }

    /**
     * 按 UUID 两半混合后的起点连续探测，找到相同位值返回原槽引用，遇到空位返回空。
     * @param most UUID 高六十四位
     * @param least UUID 低六十四位
     */
    CommandSlot get(long most, long least) {
        // 当前线性探测的物理位置，越界按掩码环绕。
        int index = index(most, least);
        while (values[index] != null) {
            if (mostSignificantBits[index] == most && leastSignificantBits[index] == least) return values[index];
            index = (index + 1) & mask;
        }
        return null;
    }

    /**
     * 空 UUID 直接返回空，否则拆成两半查询，不创建新的键对象。
     * @param commandId 要定位的命令 UUID
     */
    CommandSlot get(UUID commandId) {
        if (commandId == null) return null;
        return get(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits());
    }

    /**
     * 同 UUID 已有条目就替换引用，否则写入首空位并增加数量；半满检查发生在插入之后，超限异常不会回滚刚写入的条目。
     * @param most UUID 高六十四位
     * @param least UUID 低六十四位
     * @param value 需要登记或重插的非空命令槽引用
     */
    void put(long most, long least, CommandSlot value) {
        if (value == null) throw new IllegalArgumentException("command value is required");
        // 当前线性探测的物理位置，越界按掩码环绕。
        int index = index(most, least);
        while (values[index] != null) {
            if (mostSignificantBits[index] == most && leastSignificantBits[index] == least) {
                values[index] = value;
                return;
            }
            index = (index + 1) & mask;
        }
        mostSignificantBits[index] = most;
        leastSignificantBits[index] = least;
        values[index] = value;
        size++;
        if (size * 2 > values.length) throw new IllegalStateException("pending command-id index is full");
    }

    /**
     * 找到 UUID 后，仅在 expected 为空或与当前值按对象身份相同时删除；不匹配的旧请求不会移走另一个值。
     * @param most UUID 高六十四位
     * @param least UUID 低六十四位
     * @param expected 删除时可选的预期对象引用，空值表示只按 UUID 删除
     */
    void remove(long most, long least, CommandSlot expected) {
        // 当前线性探测的物理位置，越界按掩码环绕。
        int index = index(most, least);
        while (values[index] != null) {
            if (mostSignificantBits[index] == most && leastSignificantBits[index] == least) {
                if (expected != null && values[index] != expected) return;
                removeAt(index);
                return;
            }
            index = (index + 1) & mask;
        }
    }

    /**
     * 移走目标槽后，沿后续连续探测簇逐个取出并重新插入，修复查找链，直到遇到原空位。
     * @param removed 准备移除的物理条目下标
     */
    private void removeAt(int removed) {
        values[removed] = null;
        mostSignificantBits[removed] = 0;
        leastSignificantBits[removed] = 0;
        size--;
        // 当前线性探测的物理位置，越界按掩码环绕。
        int index = (removed + 1) & mask;
        while (values[index] != null) {
            // 删除修复时临时取出的后续槽引用。
            CommandSlot value = values[index];
            // 删除修复条目的 UUID 高半部。
            long most = mostSignificantBits[index];
            // 删除修复条目的 UUID 低半部。
            long least = leastSignificantBits[index];
            values[index] = null;
            mostSignificantBits[index] = 0;
            leastSignificantBits[index] = 0;
            size--;
            insert(most, least, value);
            index = (index + 1) & mask;
        }
    }

    /**
     * 把已移出的有效条目重新放到当前哈希起点之后的首空位，只用于删除修复，不再次执行半满检查。
     * @param most UUID 高六十四位
     * @param least UUID 低六十四位
     * @param value 需要登记或重插的非空命令槽引用
     */
    private void insert(long most, long least, CommandSlot value) {
        // 当前线性探测的物理位置，越界按掩码环绕。
        int index = index(most, least);
        while (values[index] != null) index = (index + 1) & mask;
        mostSignificantBits[index] = most;
        leastSignificantBits[index] = least;
        values[index] = value;
        size++;
    }

    /**
     * 对 UUID 两半执行固定混合，再用容量掩码得到起点，不是密码摘要。
     * @param most UUID 高六十四位
     * @param least UUID 低六十四位
     */
    private int index(long most, long least) {
        // 由两半 UUID 混合出的固定 long 哈希累加值。
        long hash = most ^ Long.rotateLeft(least, 23);
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdl;
        hash ^= hash >>> 33;
        return (int) hash & mask;
    }
}
