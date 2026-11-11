package com.surprising.aeron.service.core.change;

import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import lombok.Getter;
import lombok.experimental.Accessors;

/** 由单个 Owner 或 Lane 持有的命令变化缓冲。键和值按首次插入顺序连续保存，原语索引负责定位，重复键覆盖同一槽。 */
public class StateChangeBuffer<V> {
    /** 按首次插入顺序保存的原语实体键，只使用零到 size 之前的槽。 */
    private long[] keys = new long[8];
    private Object[] values = new Object[8];
    private long[] indexKeys = new long[16];
    private int[] indexSlots = new int[16];
    private int[] indexGenerations = new int[16];
    private int indexGeneration = 1;
    @Accessors(fluent = true, chain = false)
    @Getter
    int size;

    /** 在所有权交接完成后交换两个缓冲的连续数组、查找索引、代次及有效长度。不逐项复制值，不提供并发互斥。 */
    void swapStorage(StateChangeBuffer<V> other) {
        long[] k = keys; keys = other.keys; other.keys = k;
        Object[] v = values; values = other.values; other.values = v;
        long[] ik = indexKeys; indexKeys = other.indexKeys; other.indexKeys = ik;
        int[] slots = indexSlots; indexSlots = other.indexSlots; other.indexSlots = slots;
        int[] gens = indexGenerations; indexGenerations = other.indexGenerations; other.indexGenerations = gens;
        int gen = indexGeneration; indexGeneration = other.indexGeneration; other.indexGeneration = gen;
        int count = size; size = other.size; other.size = count;
    }

    /** 提前按二次幂准备键值容量与查找索引，只在索引增长时重建当前有效键的位置。 */
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

    private static int longHash(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        return (int) key;
    }

    /** 查到已有键就覆盖其值并返回原槽。 */
    public int put(long key, V value) {
        int mask = indexSlots.length - 1;
        int indexPosition = longHash(key) & mask;
        while (indexGenerations[indexPosition] == indexGeneration) {
            if (indexKeys[indexPosition] == key) {
                int slot = indexSlots[indexPosition];
                values[slot] = value;
                return slot;
            }
            indexPosition = (indexPosition + 1) & mask;
        }
        ensureIndexCapacity(size + 1);
        if (mask != indexSlots.length - 1) indexPosition = emptyIndexPosition(key);
        if (size == keys.length) {
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

    /** 检查当前有效变化数是否为零。 */
    public boolean isEmpty() {
        return size == 0;
    }

    /** 校验有效下标后读取该项原语键。 */
    public long keyAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return keys[index];
    }

    /** 校验有效下标后读取值引用，null 可能表示已记录的删除。 */
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

    /** 按键读取当前值，缺失与已记录 null 都返回 null，需要区分时使用 containsKey。 */
    public @SuppressWarnings("unchecked")
    V get(long key) {
        int slot = indexOf(key);
        return slot < 0 ? null : (V) values[slot];
    }

    /** 检查当前命令是否记录该键，包括值为 null 的删除项。 */
    public boolean containsKey(long key) {
        return indexOf(key) >= 0;
    }

    /** 按连续有效前缀访问键和值引用，保持首次插入顺序，不清空缓冲。 */
    public void forEach(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<V> consumer) {
        for (int index = 0; index < size; index++) {
            // 当前连续槽的业务值引用，null 删除项也必须被消费者看到。
            @SuppressWarnings("unchecked") V value = (V) values[index];
            consumer.value(keys[index], value);
        }
    }

    /** 逐项交给提交消费者，成功消费一项后释放该引用，全部成功后重置索引。回调不能读写本缓冲，途中失败由外层故障与恢复边界处理。 */
    void drainTo(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<V> consumer) {
        for (int index = 0; index < size; index++) {
            // 当前连续槽的业务值引用，null 删除项也必须被消费者看到。
            @SuppressWarnings("unchecked") V value = (V) values[index];
            consumer.value(keys[index], value);
            values[index] = null;
        }
        resetIndex();
    }

    /** 只清空当前有效前缀的值引用并重置索引代次，保留原语键数组和容量，空缓冲不重复清理。 */
    public void clear() {
        if (size == 0) return;
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

    /** 空缓冲直接返回 -1，否则在本代线性探测索引中定位业务键，返回连续槽位。 */
    public int indexOf(long key) {
        if (size == 0) return -1;
        int mask = indexSlots.length - 1;
        int position = longHash(key) & mask;
        while (indexGenerations[position] == indexGeneration) {
            if (indexKeys[position] == key) return indexSlots[position];
            position = (position + 1) & mask;
        }
        return -1;
    }

    /** 从键哈希起点寻找不属于本代的空查找槽。 */
    int emptyIndexPosition(long key) {
        int mask = indexSlots.length - 1;
        int position = longHash(key) & mask;
        while (indexGenerations[position] == indexGeneration) {
            position = (position + 1) & mask;
        }
        return position;
    }

    /** 新增有效项超过索引一半容量时翻倍，只重建当前连续有效键的查找位置。 */
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

/** 业务变化与可选预计算事实共用键和槽位的缓冲。 */
public static class Indexed<V, I> extends StateChangeBuffer<V> {
    private static final Object[] NO_PREPARED = new Object[0];
    private static final Object DELETED_INDEX = new Object();
    private Object[] prepared = NO_PREPARED;

    /** 创建可用于接收交换存储的空索引缓冲。 */
    Indexed<V, I> emptyLike() { return new Indexed<>(); }

    /** 在独占交接边界内同时交换基础键值存储和预计算列。 */
    void swapWith(Indexed<V, I> other) {
        swapStorage(other);
        Object[] values = prepared; prepared = other.prepared; other.prepared = values;
    }

    /** 新增或覆盖业务值后清除同槽旧预计算事实，避免把旧索引结果用于新值。 */
    public @Override int put(long key, V value) {
        int slot = super.put(key, value);
        if (slot < prepared.length) prepared[slot] = null;
        return slot;
    }

    /** 按已知槽更新业务值，同时撤销旧预计算事实。 */
    @Override protected void replaceAt(int slot, V value) {
        super.replaceAt(slot, value);
        if (slot < prepared.length) prepared[slot] = null;
    }

    /** 要求业务变化已存在，再为同槽记录预计算值，null 也标记为已准备。 */
    void putPrepared(long key, I value) {
        int slot = indexOf(key);
        if (slot < 0) throw new IllegalStateException("prepared index has no state change");
        setPrepared(slot, value);
    }

    /** 先按当前子类规则保存业务值并清掉旧预计算列，再根据 hasPrepared 安装这次事实。 */
    public void putIndexed(long key, V value, boolean hasPrepared, I indexValue) {
        int slot = put(key, value);
        if (hasPrepared) setPrepared(slot, indexValue);
    }

    private void setPrepared(int slot, I value) {
        if (slot >= prepared.length) {
            int length = Math.max(8, prepared.length);
            while (length <= slot) length = Math.multiplyExact(length, 2);
            prepared = java.util.Arrays.copyOf(prepared, length);
        }
        prepared[slot] = value == null ? DELETED_INDEX : value;
    }

    /** 查询有效槽是否保存了预计算事实，专用空值哨兵也算已准备。 */
    public boolean hasPreparedAt(int slot) { return slot < prepared.length && prepared[slot] != null; }

    /** 读取有效槽预计算结果，把空值哨兵还原为 null。 */
    public @SuppressWarnings("unchecked") I preparedAt(int slot) {
        Object value = slot < prepared.length ? prepared[slot] : null;
        return value == DELETED_INDEX ? null : (I) value;
    }

    /** 要求源目标不同，逐项按目标缓冲的 putIndexed 规则保存，再清源释放租约。 */
    void drainIndexedTo(Indexed<V, I> target) {
        if (target == this) throw new IllegalArgumentException("change source and target must differ");
        for (int slot = 0; slot < size; slot++)
            target.putIndexed(keyAt(slot), valueAt(slot), hasPreparedAt(slot), preparedAt(slot));
        clear();
    }

    /** 只消费键和值，全部成功后通过动态 clear 释放预计算事实及专用发布槽。 */
    @Override void drainTo(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<V> consumer) {
        forEach(consumer);
        clear();
    }

    /** 清除有效前缀中的预计算引用，再清基础业务值和索引代次，保留各列容量。 */
    public @Override void clear() {
        if (size == 0) return;
        int length = Math.min(size, prepared.length);
        java.util.Arrays.fill(prepared, 0, length, null);
        super.clear();
    }
}

/** Owner 按有序提交边界接管 Lane 变化的视图。不同账户分区先保留各自物理缓冲，同 Lane 后续结果或 Owner 直接更新才合并。同一实体只能由所属 Lane 提供，最后更新覆盖旧值。返回值和迭代器都受当前缓冲租约约束，不是永久快照。 */
public static final class OwnerIndexed<V, I> {
    /** Owner 同步或直接更新使用的具体变化缓冲，专用类型可负责复制冻结借用值。 */
    private final Indexed<V, I> direct;
    private final Indexed<V, I>[] lanes;
    private long laneMask;

    /** 保存 Owner 直接更新使用的具体缓冲，并准备最多六十四条 Lane 的按需接管槽。 */
    public @SuppressWarnings("unchecked")
    OwnerIndexed(Indexed<V, I> direct) {
        this.direct = direct;
        lanes = new Indexed[Long.SIZE];
    }

    /** 接收已交接的非空 Lane 输出。直接缓冲非空时按其规则合入，否则空目标交换整套存储、已有同 Lane 结果则逐项合并，并登记保留位图。 */
    public void adopt(int laneId, Indexed<V, I> source) {
        if (laneId < 0 || laneId >= lanes.length) throw new IllegalArgumentException("invalid change Lane");
        if (source.isEmpty()) return;
        if (!direct.isEmpty()) {
            source.drainIndexedTo(direct);
            return;
        }
        var target = lanes[laneId];
        if (target == null) lanes[laneId] = target = source.emptyLike();
        if (target.isEmpty()) target.swapWith(source);
        else source.drainIndexedTo(target);
        laneMask |= 1L << laneId;
    }

    /** 先按直接缓冲的规则保存新值，再合并保留 Lane 中其他键并释放源租约。专用缓冲在第一步复制借用值，防止源清理破坏新结果。 */
    public void put(long key, V value) {
        // 新值可能借自保留 Lane 的发布槽，先按 direct 的规则保存，再释放源，旧键不得覆盖新值。
        direct.put(key, value);
        consolidateExcept(key);
    }

    private void consolidateExcept(long replacementKey) {
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            var source = lanes[lane];
            for (int slot = 0; slot < source.size(); slot++) {
                long key = source.keyAt(slot);
                if (key != replacementKey)
                    direct.putIndexed(key, source.valueAt(slot), source.hasPreparedAt(slot), source.preparedAt(slot));
            }
            source.clear();
        }
        laneMask = 0;
    }

    /** 先在直接缓冲查找，再按保留 Lane 位图读取。 */
    public V get(long key) {
        int slot = direct.indexOf(key);
        if (slot >= 0) return direct.valueAt(slot);
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            slot = lanes[lane].indexOf(key);
            if (slot >= 0) return lanes[lane].valueAt(slot);
        }
        return null;
    }

    /** 检查直接或保留 Lane 缓冲是否记录此键，null 删除项仍算存在。 */
    public boolean containsKey(long key) {
        if (direct.indexOf(key) >= 0) return true;
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            if (lanes[lane].indexOf(key) >= 0) return true;
        }
        return false;
    }

    /** 只有直接缓冲为空且没有保留 Lane 时才视为空。 */
    public boolean isEmpty() { return laneMask == 0 && direct.isEmpty(); }
    /** 取得 Owner 直接写入的具体缓冲，仍受有序提交和所有权约束。 */
    public Indexed<V, I> directBuffer() { return direct; }
    /** 返回当前持有非空接管缓冲的 Lane 位图。 */
    public long retainedLaneMask() { return laneMask; }
    /** 按 Lane 下标取得已保留的物理缓冲，尚未初始化可能为 null。 */
    public Indexed<V, I> laneBuffer(int laneId) { return lanes[laneId]; }
    /** 按直接缓冲及保留 Lane 的顺序提供业务键和值，遍历期间不能并发改动这些缓冲。 */
    public void forEach(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<V> consumer) {
        direct.forEach(consumer);
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            lanes[lane].forEach(consumer);
        }
    }
    /** 创建按直接缓冲、再按保留 Lane 顺序读取有效键的迭代器。只保存遍历进度，不复制底层数据，期间不得修改变化视图。 */
    public org.eclipse.collections.api.iterator.LongIterator longIterator() {
        return new org.eclipse.collections.api.iterator.LongIterator() {
            long remaining = laneMask;
            Indexed<V, I> current = direct;
            int offset;
            /** 当前缓冲读完时切换到下一个保留 Lane，检查是否还有有效键。 */
            public boolean hasNext() {
                while (offset == current.size() && remaining != 0) {
                    int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
                    current = lanes[lane]; offset = 0;
                }
                return offset < current.size();
            }
            /** 要求还有键后返回当前连续槽的键，并推进下标。 */
            public long next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                return current.keyAt(offset++);
            }
        };
    }
    /** 清空直接缓冲和已置位的 Lane 缓冲，再清保留位图。 */
    public void clear() {
        direct.clear();
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            lanes[lane].clear();
        }
        laneMask = 0;
    }
}

/** 命令独占的订单后值缓冲。Lane 可先用 putLive 登记活订单引用，发布完成前必须冻结。冻结槽数组随基础物理缓冲一起交给 Owner，消费结束后才能清空和复用。 */
public static final class Orders extends Indexed<Order, Void> {
    private Order[] copies = new Order[8];

    /** 临时保存所属 Lane 的活订单引用，尚不复制。 */
    public void putLive(long key, Order value) { super.put(key, value); }

    /** 按连续槽复制为本物理缓冲的命令槽值，使随后活订单变化不再影响本回执。 */
    public void freezeValues() {
        if (size > copies.length) copies = java.util.Arrays.copyOf(copies, Math.max(size, copies.length * 2));
        for (int slot = 0; slot < size; slot++) freezeAt(slot, valueAt(slot));
    }

    private void freezeAt(int slot, Order value) {
        if (value != null && value != copies[slot])
            value = copies[slot] = value.copyIntoCommandSlot(copies[slot]);
        replaceAt(slot, value);
    }

    /** 定位订单槽并按需增长命令值槽数组，把非空订单复制到当前槽，再保存复制值及清除旧预计算事实。 */
    public @Override int put(long key, Order value) {
        int slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        freezeAt(slot, value);
        return slot;
    }

    /** 创建同样携带订单命令槽的空缓冲，保证交接时保持专用值槽类型。 */
    @Override Indexed<Order, Void> emptyLike() {
        return new Orders();
    }

    /** 要求对方也是订单专用缓冲，同时交换键值、预计算列和命令槽数组，防止换完存储后清掉另一方仍在使用的值。 */
    public @Override void swapWith(Indexed<Order, Void> other) {
        if (!(other instanceof Orders orders))
            throw new IllegalStateException("order after-images require an order change buffer");
        super.swapWith(other);
        Order[] previous = copies; copies = orders.copies; orders.copies = previous;
    }

    /** 清除当前有效命令槽内容后清基础缓冲，保留物理槽对象。调用方须已完成活引用冻结且消费者已经释放租约。 */
    public @Override void clear() {
        for (int slot = 0, count = Math.min(size, copies.length); slot < count; slot++) if (copies[slot] != null) copies[slot].clearCommandSlot();
        super.clear();
    }
}

/** 命令独占的持仓后值及预计算索引缓冲。Owner 消费后才能清理这些可复用值。 */
public static final class Positions
        extends Indexed<Position, Position.IndexValue> {
    private Position[] copies = new Position[8];

    /** 按键定位并复制当前持仓到独立发布槽，清除该槽之前的预计算索引事实。 */
    public @Override int put(long key, Position value) {
        int slot = indexOf(key);
        if (slot < 0) slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        if (value != null) value = copies[slot] = value.copyIntoPublicationSlot(copies[slot]);
        return super.put(key, value);
    }

    /** 创建具有持仓发布槽的空同类缓冲，供物理存储交换使用。 */
    @Override Indexed<Position, Position.IndexValue> emptyLike() {
        return new Positions();
    }

    /** 只允许与另一持仓专用缓冲交接，基础存储、预计算索引及发布槽数组同步交换。 */
    @Override void swapWith(Indexed<Position, Position.IndexValue> other) {
        if (!(other instanceof Positions positions))
            throw new IllegalStateException("position after-images require a position change buffer");
        super.swapWith(other);
        Position[] previous = copies;
        copies = positions.copies;
        positions.copies = previous;
    }

    /** 清除有效持仓发布槽中的业务引用，再清基础值和索引事实，保留对象供后续命令复用。 */
    public @Override void clear() {
        for (int slot = 0; slot < size; slot++)
            if (copies[slot] != null) copies[slot].clearPublicationSlot();
        super.clear();
    }
}

/** 一个 Lane 输出缓冲独占的预留后值。消费者结束后清空租约内容，保留槽对象。 */
public static final class Reservations extends StateChangeBuffer<OrderFundsReservation> {
    private OrderFundsReservation[] copies = new OrderFundsReservation[8];

    /** 先挂接 Lane 的活预留引用，不立即复制，发布前应冻结后值。 */
    public void putLive(long key, OrderFundsReservation value) { super.put(key, value); }

    /** 逐有效项把当前预留复制进本缓冲自己的发布槽，null 删除项仍保留键。 */
    public void freezeValues() {
        if (size > copies.length) copies = java.util.Arrays.copyOf(copies, Math.max(size, copies.length * 2));
        for (int slot = 0; slot < size; slot++) freezeAt(slot, valueAt(slot));
    }

    private void freezeAt(int slot, OrderFundsReservation value) {
        if (value != null && value != copies[slot])
            value = copies[slot] = value.copyIntoPublicationSlot(copies[slot]);
        replaceAt(slot, value);
    }

    /** 按键取得连续位置，把非空预留复制进同槽复用发布值，再记录该值。 */
    public @Override int put(long key, OrderFundsReservation value) {
        int slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        freezeAt(slot, value);
        return slot;
    }

    /** 只清理有效前缀与已分配发布槽长度的交集，再清基础引用。 */
    public @Override void clear() {
        for (int slot = 0, count = Math.min(size, copies.length); slot < count; slot++)
            if (copies[slot] != null) copies[slot].clearPublicationSlot();
        super.clear();
    }
}

/** 命令独占的用户前后值缓冲。 */
public static final class Users extends Indexed<com.surprising.aeron.service.account.UserAccount, com.surprising.aeron.service.account.UserAccount> {
    /** 跟随本用户缓冲保存的命令值槽数组，各槽可复用但不能被未完成命令共享。 */
    private com.surprising.aeron.service.account.UserAccount[] copies = new com.surprising.aeron.service.account.UserAccount[8];

    /** 按键定位有效槽，把非空用户复制到该槽自己的命令值，再覆盖业务值列。 */
    public @Override int put(long key, com.surprising.aeron.service.account.UserAccount value) {
        int slot = indexOf(key);
        if (slot < 0) slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        if (value != null) value = copies[slot] = value.copyIntoCommandSlot(copies[slot]);
        return super.put(key, value);
    }

    /** 只在本命令首次捕获用户时复制独立前值，并把原权威引用保存在第二列供回滚恢复身份。 */
    public void captureBefore(long key, com.surprising.aeron.service.account.UserAccount authority) {
        if (containsKey(key)) return;
        put(key, authority);
        putPrepared(key, authority);
    }

    /** 逐有效命令槽清除用户字段，再清基础值及原权威引用，保留命令槽对象和数组供下一命令使用。 */
    public @Override void clear() {
        for (int slot = 0; slot < size; slot++) if (copies[slot] != null) copies[slot].clearCommandSlot();
        super.clear();
    }
}
}
