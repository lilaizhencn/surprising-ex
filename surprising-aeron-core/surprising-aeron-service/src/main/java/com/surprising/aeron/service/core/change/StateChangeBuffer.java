package com.surprising.aeron.service.core.change;

import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 由单个 Owner 或 Lane 持有的命令变化缓冲。键和值按首次插入顺序连续保存，原语索引负责定位，重复键覆盖同一槽；null 是有效删除变化，containsKey 可区分删除与未记录。值默认只是引用，交接和复制由具体缓冲及调用方保证，清空保留容量。
 * @param <V> 业务变化值类型，null 可表示本次删除
 */
public class StateChangeBuffer<V> {
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
    void swapStorage(StateChangeBuffer<V> other) {
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

    /** 在已定位的有效槽替换值，不改变键或重新查询索引。 */
    protected void replaceAt(int slot, V value) {
        if (slot < 0 || slot >= size) throw new IndexOutOfBoundsException(slot);
        values[slot] = value;
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


/**
 * 业务变化与可选预计算事实共用键和槽位的缓冲。第二列按需分配，null 槽表示尚未准备，专用哨兵表示已准备的空值或删除；I 在持仓路径是索引值，在用户前值路径也可保存原权威引用。
 * @param <V> 命令变化的业务值类型
 * @param <I> 同槽可选预计算事实或原权威引用的类型
 */
public static class Indexed<V, I> extends StateChangeBuffer<V> {
    /** 尚未分配预计算列时共用的空数组。 */
    private static final Object[] NO_PREPARED = new Object[0];
    /** 表示预计算列已准备且结果为空的非空哨兵，区别于未准备的 null 槽。 */
    private static final Object DELETED_INDEX = new Object();
    /** 按基础业务槽对齐的可选事实列，不另建第二份键索引。 */
    private Object[] prepared = NO_PREPARED;

    /**
     * 创建可用于接收交换存储的空索引缓冲；需要专用发布槽的子类会覆盖此入口。
     */
    Indexed<V, I> emptyLike() { return new Indexed<>(); }

    /**
     * 在独占交接边界内同时交换基础键值存储和预计算列；子类还需同步交换自己的发布槽数组。
     * @param other 已独占交接的同类型索引缓冲
     */
    void swapWith(Indexed<V, I> other) {
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

    /** 按已知槽更新业务值，同时撤销旧预计算事实。 */
    @Override protected void replaceAt(int slot, V value) {
        super.replaceAt(slot, value);
        if (slot < prepared.length) prepared[slot] = null;
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
     * 要求源目标不同，逐项按目标缓冲的 putIndexed 规则保存，再清源释放租约；是否复制业务值由目标具体实现决定。
     * @param target 已经由调用方持有、且不同于源的目标缓冲
     */
    void drainIndexedTo(Indexed<V, I> target) {
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

/**
 * Owner 按有序提交边界接管 Lane 变化的视图。不同账户分区先保留各自物理缓冲，同 Lane 后续结果或 Owner 直接更新才合并；同一实体只能由所属 Lane 提供，最后更新覆盖旧值。返回值和迭代器都受当前缓冲租约约束，不是永久快照。
 * @param <V> Owner 当前命令观察到的业务变化值类型
 * @param <I> 与业务变化同槽的可选预计算事实类型
 */
public static final class OwnerIndexed<V, I> {
    /** Owner 同步或直接更新使用的具体变化缓冲，专用类型可负责复制冻结借用值。 */
    private final Indexed<V, I> direct;
    /** 按需初始化的 Lane 接管缓冲数组，上限与 long 位图一致。 */
    private final Indexed<V, I>[] lanes;
    /** 当前持有非空 Lane 结果的位集合，只遍历这些分区。 */
    private long laneMask;

    /**
     * 保存 Owner 直接更新使用的具体缓冲，并准备最多六十四条 Lane 的按需接管槽。
     * @param direct Owner 直接写入要使用的具体变化缓冲
     */
    public @SuppressWarnings("unchecked")
    OwnerIndexed(Indexed<V, I> direct) {
        this.direct = direct;
        lanes = new Indexed[Long.SIZE];
    }

    /**
     * 接收已交接的非空 Lane 输出。直接缓冲非空时按其规则合入，否则空目标交换整套存储、已有同 Lane 结果则逐项合并，并登记保留位图。
     * @param laneId 已完成交接或需要读取的账户 Lane 下标
     * @param source Lane 交出的物理变化缓冲，消费后给生产者保留或归还空缓冲
     */
    public void adopt(int laneId, Indexed<V, I> source) {
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
    public Indexed<V, I> directBuffer() { return direct; }
    /**
     * 返回当前持有非空接管缓冲的 Lane 位图。
     */
    public long retainedLaneMask() { return laneMask; }
    /**
     * 按 Lane 下标取得已保留的物理缓冲，尚未初始化可能为 null。
     * @param laneId 已完成交接或需要读取的账户 Lane 下标
     */
    public Indexed<V, I> laneBuffer(int laneId) { return lanes[laneId]; }
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
     * 创建按直接缓冲、再按保留 Lane 顺序读取有效键的迭代器；只保存遍历进度，不复制底层数据，期间不得修改变化视图。
     */
    public org.eclipse.collections.api.iterator.LongIterator longIterator() {
        return /* Owner 当前变化视图的原语键迭代器，只维护当前缓冲、有效下标和剩余 Lane 位图，不复制实体值。 */ new org.eclipse.collections.api.iterator.LongIterator() {
            /** 此迭代器尚未切换到的保留 Lane 位图，创建时从当前视图取值。 */
            long remaining = laneMask;
            /** 迭代器当前读取的直接或 Lane 缓冲引用，不是独立数据副本。 */
            Indexed<V, I> current = direct;
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

/**
 * 命令独占的订单后值缓冲。Lane 可先用 putLive 登记活订单引用，发布完成前必须冻结；冻结槽数组随基础物理缓冲一起交给 Owner，消费结束后才能清空和复用。
 */
public static final class Orders extends Indexed<Order, Void> {
    /** 属于当前物理订单变化缓冲的复用命令槽数组，随 swapWith 一起移动。 */
    private Order[] copies = new Order[8];

    /**
     * 临时保存所属 Lane 的活订单引用，尚不复制；调用方在发布或释放该前缀前完成 freezeValues。
     * @param key 订单编号原语键
     * @param value 本次订单后值，putLive 接受活引用，put 会复制到命令槽，null 表示删除
     */
    public void putLive(long key, Order value) { super.put(key, value); }

    /**
     * 按连续槽复制为本物理缓冲的命令槽值，使随后活订单变化不再影响本回执。
     */
    public void freezeValues() {
        if (size > copies.length) copies = java.util.Arrays.copyOf(copies, Math.max(size, copies.length * 2));
        for (int slot = 0; slot < size; slot++) freezeAt(slot, valueAt(slot));
    }

    /** 已知槽位直接冻结；已属于本槽的后值不再复制自身。 */
    private void freezeAt(int slot, Order value) {
        if (value != null && value != copies[slot])
            value = copies[slot] = value.copyIntoCommandSlot(copies[slot]);
        replaceAt(slot, value);
    }

    /**
     * 定位订单槽并按需增长命令值槽数组，把非空订单复制到当前槽，再保存复制值及清除旧预计算事实。
     * @param key 订单编号原语键
     * @param value 本次订单后值，putLive 接受活引用，put 会复制到命令槽，null 表示删除
     */
    public @Override int put(long key, Order value) {
        // 当前正在冻结、写入或清理的连续订单槽。
        int slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        freezeAt(slot, value);
        return slot;
    }

    /**
     * 创建同样携带订单命令槽的空缓冲，保证交接时保持专用值槽类型。
     */
    @Override Indexed<Order, Void> emptyLike() {
        return new Orders();
    }

    /**
     * 要求对方也是订单专用缓冲，同时交换键值、预计算列和命令槽数组，防止换完存储后清掉另一方仍在使用的值。
     * @param other 已完成独占交接的另一订单专用变化缓冲
     */
    public @Override void swapWith(Indexed<Order, Void> other) {
        if (!(other instanceof Orders /* 类型校验后的另一订单专用缓冲。 */ orders))
            throw new IllegalStateException("order after-images require an order change buffer");
        super.swapWith(other);
        // 交换期间暂存本物理缓冲的命令槽数组。
        Order[] previous = copies; copies = orders.copies; orders.copies = previous;
    }

    /**
     * 清除当前有效命令槽内容后清基础缓冲，保留物理槽对象；调用方须已完成活引用冻结且消费者已经释放租约。
     */
    public @Override void clear() {
        for (/* 当前正在冻结、写入或清理的连续订单槽。 */ int slot = 0, count = Math.min(size, copies.length); slot < count; slot++) if (copies[slot] != null) copies[slot].clearCommandSlot();
        super.clear();
    }
}

/**
 * 命令独占的持仓后值及预计算索引缓冲。写入即复制为当前缓冲的发布槽，交换时连同值槽和索引列一起移动；Owner 消费后才能清理这些可复用值。
 */
public static final class Positions
        extends Indexed<Position, Position.IndexValue> {
    /** 随本物理持仓变化缓冲移动的复用发布槽数组。 */
    private Position[] copies = new Position[8];

    /**
     * 按键定位并复制当前持仓到独立发布槽，清除该槽之前的预计算索引事实；null 表示删除。
     * @param key 运行时持仓键
     * @param value 要复制的持仓后值，null 表示删除
     */
    public @Override int put(long key, Position value) {
        // 当前持仓对应或正在清理的有效连续槽。
        int slot = indexOf(key);
        if (slot < 0) slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        if (value != null) value = copies[slot] = value.copyIntoPublicationSlot(copies[slot]);
        return super.put(key, value);
    }

    /**
     * 创建具有持仓发布槽的空同类缓冲，供物理存储交换使用。
     */
    @Override Indexed<Position, Position.IndexValue> emptyLike() {
        return new Positions();
    }

    /**
     * 只允许与另一持仓专用缓冲交接，基础存储、预计算索引及发布槽数组同步交换。
     * @param other 已经受控交接的另一持仓专用缓冲
     */
    @Override void swapWith(Indexed<Position, Position.IndexValue> other) {
        if (!(other instanceof Positions /* 通过专用类型检查后的另一持仓缓冲。 */ positions))
            throw new IllegalStateException("position after-images require a position change buffer");
        super.swapWith(other);
        // 交换期间暂存当前持仓发布槽数组。
        Position[] previous = copies;
        copies = positions.copies;
        positions.copies = previous;
    }

    /**
     * 清除有效持仓发布槽中的业务引用，再清基础值和索引事实，保留对象供后续命令复用。
     */
    public @Override void clear() {
        for (/* 当前持仓对应或正在清理的有效连续槽。 */ int slot = 0; slot < size; slot++)
            if (copies[slot] != null) copies[slot].clearPublicationSlot();
        super.clear();
    }
}

/**
 * 一个 Lane 输出缓冲独占的预留后值。活引用在发布前复制为复用发布槽，后续预留原位扣减不改旧回执；消费者结束后清空租约内容，保留槽对象。
 */
public static final class Reservations extends StateChangeBuffer<OrderFundsReservation> {
    /** 本预留缓冲独占的可复用发布槽数组，不属于 Lane 权威预留表。 */
    private OrderFundsReservation[] copies = new OrderFundsReservation[8];

    /**
     * 先挂接 Lane 的活预留引用，不立即复制，发布前应冻结后值。
     * @param key 预留对应的订单编号
     * @param value 要挂接或复制的预留，null 表示删除
     */
    public void putLive(long key, OrderFundsReservation value) { super.put(key, value); }

    /**
     * 逐有效项把当前预留复制进本缓冲自己的发布槽，null 删除项仍保留键。
     */
    public void freezeValues() {
        if (size > copies.length) copies = java.util.Arrays.copyOf(copies, Math.max(size, copies.length * 2));
        for (int slot = 0; slot < size; slot++) freezeAt(slot, valueAt(slot));
    }

    /** 已知槽位直接冻结；已属于本槽的后值不再复制自身。 */
    private void freezeAt(int slot, OrderFundsReservation value) {
        if (value != null && value != copies[slot])
            value = copies[slot] = value.copyIntoPublicationSlot(copies[slot]);
        replaceAt(slot, value);
    }

    /**
     * 按键取得连续位置，把非空预留复制进同槽复用发布值，再记录该值。
     * @param key 预留对应的订单编号
     * @param value 要挂接或复制的预留，null 表示删除
     */
    public @Override int put(long key, OrderFundsReservation value) {
        // 当前正在冻结、写入或清理的预留连续槽。
        int slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        freezeAt(slot, value);
        return slot;
    }

    /**
     * 只清理有效前缀与已分配发布槽长度的交集，再清基础引用；未物化的内联活变化也能清理，不访问不存在的发布槽。
     */
    public @Override void clear() {
        // 内联活变化的数量可能超过已物化的发布槽数量，只清理实际存在的槽。
        for (/* 当前正在冻结、写入或清理的预留连续槽。 */ /* 有效变化数与已分配发布槽长度的较小值，避免未冻结的活变化访问数组外位置。 */ int slot = 0, count = Math.min(size, copies.length); slot < count; slot++)
            if (copies[slot] != null) copies[slot].clearPublicationSlot();
        super.clear();
    }
}

/**
 * 命令独占的用户前后值缓冲。每个有效槽复制用户修订和模式到可复用命令槽，不让后续 Lane 原位更新覆盖较早回执；用于前值捕获时，第二列另外保留原权威对象身份。
 */
public static final class Users extends Indexed<com.surprising.aeron.service.account.UserAccount, com.surprising.aeron.service.account.UserAccount> {
    /** 跟随本用户缓冲保存的命令值槽数组，各槽可复用但不能被未完成命令共享。 */
    private com.surprising.aeron.service.account.UserAccount[] copies = new com.surprising.aeron.service.account.UserAccount[8];

    /**
     * 按键定位有效槽，把非空用户复制到该槽自己的命令值，再覆盖业务值列；不会直接把活跃用户引用作为回执值。
     * @param key 用户编号原语键
     * @param value 要复制进本命令槽的用户值，null 表示不存在
     */
    public @Override int put(long key, com.surprising.aeron.service.account.UserAccount value) {
        // 当前用户在连续变化缓冲中的槽位，首次写入先登记空业务值以取得位置。
        int slot = indexOf(key);
        if (slot < 0) slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        if (value != null) value = copies[slot] = value.copyIntoCommandSlot(copies[slot]);
        return super.put(key, value);
    }

    /**
     * 只在本命令首次捕获用户时复制独立前值，并把原权威引用保存在第二列供回滚恢复身份；原用户不存在也保留捕获标记。
     * @param key 用户编号原语键
     * @param authority 首次修改前的原 Lane 权威用户，允许 null 表示原用户不存在
     */
    public void captureBefore(long key, com.surprising.aeron.service.account.UserAccount authority) {
        if (containsKey(key)) return;
        put(key, authority);
        putPrepared(key, authority);
    }

    /**
     * 逐有效命令槽清除用户字段，再清基础值及原权威引用，保留命令槽对象和数组供下一命令使用。
     */
    public @Override void clear() {
        for (/* 当前用户在连续变化缓冲中的槽位，首次写入先登记空业务值以取得位置。 */ int slot = 0; slot < size; slot++) if (copies[slot] != null) copies[slot].clearCommandSlot();
        super.clear();
    }
}
}
