package com.surprising.aeron.service.state;

import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.Long2LongHashMap;

import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/** primitive 变更缓冲；每个实例仅由所属 owner 或 Lane 使用，清空时保留容量。 */
class RuntimeChangeBuffer<V> {
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

    void ensureCapacity(int expectedSize) {
        if (expectedSize <= 0) return;
        int valueCapacity = keys.length;
        while (valueCapacity < expectedSize) valueCapacity = Math.multiplyExact(valueCapacity, 2);
        if (valueCapacity != keys.length) {
            keys = java.util.Arrays.copyOf(keys, valueCapacity);
            values = java.util.Arrays.copyOf(values, valueCapacity);
        }
        int indexCapacity = indexSlots.length;
        while (indexCapacity < expectedSize * 2) indexCapacity = Math.multiplyExact(indexCapacity, 2);
        if (indexCapacity == indexSlots.length) return;
        indexKeys = new long[indexCapacity];
        indexSlots = new int[indexCapacity];
        indexGenerations = new int[indexCapacity];
        indexGeneration = 1;
        for (int index = 0; index < size; index++) {
            int position = emptyIndexPosition(keys[index]);
            indexKeys[position] = keys[index];
            indexSlots[position] = index;
            indexGenerations[position] = indexGeneration;
        }
    }

    int put(long key, V value) {
        int slot = indexOf(key);
        if (slot >= 0) {
            values[slot] = value;
            return slot;
        }
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
        return size++;
    }

    void drain(LongObjectHashMap<V> target, Long2LongHashMap targetLanes, int laneId) {
        for (int index = 0; index < size; index++) {
            long key = keys[index];
            @SuppressWarnings("unchecked") V value = (V) values[index];
            values[index] = null;
            if (value == null) {
                target.removeKey(key);
                if (targetLanes != null) targetLanes.remove(key);
            } else {
                target.put(key, value);
                if (targetLanes != null) targetLanes.put(key, laneId + 1L);
            }
        }
        clear();
    }

    void drain(Long2ObjectHashMap<V> target, Long2LongHashMap targetLanes, int laneId) {
        for (int index = 0; index < size; index++) {
            long key = keys[index];
            @SuppressWarnings("unchecked") V value = (V) values[index];
            values[index] = null;
            if (value == null) {
                target.remove(key);
                if (targetLanes != null) targetLanes.remove(key);
            } else {
                target.put(key, value);
                if (targetLanes != null) targetLanes.put(key, laneId + 1L);
            }
        }
        clear();
    }

    boolean isEmpty() {
        return size == 0;
    }

    int size() { return size; }

    long keyAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return keys[index];
    }

    @SuppressWarnings("unchecked")
    V valueAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return (V) values[index];
    }

    @SuppressWarnings("unchecked")
    V get(long key) {
        int slot = indexOf(key);
        return slot < 0 ? null : (V) values[slot];
    }

    boolean containsKey(long key) {
        return indexOf(key) >= 0;
    }

    void forEach(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<V> consumer) {
        for (int index = 0; index < size; index++) {
            @SuppressWarnings("unchecked") V value = (V) values[index];
            consumer.value(keys[index], value);
        }
    }

    /** 发布到另一所有者的提交视图时同遍释放引用；回调不得修改或读取本缓冲。失败后由日志恢复。 */
    void drainTo(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<V> consumer) {
        for (int index = 0; index < size; index++) {
            @SuppressWarnings("unchecked") V value = (V) values[index];
            consumer.value(keys[index], value);
            values[index] = null;
        }
        resetIndex();
    }

    org.eclipse.collections.api.iterator.LongIterator longIterator() {
        return new org.eclipse.collections.api.iterator.LongIterator() {
            /** 当前只读迭代器位置，不改变底层变更缓冲。 */
            int cursor;

            @Override
            public long next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                return keys[cursor++];
            }

            @Override
            public boolean hasNext() {
                return cursor < size;
            }
        };
    }

    long[] toArray() {
        return java.util.Arrays.copyOf(keys, size);
    }

    void clear() {
        for (int index = 0; index < size; index++) values[index] = null;
        resetIndex();
    }

    private void resetIndex() {
        size = 0;
        if (++indexGeneration == 0) {
            java.util.Arrays.fill(indexGenerations, 0);
            indexGeneration = 1;
        }
    }

    int indexOf(long key) {
        int mask = indexSlots.length - 1;
        int position = LaneLongCaptures.longHash(key) & mask;
        while (indexGenerations[position] == indexGeneration) {
            if (indexKeys[position] == key) return indexSlots[position];
            position = (position + 1) & mask;
        }
        return -1;
    }

    int emptyIndexPosition(long key) {
        int mask = indexSlots.length - 1;
        int position = LaneLongCaptures.longHash(key) & mask;
        while (indexGenerations[position] == indexGeneration) {
            position = (position + 1) & mask;
        }
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
}
