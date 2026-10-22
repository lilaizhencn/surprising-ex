package com.surprising.aeron.service.core.change;

/**
 * Owner 按有序提交边界接管 Lane 变化的视图。不同账户分区先保留各自物理缓冲，同 Lane 后续结果或 Owner 直接更新才合并；同一实体只能由所属 Lane 提供，最后更新覆盖旧值。返回值和迭代器都受当前缓冲租约约束，不是永久快照。
 * @param <V> Owner 当前命令观察到的业务变化值类型
 * @param <I> 与业务变化同槽的可选预计算事实类型
 */
public final class OwnerIndexedChanges<V, I> {
    /** Owner 同步或直接更新使用的具体变化缓冲，专用类型可负责复制冻结借用值。 */
    private final RuntimeIndexedChangeBuffer<V, I> direct;
    /** 按需初始化的 Lane 接管缓冲数组，上限与 long 位图一致。 */
    private final RuntimeIndexedChangeBuffer<V, I>[] lanes;
    /** 当前持有非空 Lane 结果的位集合，只遍历这些分区。 */
    private long laneMask;

    /**
     * 创建使用普通索引缓冲的 Owner 变化视图；需要可复用发布值时调用方传专用缓冲。
     */
    public OwnerIndexedChanges() { this(new RuntimeIndexedChangeBuffer<>()); }

    /**
     * 保存 Owner 直接更新使用的具体缓冲，并准备最多六十四条 Lane 的按需接管槽。
     * @param direct Owner 直接写入要使用的具体变化缓冲
     */
    public @SuppressWarnings("unchecked")
    OwnerIndexedChanges(RuntimeIndexedChangeBuffer<V, I> direct) {
        this.direct = direct;
        lanes = new RuntimeIndexedChangeBuffer[Long.SIZE];
    }

    /**
     * 接收已交接的非空 Lane 输出。直接缓冲非空时按其规则合入，否则空目标交换整套存储、已有同 Lane 结果则逐项合并，并登记保留位图。
     * @param laneId 已完成交接或需要读取的账户 Lane 下标
     * @param source Lane 交出的物理变化缓冲，消费后给生产者保留或归还空缓冲
     */
    public void adopt(int laneId, RuntimeIndexedChangeBuffer<V, I> source) {
        if (laneId < 0 || laneId >= lanes.length) throw new IllegalArgumentException("invalid change Lane");
        if (source.isEmpty()) return;
        if (!direct.isEmpty()) {
            source.drainIndexedTo(direct);
            return;
        }
        // 本 Lane 已保留或刚创建的同类型接管缓冲，空时通过交换存储交接。
        var target = lanes[laneId];
        if (target == null) lanes[laneId] = target = source.emptyLike();
        if (target.isEmpty()) target.swapWith(source);
        else source.drainIndexedTo(target);
        laneMask |= 1L << laneId;
    }

    /**
     * 先按直接缓冲的规则保存新值，再合并保留 Lane 中其他键并释放源租约；专用缓冲在第一步复制借用值，防止源清理破坏新结果。
     * @param key 当前变化的原语业务键
     * @param value 要按直接缓冲规则保存的新业务值，null 表示删除
     */
    public void put(long key, V value) {
        // 新值可能借自保留 Lane 的发布槽，先按 direct 的规则保存，再释放源，旧键不得覆盖新值。
        direct.put(key, value);
        consolidateExcept(key);
    }

    /**
     * 只遍历当前保留的 Lane，跳过已被直接更新的新键，把其他业务值及预计算事实合入直接缓冲，清空源并重置保留位图。
     * @param replacementKey 已经先写入直接缓冲的新键，合并旧 Lane 数据时不得覆盖它
     */
    private void consolidateExcept(long replacementKey) {
        // 还要处理的保留 Lane 位图，每次清除最低有效位。
        long remaining = laneMask;
        while (remaining != 0) {
            // 当前位图选出的 Lane 编号。
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            // 当前正在被合并及清理的保留 Lane 缓冲。
            var source = lanes[lane];
            for (/* 当前连续业务变化的下标。 */ int slot = 0; slot < source.size(); slot++) {
                // 当前合并的业务键，需要跳过本次直接替换的键。
                long key = source.keyAt(slot);
                if (key != replacementKey)
                    direct.putIndexed(key, source.valueAt(slot), source.hasPreparedAt(slot), source.preparedAt(slot));
            }
            source.clear();
        }
        laneMask = 0;
    }

    /**
     * 先在直接缓冲查找，再按保留 Lane 位图读取；记录为 null 的删除会阻止继续查旧结果，未找到也返回 null。
     * @param key 当前变化的原语业务键
     */
    public V get(long key) {
        // 当前连续业务变化的下标。
        int slot = direct.indexOf(key);
        if (slot >= 0) return direct.valueAt(slot);
        // 还要处理的保留 Lane 位图，每次清除最低有效位。
        long remaining = laneMask;
        while (remaining != 0) {
            // 当前位图选出的 Lane 编号。
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            slot = lanes[lane].indexOf(key);
            if (slot >= 0) return lanes[lane].valueAt(slot);
        }
        return null;
    }

    /**
     * 检查直接或保留 Lane 缓冲是否记录此键，null 删除项仍算存在。
     * @param key 当前变化的原语业务键
     */
    public boolean containsKey(long key) {
        if (direct.indexOf(key) >= 0) return true;
        // 还要处理的保留 Lane 位图，每次清除最低有效位。
        long remaining = laneMask;
        while (remaining != 0) {
            // 当前位图选出的 Lane 编号。
            int lane = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            if (lanes[lane].indexOf(key) >= 0) return true;
        }
        return false;
    }

    /**
     * 只有直接缓冲为空且没有保留 Lane 时才视为空。
     */
    public boolean isEmpty() { return laneMask == 0 && direct.isEmpty(); }
    /**
     * 取得 Owner 直接写入的具体缓冲，仍受有序提交和所有权约束；不会自动执行 put 的保留结果合并。
     */
    public RuntimeIndexedChangeBuffer<V, I> directBuffer() { return direct; }
    /**
     * 返回当前持有非空接管缓冲的 Lane 位图。
     */
    public long retainedLaneMask() { return laneMask; }
    /**
     * 按 Lane 下标取得已保留的物理缓冲，尚未初始化可能为 null。
     * @param laneId 已完成交接或需要读取的账户 Lane 下标
     */
    public RuntimeIndexedChangeBuffer<V, I> laneBuffer(int laneId) { return lanes[laneId]; }
    /**
     * 先遍历直接变化，再按 Lane 编号顺序遍历保留变化，向内部消费者提供预计算事实，不自动清空。
     * @param consumer 在 Owner 当前读取或提交边界内消费变化的回调
     */
    void forEachIndexed(RuntimeIndexedChangeBuffer.Consumer<V, I> consumer) {
        direct.forEachIndexed(consumer);
        // 还要处理的保留 Lane 位图，每次清除最低有效位。
        long remaining = laneMask;
        while (remaining != 0) {
            // 当前位图选出的 Lane 编号。
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            lanes[lane].forEachIndexed(consumer);
        }
    }
    /**
     * 按直接缓冲及保留 Lane 的顺序提供业务键和值，遍历期间不能并发改动这些缓冲。
     * @param consumer 在 Owner 当前读取或提交边界内消费变化的回调
     */
    public void forEach(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<V> consumer) {
        direct.forEach(consumer);
        // 还要处理的保留 Lane 位图，每次清除最低有效位。
        long remaining = laneMask;
        while (remaining != 0) {
            // 当前位图选出的 Lane 编号。
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            lanes[lane].forEach(consumer);
        }
    }
    /**
     * 消费直接缓冲及各保留 Lane 的值，交由具体缓冲释放其引用和发布槽，全部成功后把保留位图清零；不提供异常原子回滚。
     * @param consumer 在 Owner 当前读取或提交边界内消费变化的回调
     */
    void drainTo(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<V> consumer) {
        direct.drainTo(consumer);
        // 还要处理的保留 Lane 位图，每次清除最低有效位。
        long remaining = laneMask;
        while (remaining != 0) {
            // 当前位图选出的 Lane 编号。
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            lanes[lane].drainTo(consumer);
        }
        laneMask = 0;
    }
    /**
     * 创建按直接缓冲、再按保留 Lane 顺序读取有效键的迭代器；只保存遍历进度，不复制底层数据，期间不得修改变化视图。
     */
    public org.eclipse.collections.api.iterator.LongIterator longIterator() {
        return /* Owner 当前变化视图的原语键迭代器，只维护当前缓冲、有效下标和剩余 Lane 位图，不复制实体值。 */ new org.eclipse.collections.api.iterator.LongIterator() {
            /** 此迭代器尚未切换到的保留 Lane 位图，创建时从当前视图取值。 */
            long remaining = laneMask;
            /** 迭代器当前读取的直接或 Lane 缓冲引用，不是独立数据副本。 */
            RuntimeIndexedChangeBuffer<V, I> current = direct;
            /** 迭代器在当前缓冲有效前缀中的下一个下标。 */
            int offset;
            /**
             * 当前缓冲读完时切换到下一个保留 Lane，检查是否还有有效键。
             */
            public boolean hasNext() {
                while (offset == current.size() && remaining != 0) {
                    // 当前位图选出的 Lane 编号。
                    int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
                    current = lanes[lane]; offset = 0;
                }
                return offset < current.size();
            }
            /**
             * 要求还有键后返回当前连续槽的键，并推进下标；耗尽时抛出 NoSuchElementException。
             */
            public long next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                return current.keyAt(offset++);
            }
        };
    }
    /**
     * 在导出边界合计有效变化数并复制当前键序列，不把源缓冲的后续变化暴露到数组中。
     */
    public long[] toArray() {
        // 直接和所有保留 Lane 的有效变化总数。
        int size = direct.size();
        // 还要处理的保留 Lane 位图，每次清除最低有效位。
        long remaining = laneMask;
        while (remaining != 0) {
            // 当前位图选出的 Lane 编号。
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            size += lanes[lane].size();
        }
        // 导出边界新建的独立原语键数组。
        long[] result = new long[size];
        // 按当前变化视图读取键序列的临时迭代器。
        var iterator = longIterator();
        for (/* 当前复制到导出数组的下标。 */ int i = 0; i < size; i++) result[i] = iterator.next();
        return result;
    }
    /**
     * 清空直接缓冲和已置位的 Lane 缓冲，再清保留位图；容器与发布槽容量留待下一命令复用。
     */
    public void clear() {
        direct.clear();
        // 还要处理的保留 Lane 位图，每次清除最低有效位。
        long remaining = laneMask;
        while (remaining != 0) {
            // 当前位图选出的 Lane 编号。
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            lanes[lane].clear();
        }
        laneMask = 0;
    }
}
