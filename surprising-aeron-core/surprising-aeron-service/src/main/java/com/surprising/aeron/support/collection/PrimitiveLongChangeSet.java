package com.surprising.aeron.support.collection;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import lombok.Getter;
import lombok.experimental.Accessors;

/** 单个业务范围复用的变更 ID 集合：long 数组保留首次出现顺序，开放寻址索引去重，代号清空让旧槽立即失效。它继承 Collection 以接入现有边界，非线程安全，也不是任意删除或并发修改安全的通用 Set。 */
public final class PrimitiveLongChangeSet extends AbstractCollection<Long> implements PrimitiveLongView {

    /** 按本代首次出现顺序保存的原始 ID 数组，size 之外为不可读旧值或空槽。 */
    private long[] values;
    private long[] indexKeys;
    private int[] indexGenerations;
    private int generation = 1;
    @Accessors(fluent = true, chain = false)
    @Getter(onMethod_ = {@Override})
    private int size;

    @Override
    public int primitiveSize() {
        return size;
    }

    @Override
    public long primitiveValueAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index=" + index + " size=" + size);
        }
        return values[index];
    }

    public void forEachLong(java.util.function.LongConsumer consumer) {
        for (int index = 0; index < size; index++) {
            consumer.accept(values[index]);
        }
    }

    /** 按预期八条建立有序变更集合。 */
    public PrimitiveLongChangeSet() {
        this(8);
    }

    /** 要求预期大小非负，把值数组容量从八按二倍扩到足够，再给哈希索引两倍槽数。 */
    public PrimitiveLongChangeSet(int expectedSize) {
        if (expectedSize < 0) throw new IllegalArgumentException("expected size must not be negative");
        int valueCapacity = 8;
        while (valueCapacity < expectedSize) valueCapacity = Math.multiplyExact(valueCapacity, 2);
        int indexCapacity = valueCapacity << 1;
        values = new long[valueCapacity];
        indexKeys = new long[indexCapacity];
        indexGenerations = new int[indexCapacity];
    }

    /** 先探测当前代号中的重复 ID。 */
    public boolean add(long value) {
        int mask = indexKeys.length - 1;
        int position = longHash(value) & mask;
        while (indexGenerations[position] == generation) {
            if (indexKeys[position] == value) return false;
            position = (position + 1) & mask;
        }
        ensureCapacity(size + 1);
        if (mask != indexKeys.length - 1) position = emptyIndexPosition(value);
        values[size++] = value;
        indexKeys[position] = value;
        indexGenerations[position] = generation;
        return true;
    }

    /** 优先直接读取同类变更集合或 PrimitiveLongView，其他集合再遍历 Long。合并保持各值首次进入本集合的顺序，返回是否新增过元素。 */
    @Override
    public boolean addAll(Collection<? extends Long> values) {
        boolean changed = false;
        if (values instanceof PrimitiveLongChangeSet primitive) {
            for (int index = 0; index < primitive.size; index++) changed |= add(primitive.values[index]);
            return changed;
        }
        if (values instanceof PrimitiveLongView primitive) {
            for (int index = 0; index < primitive.primitiveSize(); index++) {
                changed |= add(primitive.primitiveValueAt(index));
            }
            return changed;
        }
        for (Long value : values) changed |= add(value);
        return changed;
    }

    /** 要求包装值非空，拆箱后走同一套去重追加。 */
    @Override
    public boolean add(Long value) {
        if (value == null) throw new NullPointerException("primitive change id is required");
        return add(value.longValue());
    }

    /** 只有 Long 对象才查索引，其他类型和空值返回假。 */
    @Override
    public boolean contains(Object value) {
        return value instanceof Long id && indexOf(id.longValue()) >= 0;
    }

    /** 只复制当前有效值，得到独立、按首次出现顺序排列的 long 数组。 */
    public long[] toPrimitiveArray() {
        return Arrays.copyOf(values, size);
    }

    /** 空集合复用空列表，否则复制有效值后把副本交只读列表，集合后续清空或复用不影响它。 */
    public ImmutableLongArrayList toImmutableList() {
        if (size == 0) return ImmutableLongArrayList.empty();
        return ImmutableLongArrayList.takeOwnership(toPrimitiveArray());
    }

    /** 按当前有效长度检查下标，再取首次出现顺序中的 long 值。 */
    public long valueAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return values[index];
    }

    /** 创建各自从零开始的装箱迭代器，读取的是原集合当前 size 和 values。不冻结内容，也不提供并发修改检测或删除支持。 */
    @Override
    public Iterator<Long> iterator() {
        // 本次调用独占顺序下标的轻量迭代器，借用外部集合的当前数组与长度，不是快照。
        return new Iterator<>() {
            /** 当前匿名迭代器独占的顺序下标，不是哈希槽号。 */
            private int index;

            /** 按本迭代器下标与集合当前有效长度判断是否还有值。 */
            @Override
            public boolean hasNext() { return index < size; }

            /** 返回当前下标的值并向后移动。 */
            @Override
            public Long next() {
                if (index >= size) throw new NoSuchElementException();
                return values[index++];
            }
        };
    }

    /** 归零有效长度并换代，让原索引槽失效而不逐个清键。 */
    @Override
    public void clear() {
        size = 0;
        if (++generation == 0) {
            Arrays.fill(indexGenerations, 0);
            generation = 1;
        }
    }

    private int indexOf(long value) {
        int mask = indexKeys.length - 1;
        int position = longHash(value) & mask;
        while (indexGenerations[position] == generation) {
            if (indexKeys[position] == value) return position;
            position = (position + 1) & mask;
        }
        return -1;
    }

    private int emptyIndexPosition(long value) {
        int mask = indexKeys.length - 1;
        int position = longHash(value) & mask;
        while (indexGenerations[position] == generation) position = (position + 1) & mask;
        return position;
    }

    private void ensureCapacity(int required) {
        if (required > values.length) values = Arrays.copyOf(values, values.length << 1);
        if (required * 2 < indexKeys.length) return;
        indexKeys = new long[indexKeys.length << 1];
        indexGenerations = new int[indexKeys.length];
        generation = 1;
        for (int index = 0; index < size; index++) {
            int position = emptyIndexPosition(values[index]);
            indexKeys[position] = values[index];
            indexGenerations[position] = generation;
        }
    }

    private static int longHash(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return (int) value;
    }
}
