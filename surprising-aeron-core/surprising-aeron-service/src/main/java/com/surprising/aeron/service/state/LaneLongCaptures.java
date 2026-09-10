package com.surprising.aeron.service.state;

/** primitive 变更缓冲；每个实例仅由所属 owner 或 Lane 使用，清空时保留容量。 */
final class LaneLongCaptures<V> {
    /** 连续存放的 primitive 实体键；仅 [0,size) 有效。 */
    private long[] keys = new long[8];
    /** 与键对应的值；清空时释放引用并保留数组容量。 */
    private Object[] values = new Object[8];
    /** 开放寻址索引中的实体键，用于定位连续存储槽。 */
    private long[] indexKeys = new long[16];
    /** 索引槽对应的连续存储下标。 */
    private int[] indexSlots = new int[16];
    /** 每个索引槽所属的清空代次，避免每次清零整张索引。 */
    private int[] indexGenerations = new int[16];
    /** 当前有效代次；溢出时清空代次数组。 */
    private int indexGeneration = 1;
    /** 当前有效元素数量。 */
    int size;

    int size() { return size; }
    boolean isEmpty() { return size == 0; }

    boolean containsKey(long key) { return indexOf(key) >= 0; }

    V get(long key) {
        int index = indexOf(key);
        return index < 0 ? null : value(index);
    }

    void put(long key, V value) {
        if (indexOf(key) >= 0) throw new IllegalStateException("capture key already exists");
        ensureIndexCapacity(size + 1);
        if (size == keys.length) {
            int capacity = Math.multiplyExact(size, 2);
            keys = java.util.Arrays.copyOf(keys, capacity);
            values = java.util.Arrays.copyOf(values, capacity);
        }
        int indexPosition = emptyIndexPosition(key);
        keys[size] = key;
        values[size] = value;
        indexKeys[indexPosition] = key;
        indexSlots[indexPosition] = size;
        indexGenerations[indexPosition] = indexGeneration;
        size++;
    }

    long key(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return keys[index];
    }

    @SuppressWarnings("unchecked")
    V value(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return (V) values[index];
    }

    int indexOf(long key) {
        int mask = indexSlots.length - 1;
        int position = longHash(key) & mask;
        while (indexGenerations[position] == indexGeneration) {
            if (indexKeys[position] == key) return indexSlots[position];
            position = (position + 1) & mask;
        }
        return -1;
    }

    int emptyIndexPosition(long key) {
        int mask = indexSlots.length - 1;
        int position = longHash(key) & mask;
        while (indexGenerations[position] == indexGeneration) position = (position + 1) & mask;
        return position;
    }

    void ensureIndexCapacity(int requiredSize) {
        if (requiredSize <= indexSlots.length / 2) return;
        int capacity = Math.multiplyExact(indexSlots.length, 2);
        indexKeys = new long[capacity];
        indexSlots = new int[capacity];
        indexGenerations = new int[capacity];
        indexGeneration = 1;
        for (int index = 0; index < size; index++) {
            int position = emptyIndexPosition(keys[index]);
            indexKeys[position] = keys[index];
            indexSlots[position] = index;
            indexGenerations[position] = indexGeneration;
        }
    }

    static int longHash(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        return (int) key;
    }

    void clear() {
        if (size == 0) return;
        for (int index = 0; index < size; index++) values[index] = null;
        size = 0;
        if (++indexGeneration == 0) {
            java.util.Arrays.fill(indexGenerations, 0);
            indexGeneration = 1;
        }
    }
}
