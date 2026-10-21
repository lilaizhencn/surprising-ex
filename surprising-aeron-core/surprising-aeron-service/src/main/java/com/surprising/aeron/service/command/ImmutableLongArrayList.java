package com.surprising.aeron.service.command;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.RandomAccess;
import java.util.function.LongConsumer;

/**
 * 用 long 数组保存只读列表，在命令和分页等边界兼容 List<Long>，内部可通过 primitive 视图避免遍历时装箱。只读成立的前提是移交数组后原调用方不再修改；它不会为 takeOwnership 自动复制数组。
 */
public final class ImmutableLongArrayList extends AbstractList<Long>
        implements RandomAccess, com.surprising.aeron.service.command.support.PrimitiveLongView {

    /** 所有空结果共用的零长度数组列表。 */
    private static final ImmutableLongArrayList EMPTY = new ImmutableLongArrayList(new long[0]);
    /** 已接管且不得再被外部修改的精确长度数组。 */
    private final long[] values;

    /**
     * 直接接管传入数组作为只读列表底层，不复制；外层负责保证后续不再改动它。
     * @param values 要接管的原 long 数组，移交后调用方不得再写
     */
    private ImmutableLongArrayList(long[] values) {
        this.values = values;
    }

    /**
     * 空数组或空引用返回共享空列表，非空则直接接管数组；调用方从此不得再写该数组。
     * @param values 要接管的原 long 数组，移交后调用方不得再写
     */
    public static ImmutableLongArrayList takeOwnership(long[] values) {
        if (values == null || values.length == 0) return EMPTY;
        return new ImmutableLongArrayList(values);
    }

    /**
     * 取得可安全共用的空列表。
     */
    public static ImmutableLongArrayList empty() { return EMPTY; }

    /**
     * 短期命令页或结果的只追加构建器，保存 long 数组及有效长度；冻结时确保之后追加不会改写已交出的结果。
     */
    public static final class Builder {
        /** 构建器当前可追加数组，满时复制扩容。 */
        private long[] values;
        /** 构建器已追加条数，范围不超过当前数组容量。 */
        private int size;

        /**
         * 以至少一个槽建立只追加构建器，负数或零初始容量也取一，不在此拒绝。
         * @param initialCapacity 构建器期望初始槽数，至少分配一个槽
         */
        public Builder(int initialCapacity) {
            values = new long[Math.max(1, initialCapacity)];
        }

        /**
         * 在有效尾部追加一个 long，容量满时按二倍复制扩容；本构建器不提供原位修改已追加值的入口。
         * @param value 要追加或查找的值；Object 重载只把 Long 当作可匹配值
         */
        public void add(long value) {
            if (size == values.length) values = Arrays.copyOf(values, values.length << 1);
            values[size++] = value;
        }

        /**
         * 返回当前已追加条数，不是底层容量。
         */
        public int size() { return size; }

        /**
         * 返回最后追加的值，尚无元素时报错。
         */
        public long last() {
            if (size == 0) throw new IllegalStateException("empty primitive list");
            return values[size - 1];
        }

        /**
         * 空结果复用共享空列表；已填满时交出原数组，否则只复制有效段。之后仍可继续追加：满数组会先扩容，未满时冻结结果已有副本。
         */
        public ImmutableLongArrayList freeze() {
            if (size == 0) return EMPTY;
            return takeOwnership(size == values.length ? values : Arrays.copyOf(values, size));
        }
    }

    /**
     * 按数组下标直接读 long，越界沿用数组异常。
     * @param index 从零开始的数组下标
     */
    public long valueAt(int index) {
        return values[index];
    }

    /**
     * 读取 primitive 视图长度，与列表大小相同。
     */
    @Override public int primitiveSize() { return values.length; }
    /**
     * 按下标直接读取 primitive 值，越界沿用数组异常。
     * @param index 从零开始的数组下标
     */
    @Override public long primitiveValueAt(int index) { return values[index]; }

    /**
     * 逐项比较 primitive long 是否存在，找到立即返回，不创建包装值。
     * @param value 要追加或查找的值；Object 重载只把 Long 当作可匹配值
     */
    public boolean containsLong(long value) {
        for (long /* 原数组中当前待比较的 long */ candidate : values) if (candidate == value) return true;
        return false;
    }

    /**
     * 复制底层数组，调用方可修改返回副本而不影响本列表。
     */
    public long[] toPrimitiveArray() {
        return values.clone();
    }

    /**
     * 按原数组顺序把 long 交给回调，不先装箱为 Long；回调异常原样传播。
     * @param consumer 按原顺序接收每个 long 的回调
     */
    public void forEachLong(LongConsumer consumer) {
        for (long /* 当前遍历到、要交回调或验证包含关系的元素 */ value : values) consumer.accept(value);
    }

    /**
     * 为 List 契约按下标取值并装箱为 Long，越界沿用数组异常。
     * @param index 从零开始的数组下标
     */
    @Override
    public Long get(int index) {
        return values[index];
    }

    /**
     * 返回列表元素数，底层数组没有未用尾段。
     */
    @Override
    public int size() {
        return values.length;
    }

    /**
     * 只接受 Long 类型对象，拆箱后线性查找；空值及其他 Number 类型返回假。
     * @param value 要追加或查找的值；Object 重载只把 Long 当作可匹配值
     */
    @Override
    public boolean contains(Object value) {
        if (!(value instanceof Long /* 类型匹配成功的 Long 包装值，之后拆箱 */ number)) return false;
        // 从 Long 参数拆出的目标 primitive 值
        long expected = number;
        for (long /* 原数组中当前待比较的 long */ candidate : values) if (candidate == expected) return true;
        return false;
    }

    /**
     * 创建新的 Long 数组并逐项装箱，按 Object 数组契约交出，不暴露原 long 数组。
     */
    @Override
    public Object[] toArray() {
        // 本次独立创建的 Long 结果数组
        Long[] boxed = new Long[values.length];
        for (/* 装箱复制时的数组下标 */ int index = 0; index < values.length; index++) boxed[index] = values[index];
        return boxed;
    }

    /**
     * 沿用 AbstractCollection 的带类型数组转换规则，按目标数组长度和组件类型写入，仍需装箱。
     * @param <T> 目标数组的组件类型，元素仍按 Long 装箱
     * @param destination 按集合契约复用或重新分配的目标类型数组
     */
    @Override
    public <T> T[] toArray(T[] destination) {
        return super.toArray(destination);
    }

    /**
     * 逐个用本列表的 Long 类型相等规则检查外部集合，任一元素不存在就返回假，空集合为真。
     * @param collection 需要逐项验证是否全部存在的外部集合
     */
    @Override
    public boolean containsAll(Collection<?> collection) {
        for (Object /* 当前遍历到、要交回调或验证包含关系的元素 */ value : collection) if (!contains(value)) return false;
        return true;
    }
}
