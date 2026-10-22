package com.surprising.aeron.service.core.change;

/**
 * 业务变化与可选预计算事实共用键和槽位的缓冲。第二列按需分配，null 槽表示尚未准备，专用哨兵表示已准备的空值或删除；I 在持仓路径是索引值，在用户前值路径也可保存原权威引用。
 * @param <V> 命令变化的业务值类型
 * @param <I> 同槽可选预计算事实或原权威引用的类型
 */
public class RuntimeIndexedChangeBuffer<V, I> extends RuntimeChangeBuffer<V> {
    /** 尚未分配预计算列时共用的空数组。 */
    private static final Object[] NO_PREPARED = new Object[0];
    /** 表示预计算列已准备且结果为空的非空哨兵，区别于未准备的 null 槽。 */
    private static final Object DELETED_INDEX = new Object();
    /** 按基础业务槽对齐的可选事实列，不另建第二份键索引。 */
    private Object[] prepared = NO_PREPARED;

    /**
     * 消费同槽业务变化及可选预计算事实的内部回调，标记可区分未准备与已准备删除。
     * @param <V> 命令变化的业务值类型
     * @param <I> 同槽可选预计算事实或原权威引用的类型
     */
    @FunctionalInterface
    interface Consumer<V, I> {
        /**
         * 消费一个业务变化及其预计算列；hasPrepared 区分未准备和已经准备的 null。
         * @param key 业务变化的原语 long 键
         * @param value 本次消费的业务后值，null 表示删除或调用方定义的空值
         * @param hasPrepared 这次变化是否携带已准备的第二列，true 时 null 仍有意义
         * @param indexValue 这次已准备的索引结果，null 可明确表示删除
         */
        void accept(long key, V value, boolean hasPrepared, I indexValue);
    }

    /**
     * 创建可用于接收交换存储的空索引缓冲；需要专用发布槽的子类会覆盖此入口。
     */
    RuntimeIndexedChangeBuffer<V, I> emptyLike() { return new RuntimeIndexedChangeBuffer<>(); }

    /**
     * 在独占交接边界内同时交换基础键值存储和预计算列；子类还需同步交换自己的发布槽数组。
     * @param other 已独占交接的同类型索引缓冲
     */
    void swapWith(RuntimeIndexedChangeBuffer<V, I> other) {
        swapStorage(other);
        // 交换预计算列时暂存当前数组，不是基础业务值列。
        Object[] values = prepared; prepared = other.prepared; other.prepared = values;
    }

    /**
     * 新增或覆盖业务值后清除同槽旧预计算事实，避免把旧索引结果用于新值。
     * @param key 业务变化的原语 long 键
     * @param value 要存入的业务值或预计算值，具体由当前入口参数类型确定
     */
    public @Override int put(long key, V value) {
        // 当前业务键对应或正在遍历的有效连续槽。
        int slot = super.put(key, value);
        if (slot < prepared.length) prepared[slot] = null;
        return slot;
    }

    /**
     * 要求业务变化已存在，再为同槽记录预计算值，null 也标记为已准备。
     * @param key 业务变化的原语 long 键
     * @param value 要存入的业务值或预计算值，具体由当前入口参数类型确定
     */
    void putPrepared(long key, I value) {
        // 当前业务键对应或正在遍历的有效连续槽。
        int slot = indexOf(key);
        if (slot < 0) throw new IllegalStateException("prepared index has no state change");
        setPrepared(slot, value);
    }

    /**
     * 先按当前子类规则保存业务值并清掉旧预计算列，再根据 hasPrepared 安装这次事实。
     * @param key 业务变化的原语 long 键
     * @param value 要存入的业务值或预计算值，具体由当前入口参数类型确定
     * @param hasPrepared 这次变化是否携带已准备的第二列，true 时 null 仍有意义
     * @param indexValue 这次已准备的索引结果，null 可明确表示删除
     */
    public void putIndexed(long key, V value, boolean hasPrepared, I indexValue) {
        // 当前业务键对应或正在遍历的有效连续槽。
        int slot = put(key, value);
        if (hasPrepared) setPrepared(slot, indexValue);
    }

    /**
     * 按需增长第二列，把 null 转成专用已准备空值哨兵，保留与未准备状态的区别。
     * @param slot 基础缓冲中有效连续下标
     * @param value 要存入的业务值或预计算值，具体由当前入口参数类型确定
     */
    private void setPrepared(int slot, I value) {
        if (slot >= prepared.length) {
            // 扩容第二列或清空有效前缀时需要处理的长度。
            int length = Math.max(8, prepared.length);
            while (length <= slot) length = Math.multiplyExact(length, 2);
            prepared = java.util.Arrays.copyOf(prepared, length);
        }
        prepared[slot] = value == null ? DELETED_INDEX : value;
    }

    /**
     * 按当前有效槽遍历业务值、是否已准备以及还原后的预计算值，不清理缓冲。
     * @param consumer 在当前读取边界内消费业务值及预计算事实的回调
     */
    void forEachIndexed(Consumer<V, I> consumer) {
        for (/* 当前业务键对应或正在遍历的有效连续槽。 */ int slot = 0; slot < size; slot++) {
            // 预计算列槽的原始引用，null 未准备，哨兵表示已准备空值。
            Object stored = slot < prepared.length ? prepared[slot] : null;
            // 当前槽是否明确保存了预计算事实。
            boolean hasPrepared = stored != null;
            // 把专用空值哨兵还原后的预计算结果。
            @SuppressWarnings("unchecked") I indexValue = stored == DELETED_INDEX ? null : (I) stored;
            consumer.accept(keyAt(slot), valueAt(slot), hasPrepared, indexValue);
        }
    }

    /**
     * 查询有效槽是否保存了预计算事实，专用空值哨兵也算已准备；调用方负责下标有效。
     * @param slot 基础缓冲中有效连续下标
     */
    public boolean hasPreparedAt(int slot) { return slot < prepared.length && prepared[slot] != null; }

    /**
     * 读取有效槽预计算结果，把空值哨兵还原为 null；需配合 hasPreparedAt 区分未准备。
     * @param slot 基础缓冲中有效连续下标
     */
    public @SuppressWarnings("unchecked") I preparedAt(int slot) {
        // 当前槽的预计算原始引用，可能是空值哨兵。
        Object value = slot < prepared.length ? prepared[slot] : null;
        return value == DELETED_INDEX ? null : (I) value;
    }

    /**
     * 把全部业务变化与预计算列交给消费者，全部成功后清空；中途异常不在此回滚消费者已发生的变化。
     * @param consumer 在当前读取边界内消费业务值及预计算事实的回调
     */
    void drainIndexedTo(Consumer<V, I> consumer) {
        forEachIndexed(consumer);
        clear();
    }

    /**
     * 要求源目标不同，逐项按目标缓冲的 putIndexed 规则保存，再清源释放租约；是否复制业务值由目标具体实现决定。
     * @param target 已经由调用方持有、且不同于源的目标缓冲
     */
    void drainIndexedTo(RuntimeIndexedChangeBuffer<V, I> target) {
        if (target == this) throw new IllegalArgumentException("change source and target must differ");
        for (/* 当前业务键对应或正在遍历的有效连续槽。 */ int slot = 0; slot < size; slot++)
            target.putIndexed(keyAt(slot), valueAt(slot), hasPreparedAt(slot), preparedAt(slot));
        clear();
    }

    /**
     * 只消费键和值，全部成功后通过动态 clear 释放预计算事实及专用发布槽。
     * @param consumer 在当前读取边界内消费业务值及预计算事实的回调
     */
    @Override void drainTo(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<V> consumer) {
        forEach(consumer);
        // 复用前还要清理预计算事实，并调用子类清空专用发布槽。
        clear();
    }

    /**
     * 清除有效前缀中的预计算引用，再清基础业务值和索引代次，保留各列容量。
     */
    public @Override void clear() {
        if (size == 0) return;
        // 扩容第二列或清空有效前缀时需要处理的长度。
        int length = Math.min(size, prepared.length);
        java.util.Arrays.fill(prepared, 0, length, null);
        super.clear();
    }
}
