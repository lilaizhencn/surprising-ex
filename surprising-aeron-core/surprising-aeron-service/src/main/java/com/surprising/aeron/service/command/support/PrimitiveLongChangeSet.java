package com.surprising.aeron.service.command.support;
import com.surprising.aeron.service.command.ImmutableLongArrayList;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 单个业务范围复用的变更 ID 集合：long 数组保留首次出现顺序，开放寻址索引去重，代号清空让旧槽立即失效。它继承 Collection 以接入现有边界，非线程安全，也不是任意删除或并发修改安全的通用 Set。
 */
public final class PrimitiveLongChangeSet extends AbstractCollection<Long> {

    /** 按本代首次出现顺序保存的原始 ID 数组，size 之外为不可读旧值或空槽。 */
    private long[] values;
    /** 开放寻址去重表的键，只有对应代号等于 generation 时有效。 */
    private long[] indexKeys;
    /** 每个索引槽最后登记的代号，不需要每次清掉 indexKeys。 */
    private int[] indexGenerations;
    /** 当前有效代号，从一开始，零保留给空槽，完整绕回零后统一重置。 */
    private int generation = 1;
    /** 本代去重后有效值数，同时为下一次追加位置。 */
    private int size;

    /**
     * 按预期八条建立有序变更集合。
     */
    public PrimitiveLongChangeSet() {
        this(8);
    }

    /**
     * 要求预期大小非负，把值数组容量从八按二倍扩到足够，再给哈希索引两倍槽数；不预装业务值。
     * @param expectedSize 期望变更数量，必须非负，实际值数组至少八槽
     */
    public PrimitiveLongChangeSet(int expectedSize) {
        if (expectedSize < 0) throw new IllegalArgumentException("expected size must not be negative");
        // 从八开始按二倍增长的值数组初始容量
        int valueCapacity = 8;
        while (valueCapacity < expectedSize) valueCapacity = Math.multiplyExact(valueCapacity, 2);
        // 初始哈希槽数，为值容量二倍
        int indexCapacity = valueCapacity << 1;
        values = new long[valueCapacity];
        indexKeys = new long[indexCapacity];
        indexGenerations = new int[indexCapacity];
    }

    /**
     * 先探测当前代号中的重复 ID；新值需要时扩容，只有扩容重建索引后才重新找插入槽，再追加到值数组并登记索引。零、负数和 long 两端均可作 ID。
     * @param value 需要追加、查询或计算哈希的 ID；包装追加拒绝空值
     */
    public boolean add(long value) {
        // 当前索引长度减一，利用二次幂容量环绕探测
        int mask = indexKeys.length - 1;
        // 当前探测、命中或待插入的哈希槽下标
        int position = longHash(value) & mask;
        while (indexGenerations[position] == generation) {
            if (indexKeys[position] == value) return false;
            position = (position + 1) & mask;
        }
        ensureCapacity(size + 1);
        // 首次探测已找到插入槽，只有扩容重建索引后才需要重新定位。
        if (mask != indexKeys.length - 1) position = emptyIndexPosition(value);
        values[size++] = value;
        indexKeys[position] = value;
        indexGenerations[position] = generation;
        return true;
    }

    /**
     * 优先直接读取同类变更集合或 PrimitiveLongView，其他集合再遍历 Long；合并保持各值首次进入本集合的顺序，返回是否新增过元素。
     * @param values 待按原遍历顺序合并的外部集合，可以就是本集合
     */
    @Override
    public boolean addAll(Collection<? extends Long> values) {
        // 批量合并至今是否新增过元素，不因后续重复而清掉真值
        boolean changed = false;
        if (values instanceof PrimitiveLongChangeSet /* 已匹配的变更集合或 primitive 视图，可直接读取 long 而不装箱 */ primitive) {
            for (/* 按首次出现顺序遍历已有值或外部 primitive 输入的下标 */ int index = 0; index < primitive.size; index++) changed |= add(primitive.values[index]);
            return changed;
        }
        if (values instanceof PrimitiveLongView /* 已匹配的变更集合或 primitive 视图，可直接读取 long 而不装箱 */ primitive) {
            for (/* 按首次出现顺序遍历已有值或外部 primitive 输入的下标 */ int index = 0; index < primitive.primitiveSize(); index++) {
                changed |= add(primitive.primitiveValueAt(index));
            }
            return changed;
        }
        for (Long /* 普通集合遍历到的 Long，空值随后由追加入口拒绝 */ value : values) changed |= add(value);
        return changed;
    }

    /**
     * 要求包装值非空，拆箱后走同一套去重追加。
     * @param value 需要追加、查询或计算哈希的 ID；包装追加拒绝空值
     */
    @Override
    public boolean add(Long value) {
        if (value == null) throw new NullPointerException("primitive change id is required");
        return add(value.longValue());
    }

    /**
     * 只有 Long 对象才查索引，其他类型和空值返回假；不按其他 Number 类型数值转换比较。
     * @param value 需要追加、查询或计算哈希的 ID；包装追加拒绝空值
     */
    @Override
    public boolean contains(Object value) {
        return value instanceof Long /* 类型匹配成功的 Long 查询参数，拆箱后查索引 */ id && indexOf(id.longValue()) >= 0;
    }

    /**
     * 只复制当前有效值，得到独立、按首次出现顺序排列的 long 数组。
     */
    public long[] toPrimitiveArray() {
        return Arrays.copyOf(values, size);
    }

    /**
     * 空集合复用空列表，否则复制有效值后把副本交只读列表，集合后续清空或复用不影响它。
     */
    public ImmutableLongArrayList toImmutableList() {
        if (size == 0) return ImmutableLongArrayList.empty();
        return ImmutableLongArrayList.takeOwnership(toPrimitiveArray());
    }

    /**
     * 按当前有效长度检查下标，再取首次出现顺序中的 long 值。
     * @param index 值数组有效段中从零开始的顺序下标
     */
    public long valueAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return values[index];
    }

    /**
     * 创建各自从零开始的装箱迭代器，读取的是原集合当前 size 和 values；不冻结内容，也不提供并发修改检测或删除支持。
     */
    @Override
    public Iterator<Long> iterator() {
        return /* 本次调用独占顺序下标的轻量迭代器，借用外部集合的当前数组与长度，不是快照。 */ new Iterator<>() {
            /** 当前匿名迭代器独占的顺序下标，不是哈希槽号。 */
            private int index;

            /**
             * 按本迭代器下标与集合当前有效长度判断是否还有值。
             */
            @Override
            public boolean hasNext() { return index < size; }

            /**
             * 返回当前下标的值并向后移动；已达到当前有效长度时抛耗尽异常。
             */
            @Override
            public Long next() {
                if (index >= size) throw new NoSuchElementException();
                return values[index++];
            }
        };
    }

    /**
     * 返回本代已经去重的变更 ID 数量。
     */
    @Override
    public int size() {
        return size;
    }

    /**
     * 归零有效长度并换代，让原索引槽失效而不逐个清键；代号绕回零时才清所有代号槽并从一重新开始。
     */
    @Override
    public void clear() {
        size = 0;
        if (++generation == 0) {
            Arrays.fill(indexGenerations, 0);
            generation = 1;
        }
    }

    /**
     * 按当前代号线性探测，匹配返回索引槽号，遇到非本代槽则未命中并返回负一。
     * @param value 需要追加、查询或计算哈希的 ID；包装追加拒绝空值
     */
    private int indexOf(long value) {
        // 当前索引长度减一，利用二次幂容量环绕探测
        int mask = indexKeys.length - 1;
        // 当前探测、命中或待插入的哈希槽下标
        int position = longHash(value) & mask;
        while (indexGenerations[position] == generation) {
            if (indexKeys[position] == value) return position;
            position = (position + 1) & mask;
        }
        return -1;
    }

    /**
     * 按当前索引掩码线性探测，返回第一个不属于本代的空槽；调用方已处理重复值。
     * @param value 需要追加、查询或计算哈希的 ID；包装追加拒绝空值
     */
    private int emptyIndexPosition(long value) {
        // 当前索引长度减一，利用二次幂容量环绕探测
        int mask = indexKeys.length - 1;
        // 当前探测、命中或待插入的哈希槽下标
        int position = longHash(value) & mask;
        while (indexGenerations[position] == generation) position = (position + 1) & mask;
        return position;
    }

    /**
     * 值数组按需二倍扩容；索引占用将达到一半时二倍重建，把已有有效值重新登记，并把代号重置为一。大容量运算沿用原 int 及数组限制。
     * @param required 本次新增后需要容纳的有效值总数
     */
    private void ensureCapacity(int required) {
        if (required > values.length) values = Arrays.copyOf(values, values.length << 1);
        if (required * 2 < indexKeys.length) return;
        indexKeys = new long[indexKeys.length << 1];
        indexGenerations = new int[indexKeys.length];
        generation = 1;
        for (/* 按首次出现顺序遍历已有值或外部 primitive 输入的下标 */ int index = 0; index < size; index++) {
            // 当前探测、命中或待插入的哈希槽下标
            int position = emptyIndexPosition(values[index]);
            indexKeys[position] = values[index];
            indexGenerations[position] = generation;
        }
    }

    /**
     * 用固定的移位、异或及乘法把 long 混合成 int 哈希；乘法环绕是算法本身，不改变原 ID。
     * @param value 需要追加、查询或计算哈希的 ID；包装追加拒绝空值
     */
    private static int longHash(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return (int) value;
    }
}
