package com.surprising.aeron.service.state.account;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
/**
 * 一个 Lane、一个命令的 long 键前值捕获缓冲。按首次捕获顺序保存键和值引用，另用带代次的索引定位；null 是合法捕获值，是否已捕获需通过 containsKey 判断。调用方在原位变更前自行冻结需要保留的值。
 * @param <V> 调用方约定的捕获业务值类型，可以用 null 表示原值不存在
 */
final class LaneLongCaptures<V> {
    /** 按捕获顺序保存的 long 业务键，size 以外内容不属于本轮。 */
    private long[] keys = new long[8];
    /** 与 keys 同下标的捕获值引用，允许 null，清空时释放本轮有效引用。 */
    private Object[] values = new Object[8];
    /** 开放寻址查找索引中的 long 键。 */
    private long[] indexKeys = new long[16];
    /** 索引槽指向连续捕获数组的下标。 */
    private int[] indexSlots = new int[16];
    /** 各查找槽所属代次，非本代视为空槽。 */
    private int[] indexGenerations = new int[16];
    /** 当前查找代次，绕回零时清空代次标记并重新从一开始。 */
    private int indexGeneration = 1;
    /**  当前命令有效捕获项数量。 返回当前命令已捕获的有效条目数。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    int size;

    /**
     * 判断当前命令是否尚无捕获条目。
     */
    boolean isEmpty() { return size == 0; }

    /**
     * 检查键是否已捕获，保留捕获值为 null 的存在信息。
     * @param key 需要查找或新增捕获的 long 业务键，零也可作为键
     */
    boolean containsKey(long key) { return indexOf(key) >= 0; }

    /**
     * 按键取得捕获值，缺失和已捕获 null 都返回 null，需要区分时再检查 containsKey。
     * @param key 需要查找或新增捕获的 long 业务键，零也可作为键
     */
    V get(long key) {
        // 当前查找、清理或重建的连续捕获下标。
        int index = indexOf(key);
        return index < 0 ? null : value(index);
    }

    /**
     * 要求键尚未捕获，必要时增长连续数组及索引，再追加键和原值引用；不会自动深复制可变业务对象。
     * @param key 需要查找或新增捕获的 long 业务键，零也可作为键
     * @param value 首次捕获的值引用，null 表示原业务值不存在
     */
    void put(long key, V value) {
        if (indexOf(key) >= 0) throw new IllegalStateException("capture key already exists");
        ensureIndexCapacity(size + 1);
        if (size == keys.length) {
            // 需要翻倍后的连续数组或查找索引容量。
            int capacity = Math.multiplyExact(size, 2);
            keys = java.util.Arrays.copyOf(keys, capacity);
            values = java.util.Arrays.copyOf(values, capacity);
        }
        // 新键在本代查找索引中占用的槽。
        int indexPosition = emptyIndexPosition(key);
        keys[size] = key;
        values[size] = value;
        indexKeys[indexPosition] = key;
        indexSlots[indexPosition] = size;
        indexGenerations[indexPosition] = indexGeneration;
        size++;
    }

    /**
     * 只在键存在且当前引用就是 expected 时把引用替换为 frozen，防止冻结过程中误覆盖其他捕获结果。
     * @param key 需要查找或新增捕获的 long 业务键，零也可作为键
     * @param expected 冻结前应当仍保存的原引用，使用对象身份比较
     * @param frozen 替换进入捕获缓冲的已冻结值引用
     */
    void replaceReference(long key, V expected, V frozen) {
        // 需要冻结的捕获项下标，必须存在且原引用匹配。
        int slot = indexOf(key);
        if (slot < 0 || values[slot] != expected)
            throw new IllegalStateException("captured reference changed before freezing");
        values[slot] = frozen;
    }

    /**
     * 检查有效下标后返回该捕获项的原语键。
     * @param index 当前有效连续条目的下标
     */
    long key(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return keys[index];
    }

    /**
     * 检查有效下标后按缓冲约定类型返回值引用，可为 null。
     * @param index 当前有效连续条目的下标
     */
    @SuppressWarnings("unchecked")
    V value(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return (V) values[index];
    }

    /**
     * 在本代索引中线性探测长整型键，返回其连续下标，缺失为 -1。
     * @param key 需要查找或新增捕获的 long 业务键，零也可作为键
     */
    int indexOf(long key) {
        if (size == 0) return -1;
        // 二次幂索引容量减一，用于环绕探测。
        int mask = indexSlots.length - 1;
        // 当前线性探测或重建时使用的索引槽。
        int position = longHash(key) & mask;
        while (indexGenerations[position] == indexGeneration) {
            if (indexKeys[position] == key) return indexSlots[position];
            position = (position + 1) & mask;
        }
        return -1;
    }

    /**
     * 寻找本代尚未占用的索引槽，旧代次槽可直接复用。
     * @param key 需要查找或新增捕获的 long 业务键，零也可作为键
     */
    private int emptyIndexPosition(long key) {
        // 二次幂索引容量减一，用于环绕探测。
        int mask = indexSlots.length - 1;
        // 当前线性探测或重建时使用的索引槽。
        int position = longHash(key) & mask;
        while (indexGenerations[position] == indexGeneration) {
            position = (position + 1) & mask;
        }
        return position;
    }

    /**
     * 有效量超过索引一半容量时翻倍，按当前连续键前缀重建索引，不遍历历史捕获条目。
     * @param requiredSize 查找索引需要支持的有效条目数
     */
    private void ensureIndexCapacity(int requiredSize) {
        if (requiredSize <= indexSlots.length / 2) return;
        // 需要翻倍后的连续数组或查找索引容量。
        int capacity = Math.multiplyExact(indexSlots.length, 2);
        indexKeys = new long[capacity];
        indexSlots = new int[capacity];
        indexGenerations = new int[capacity];
        indexGeneration = 1;
        for (/* 当前查找、清理或重建的连续捕获下标。 */ int index = 0; index < size; index++) {
            // 当前线性探测或重建时使用的索引槽。
            int position = emptyIndexPosition(keys[index]);
            indexKeys[position] = keys[index];
            indexSlots[position] = index;
            indexGenerations[position] = indexGeneration;
        }
    }

    /**
     * 混合 long 键用于查找槽定位，不修改调用方保存的实际业务键。
     * @param key 需要查找或新增捕获的 long 业务键，零也可作为键
     */
    static int longHash(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        return (int) key;
    }

    /**
     * 只清除当前有效前缀的对象引用并把数量归零，再推进查找代次；保留键数组和容量，代次绕零时重置代次数组。
     */
    void clear() {
        if (size == 0) return;
        for (/* 当前查找、清理或重建的连续捕获下标。 */ int index = 0; index < size; index++) values[index] = null;
        size = 0;
        if (++indexGeneration == 0) {
            java.util.Arrays.fill(indexGenerations, 0);
            indexGeneration = 1;
        }
    }
}
