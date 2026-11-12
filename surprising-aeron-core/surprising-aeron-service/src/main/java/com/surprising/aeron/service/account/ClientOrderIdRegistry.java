package com.surprising.aeron.service.account;

import com.surprising.aeron.service.core.identity.IdIndex;
import java.util.Arrays;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.api.block.procedure.primitive.LongProcedure;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/** 一个账户分片的客户订单身份行表、正向客户订单映射及订单到客户别名的反向索引。跨线程监控只读发布数量，不直接读取行表。 */
public final class ClientOrderIdRegistry {
    @lombok.NonNull
    private final AccountPartition lane;
    final LongObjectHashMap<Long2LongHashMap> clientOrderIndex;
    @Accessors(fluent = true, chain = false)
    @Getter
    final OrderKeyIndex clientKeysByOrderId = new OrderKeyIndex();
    private final Long2LongHashMap rowsByKey = new Long2LongHashMap(16, 0.65f, 0);
    /** 按行保存的确定性客户身份键，空行置零。 */
    private long[] keys = new long[16];
    private long[] users = new long[16];
    private long[] references = new long[16];
    /** 按行保存的客户号文本，最后一个引用释放时清空，避免回收行继续持有文本。 */
    private String[] names = new String[16];
    private int[] freeRows = new int[16];
    private int freeCount;
    private int nextRow;
    /** 监控可见的存活身份行数，Owner 只观察此计数，不读取原语表和身份行。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private volatile int publishedCount;
    private IdIndex releasingClientIdentities;
    private long releasingClientUser;
    /** 常驻的客户别名释放回调，避免每个终态订单新建捕获对象。 */
    private final LongProcedure releaseClientIdentity = this::releaseClientIdentity;
    private Long2LongHashMap unlinkingClientOrders;
    private long unlinkingOrderId;
    private final LongProcedure unlinkClientOrder = this::unlinkClientOrder;

    public ClientOrderIdRegistry(AccountPartition lane) {
        this(lane, 16);
    }

    public ClientOrderIdRegistry(AccountPartition lane, int initialEntityCapacity) {
        this.lane = java.util.Objects.requireNonNull(lane, "lane");
        this.clientOrderIndex = new LongObjectHashMap<>(initialEntityCapacity);
    }

    /** 由所属账户分片校验或认领当前线程。 */
    private void assertOwner() { lane.assertOwner(); }

    /** 已有键先核对用户和文本，retain 为 true 才增加已有引用。 */
    public long prepare(long key, long userId, String name, boolean retain) {
        assertOwner();
        int row = row(key);
        if (row >= 0) {
            requireIdentity(row, userId, name);
            if (retain) references[row] = Math.incrementExact(references[row]);
            return key;
        }
        insert(key, userId, name, 1);
        return -key;
    }

    /** 只在键、用户与客户号文本全部匹配时返回正键。 */
    public long find(long key, long userId, String name) {
        assertOwner();
        int row = row(key);
        return row >= 0 && users[row] == userId && names[row].equals(name) ? key : 0;
    }

    /** 要求身份键存在且属于指定用户后返回客户号文本，不允许借其他用户的键读取文本。 */
    public String name(long key, long userId) {
        assertOwner();
        int row = row(key);
        if (row < 0 || users[row] != userId)
            throw new IllegalArgumentException("unknown runtime client key: " + userId + '/' + key);
        return names[row];
    }

    /** 要求键及完整身份匹配后撤回一个引用。 */
    public void rollback(long key, long userId, String name) {
        assertOwner();
        int row = row(key);
        if (row < 0) throw new IllegalStateException("deterministic client identity collision");
        requireIdentity(row, userId, name);
        releaseRow(key, row);
    }

    /** 键存在且归属用户匹配时释放一个引用。 */
    public void release(long key, long userId) {
        assertOwner();
        int row = row(key);
        if (row >= 0 && users[row] == userId) releaseRow(key, row);
    }

    /** 借用身份字典及用户编号，遍历该订单的全部客户别名并释放引用。 */
    public void releaseOrderClientIdentities(IdIndex identities, long orderId, long userId) {
        assertOwner();
        if (releasingClientIdentities != null)
            throw new IllegalStateException("nested terminal client identity release");
        releasingClientIdentities = java.util.Objects.requireNonNull(identities);
        releasingClientUser = userId;
        try { clientKeysByOrderId.forEach(orderId, releaseClientIdentity); }
        finally { releasingClientIdentities = null; releasingClientUser = 0; }
    }

    private void releaseClientIdentity(long clientKey) {
        releasingClientIdentities.releaseClientKeyInLane(lane, releasingClientUser, clientKey);
    }

    /** 仅移除仍指向该订单的用户客户号映射，再删除订单到别名的反向记录。避免删掉已被新订单接管的映射。 */
    public void removeClientOrdersForOrder(long userId, long orderId) {
        assertOwner();
        if (unlinkingClientOrders != null) throw new IllegalStateException("nested client order unlink");
        Long2LongHashMap ordersByClient = clientOrderIndex.get(userId);
        if (ordersByClient != null) {
            unlinkingClientOrders = ordersByClient;
            unlinkingOrderId = orderId;
            try { clientKeysByOrderId.forEach(orderId, unlinkClientOrder); }
            finally { unlinkingClientOrders = null; unlinkingOrderId = 0; }
        }
        clientKeysByOrderId.remove(orderId);
    }

    private void unlinkClientOrder(long clientKey) {
        if (unlinkingClientOrders.get(clientKey) == unlinkingOrderId) unlinkingClientOrders.remove(clientKey);
    }

    /** 核对指定行的用户和客户号文本，防止确定性键碰撞后误用其他身份。 */
    private void requireIdentity(int row, long userId, String name) {
        if (users[row] != userId || !names[row].equals(name))
            throw new IllegalStateException("deterministic client identity collision");
    }

    private int row(long key) { return (int) rowsByKey.get(key) - 1; }

    private void insert(long key, long userId, String name, long count) {
        if (key <= 0 || userId <= 0 || name == null || name.isBlank() || count <= 0)
            throw new IllegalArgumentException("invalid client identity");
        if (rowsByKey.containsKey(key)) throw new IllegalStateException("duplicate imported client identity");
        int row = freeCount == 0 ? nextRow : freeRows[freeCount - 1];
        if (row == keys.length) {
            int capacity = Math.multiplyExact(keys.length, 2);
            keys = Arrays.copyOf(keys, capacity);
            users = Arrays.copyOf(users, capacity);
            references = Arrays.copyOf(references, capacity);
            names = Arrays.copyOf(names, capacity);
            freeRows = Arrays.copyOf(freeRows, capacity);
        }
        rowsByKey.put(key, row + 1L);
        if (freeCount == 0) nextRow++; else freeCount--;
        keys[row] = key;
        users[row] = userId;
        names[row] = name;
        references[row] = count;
        publishedCount++;
    }

    private void releaseRow(long key, int row) {
        if (references[row] <= 0) throw new IllegalStateException("client identity released twice");
        if (--references[row] != 0) return;
        rowsByKey.remove(key);
        keys[row] = users[row] = 0;
        names[row] = null;
        freeRows[freeCount++] = row;
        publishedCount--;
    }

    /** 账户分片拥有的订单到客户身份别名反向索引。 */
    public static final class OrderKeyIndex {
        private final Long2LongHashMap single = new Long2LongHashMap(-1);
        private final Long2ObjectHashMap<LongHashSet> multiple = new Long2ObjectHashMap<>();

        /** 给订单加入别名，重复加入不增加成员。 */
        public void add(long orderId, long key) {
            LongHashSet keys = multiple.get(orderId);
            if (keys != null) {
                keys.add(key);
                return;
            }
            long previous = single.get(orderId);
            if (previous == -1) {
                single.put(orderId, key);
            } else if (previous != key) {
                keys = new LongHashSet();
                keys.add(previous);
                single.remove(orderId);
                keys.add(key);
                multiple.put(orderId, keys);
            }
        }

        /** 只移除指定别名。 */
        public void remove(long orderId, long key) {
            LongHashSet keys = multiple.get(orderId);
            if (keys == null) {
                if (single.get(orderId) == key) single.remove(orderId);
            } else if (keys.remove(key) && keys.size() == 1) {
                single.put(orderId, keys.longIterator().next());
                multiple.remove(orderId);
            }
        }

        /** 把该订单当前的全部别名交给回调，零别名也正常输出。遍历顺序沿用容器，不保证排序。 */
        public void forEach(long orderId, LongProcedure consumer) {
            LongHashSet keys = multiple.get(orderId);
            if (keys != null) keys.forEach(consumer);
            else {
                long key = single.get(orderId);
                if (key != -1) consumer.value(key);
            }
        }

        /** 删除订单的所有反向别名记录。 */
        public void remove(long orderId) {
            single.remove(orderId);
            multiple.remove(orderId);
        }
    }
}
