package com.surprising.aeron.service.core.change;

import lombok.Getter;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/**
 * 由单个 Owner 或 Lane 持有的命令变化缓冲。键和值按首次插入顺序连续保存，原语索引负责定位，重复键覆盖同一槽；null 是有效删除变化，containsKey 可区分删除与未记录。值默认只是引用，交接和复制由具体缓冲及调用方保证，清空保留容量。
 * @param <V> 业务变化值类型，null 可表示本次删除
 */
public class RuntimeChangeBuffer<V> {
    /** 按首次插入顺序保存的原语实体键，只使用零到 size 之前的槽。 */
    private long[] keys = new long[8];
    /** 同下标的业务值引用，null 是合法删除变化，清空后释放有效引用。 */
    private Object[] values = new Object[8];
    /** 开放寻址查找表中的实体键。 */
    private long[] indexKeys = new long[16];
    /** 查找槽到连续键值数组下标的映射。 */
    private int[] indexSlots = new int[16];
    /** 查找槽所属清空代次，非当前代次的槽视为空。 */
    private int[] indexGenerations = new int[16];
    /** 当前有效查找代次，绕回零才全清代次标记。 */
    private int indexGeneration = 1;
    /**  本轮连续有效变化数量，不代表底层保留容量。 返回当前有效变化条目数。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    int size;

    /**
     * 在所有权交接完成后交换两个缓冲的连续数组、查找索引、代次及有效长度；不逐项复制值，不提供并发互斥。
     * @param other 已经完成所有权交接、可独占交换存储的另一个同类型缓冲
     */
    void swapStorage(RuntimeChangeBuffer<V> other) {
        // 交换期间暂存的当前原语键数组。
        long[] k = keys; keys = other.keys; other.keys = k;
        // 交换期间暂存的当前业务值数组。
        Object[] v = values; values = other.values; other.values = v;
        // 交换期间暂存的当前查找键数组。
        long[] ik = indexKeys; indexKeys = other.indexKeys; other.indexKeys = ik;
        // 交换期间暂存的查找槽到连续位置映射。
        int[] slots = indexSlots; indexSlots = other.indexSlots; other.indexSlots = slots;
        // 交换期间暂存的查找槽代次数组。
        int[] gens = indexGenerations; indexGenerations = other.indexGenerations; other.indexGenerations = gens;
        // 交换期间暂存的当前代次。
        int gen = indexGeneration; indexGeneration = other.indexGeneration; other.indexGeneration = gen;
        // 交换期间暂存的有效变化数。
        int count = size; size = other.size; other.size = count;
    }

    /**
     * 提前按二次幂准备键值容量与查找索引，只在索引增长时重建当前有效键的位置；非正数请求不处理。
     * @param expectedSize 本轮预期需要容纳的有效变化数
     */
    void ensureCapacity(int expectedSize) {
        if (expectedSize <= 0) return;
        // 满足预计有效量所需的二次幂连续数组容量。
        int valueCapacity = keys.length;
        while (valueCapacity < expectedSize) valueCapacity = Math.multiplyExact(valueCapacity, 2);
        if (valueCapacity != keys.length) {
            keys = java.util.Arrays.copyOf(keys, valueCapacity);
            values = java.util.Arrays.copyOf(values, valueCapacity);
        }
        // 满足预计查找负载所需的二次幂索引容量。
        int indexCapacity = indexSlots.length;
        while (indexCapacity < expectedSize * 2) indexCapacity = Math.multiplyExact(indexCapacity, 2);
        if (indexCapacity == indexSlots.length) return;
        indexKeys = new long[indexCapacity];
        indexSlots = new int[indexCapacity];
        indexGenerations = new int[indexCapacity];
        indexGeneration = 1;
        for (/* 当前遍历、消费或重建的有效连续下标。 */ int index = 0; index < size; index++) {
            // 当前键在线性探测索引中选中的位置。
            int position = emptyIndexPosition(keys[index]);
            indexKeys[position] = keys[index];
            indexSlots[position] = index;
            indexGenerations[position] = indexGeneration;
        }
    }

    /**
     * 把 long 业务键混合为查找用哈希，零和负数键也正常参与，不改变原键。
     * @param key 原语 long 业务键，允许零与负数
     */
    private static int longHash(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        return (int) key;
    }

    /**
     * 查到已有键就覆盖其值并返回原槽；新键追加到连续前缀，必要时增长索引与数组，允许保存 null 删除标记。
     * @param key 原语 long 业务键，允许零与负数
     * @param value 要保存的业务值引用，null 表示删除或调用方定义的空值
     */
    public int put(long key, V value) {
        // 二次幂索引长度减一，用于环绕探测。
        int mask = indexSlots.length - 1;
        // 当前键匹配或可追加的查找槽，索引扩容后需要重新定位。
        int indexPosition = longHash(key) & mask;
        while (indexGenerations[indexPosition] == indexGeneration) {
            if (indexKeys[indexPosition] == key) {
                // 当前键已有的连续键值位置。
                int slot = indexSlots[indexPosition];
                values[slot] = value;
                return slot;
            }
            indexPosition = (indexPosition + 1) & mask;
        }
        ensureIndexCapacity(size + 1);
        // 复用本次查找找到的空槽；只有索引已经扩容时才需要重新定位。
        if (mask != indexSlots.length - 1) indexPosition = emptyIndexPosition(key);
        if (size == keys.length) {
            // 本次键值数组或查找索引翻倍后的容量。
            int capacity = Math.multiplyExact(size, 2);
            keys = java.util.Arrays.copyOf(keys, capacity);
            values = java.util.Arrays.copyOf(values, capacity);
        }
        keys[size] = key;
        values[size] = value;
        indexKeys[indexPosition] = key;
        indexSlots[indexPosition] = size;
        indexGenerations[indexPosition] = indexGeneration;
        return size++;
    }

    /**
     * 逐项把值引用写入目标原语 Map，null 则删除目标键，并释放源引用；全部处理完才重置索引，不承诺回调或写入异常时原子回滚。
     * @param target 接收变化的目标原语 Map，写入引用而非自动深复制
     */
    public void drainToEclipseMap(LongObjectHashMap<V> target) {
        for (/* 当前遍历、消费或重建的有效连续下标。 */ int index = 0; index < size; index++) {
            // 当前遍历项的原语业务键。
            long key = keys[index];
            // 当前连续槽的业务值引用，null 删除项也必须被消费者看到。
            @SuppressWarnings("unchecked") V value = (V) values[index];
            values[index] = null;
            if (value == null) {
                target.removeKey(key);
            } else {
                target.put(key, value);
            }
        }
        resetIndex();
    }

    /**
     * 检查当前有效变化数是否为零。
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * 校验有效下标后读取该项原语键。
     * @param index 当前有效连续变化槽下标
     */
    public long keyAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return keys[index];
    }

    /**
     * 校验有效下标后读取值引用，null 可能表示已记录的删除。
     * @param index 当前有效连续变化槽下标
     */
    public @SuppressWarnings("unchecked")
    V valueAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return (V) values[index];
    }

    /**
     * 校验有效下标后只替换该项值，不改变键、位置或索引。
     * @param index 当前有效连续变化槽下标
     * @param value 要保存的业务值引用，null 表示删除或调用方定义的空值
     */
    void setValueAt(int index, V value) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        values[index] = value;
    }

    /**
     * 按键读取当前值，缺失与已记录 null 都返回 null，需要区分时使用 containsKey。
     * @param key 原语 long 业务键，允许零与负数
     */
    public @SuppressWarnings("unchecked")
    V get(long key) {
        // 当前键已有的连续键值位置。
        int slot = indexOf(key);
        return slot < 0 ? null : (V) values[slot];
    }

    /**
     * 检查当前命令是否记录该键，包括值为 null 的删除项。
     * @param key 原语 long 业务键，允许零与负数
     */
    public boolean containsKey(long key) {
        return indexOf(key) >= 0;
    }

    /**
     * 按连续有效前缀访问键和值引用，保持首次插入顺序，不清空缓冲；回调应遵守当前所有权边界。
     * @param consumer 在当前缓冲所有权边界内处理原语键及值的回调
     */
    public void forEach(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<V> consumer) {
        for (/* 当前遍历、消费或重建的有效连续下标。 */ int index = 0; index < size; index++) {
            // 当前连续槽的业务值引用，null 删除项也必须被消费者看到。
            @SuppressWarnings("unchecked") V value = (V) values[index];
            consumer.value(keys[index], value);
        }
    }

    /**
     * 逐项交给提交消费者，成功消费一项后释放该引用，全部成功后重置索引；回调不能读写本缓冲，途中失败由外层故障与恢复边界处理。
     * @param consumer 在当前缓冲所有权边界内处理原语键及值的回调
     */
    void drainTo(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<V> consumer) {
        for (/* 当前遍历、消费或重建的有效连续下标。 */ int index = 0; index < size; index++) {
            // 当前连续槽的业务值引用，null 删除项也必须被消费者看到。
            @SuppressWarnings("unchecked") V value = (V) values[index];
            consumer.value(keys[index], value);
            values[index] = null;
        }
        resetIndex();
    }

    /**
     * 只清空当前有效前缀的值引用并重置索引代次，保留原语键数组和容量，空缓冲不重复清理。
     */
    public void clear() {
        if (size == 0) return;
        for (/* 当前遍历、消费或重建的有效连续下标。 */ int index = 0; index < size; index++) values[index] = null;
        resetIndex();
    }

    /**
     * 把数量归零并推进代次，代次加一绕回零时清空代次标记并重新从一开始。
     */
    private void resetIndex() {
        size = 0;
        if (++indexGeneration == 0) {
            java.util.Arrays.fill(indexGenerations, 0);
            indexGeneration = 1;
        }
    }

    /**
     * 空缓冲直接返回 -1，否则在本代线性探测索引中定位业务键，返回连续槽位。
     * @param key 原语 long 业务键，允许零与负数
     */
    public int indexOf(long key) {
        // 当前没有有效项时不必哈希或探测历史保留的索引，直接返回未找到。
        if (size == 0) return -1;
        // 二次幂索引长度减一，用于环绕探测。
        int mask = indexSlots.length - 1;
        // 当前键在线性探测索引中选中的位置。
        int position = longHash(key) & mask;
        while (indexGenerations[position] == indexGeneration) {
            if (indexKeys[position] == key) return indexSlots[position];
            position = (position + 1) & mask;
        }
        return -1;
    }

    /**
     * 从键哈希起点寻找不属于本代的空查找槽。
     * @param key 原语 long 业务键，允许零与负数
     */
    int emptyIndexPosition(long key) {
        // 二次幂索引长度减一，用于环绕探测。
        int mask = indexSlots.length - 1;
        // 当前键在线性探测索引中选中的位置。
        int position = longHash(key) & mask;
        while (indexGenerations[position] == indexGeneration) {
            position = (position + 1) & mask;
        }
        return position;
    }

    /**
     * 新增有效项超过索引一半容量时翻倍，只重建当前连续有效键的查找位置。
     * @param requiredSize 查找索引需要容纳的有效键数量
     */
    void ensureIndexCapacity(int requiredSize) {
        if (requiredSize <= indexSlots.length / 2) return;
        // 本次键值数组或查找索引翻倍后的容量。
        int capacity = Math.multiplyExact(indexSlots.length, 2);
        indexKeys = new long[capacity];
        indexSlots = new int[capacity];
        indexGenerations = new int[capacity];
        indexGeneration = 1;
        for (/* 当前遍历、消费或重建的有效连续下标。 */ int index = 0; index < size; index++) {
            // 当前键在线性探测索引中选中的位置。
            int position = emptyIndexPosition(keys[index]);
            indexKeys[position] = keys[index];
            indexSlots[position] = index;
            indexGenerations[position] = indexGeneration;
        }
    }
}

