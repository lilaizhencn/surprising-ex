package com.surprising.aeron.service.account;

import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import java.util.Arrays;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.api.block.procedure.primitive.LongProcedure;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * 一个账户分片的客户订单身份行表、正向客户订单映射及订单到客户别名的反向索引。正数身份键定位用户与客户号文本，引用计数为零时清除文本并复用空行；跨线程监控只读发布数量，不直接读取行表。
 */
public final class ClientOrderIdRegistry {
    /** 拥有这些客户身份的唯一账户分片。 */
    @lombok.NonNull
    private final AccountPartition lane;
    /** 每个用户的客户身份键到订单编号映射，不直接以客户号文本作为热路径键。 */
    final LongObjectHashMap<Long2LongHashMap> clientOrderIndex;
    /** 订单编号到所有客户身份别名的反向索引，便于终态逐项清理。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final OrderKeyIndex clientKeysByOrderId = new OrderKeyIndex();
    /** 正数客户身份键到数组行号加一的映射，零表示不存在。 */
    private final Long2LongHashMap rowsByKey = new Long2LongHashMap(16, 0.65f, 0);
    /** 按行保存的确定性客户身份键，空行置零。 */
    private long[] keys = new long[16];
    /** 按行保存的用户编号，与身份文本一起校验键归属。 */
    private long[] users = new long[16];
    /** 按行保存仍持有此身份的引用次数，零表示可回收行。 */
    private long[] references = new long[16];
    /** 按行保存的客户号文本，最后一个引用释放时清空，避免回收行继续持有文本。 */
    private String[] names = new String[16];
    /** 已释放数组行的下标栈，优先复用已有空行。 */
    private int[] freeRows = new int[16];
    /** 空闲行下标栈的有效长度。 */
    private int freeCount;
    /** 尚未使用过的下一行下标，也是清理和冷导出遍历的上界。 */
    private int nextRow;
    /** 监控可见的存活身份行数，Owner 只观察此计数，不读取原语表和身份行。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private volatile int publishedCount;
    /** 仅终态释放客户身份时借用的字典，遍历结束后清空。 */
    private RuntimeIdentityRegistry releasingClientIdentities;
    /** 当前客户身份释放作用域的用户编号。 */
    private long releasingClientUser;
    /** 常驻的客户别名释放回调，避免每个终态订单新建捕获对象。 */
    private final LongProcedure releaseClientIdentity = this::releaseClientIdentity;
    /** 仅解绑客户号映射时借用的用户映射表。 */
    private Long2LongHashMap unlinkingClientOrders;
    /** 当前解绑作用域要移除的订单编号。 */
    private long unlinkingOrderId;
    /** 常驻的映射解绑回调，删除前核对映射仍指向目标订单。 */
    private final LongProcedure unlinkClientOrder = this::unlinkClientOrder;

    public ClientOrderIdRegistry(AccountPartition lane) {
        this(lane, 16);
    }

    public ClientOrderIdRegistry(AccountPartition lane, int initialEntityCapacity) {
        this.lane = java.util.Objects.requireNonNull(lane, "lane");
        this.clientOrderIndex = new LongObjectHashMap<>(initialEntityCapacity);
    }

    /**
     * 由所属账户分片校验或认领当前线程。
     */
    private void assertOwner() { lane.assertOwner(); }

    /**
     * 已有键先核对用户和文本，retain 为 true 才增加已有引用；新键总以一个引用插入。返回正键表示已存在，负键表示本次新建。
     * @param key 由运行时字典计算的正数客户身份键
     * @param userId 此客户号身份所属的用户编号
     * @param name 用户提供的非空白客户订单号文本
     * @param retain 已有身份是否再持有一个引用；新身份不论此值都以一个引用插入
     */
    public long prepare(long key, long userId, String name, boolean retain) {
        assertOwner();
        // 本次查找、导入或遍历使用的身份数组下标，只有引用非零的行才存活。
        int row = row(key);
        if (row >= 0) {
            requireIdentity(row, userId, name);
            if (retain) references[row] = Math.incrementExact(references[row]);
            return key;
        }
        insert(key, userId, name, 1);
        return -key;
    }

    /**
     * 只在键、用户与客户号文本全部匹配时返回正键；未找到或身份不匹配返回零，不新建行或增加引用。
     * @param key 由运行时字典计算的正数客户身份键
     * @param userId 此客户号身份所属的用户编号
     * @param name 用户提供的非空白客户订单号文本
     */
    public long find(long key, long userId, String name) {
        assertOwner();
        // 本次查找、导入或遍历使用的身份数组下标，只有引用非零的行才存活。
        int row = row(key);
        return row >= 0 && users[row] == userId && names[row].equals(name) ? key : 0;
    }

    /**
     * 要求身份键存在且属于指定用户后返回客户号文本，不允许借其他用户的键读取文本。
     * @param key 由运行时字典计算的正数客户身份键
     * @param userId 此客户号身份所属的用户编号
     */
    public String name(long key, long userId) {
        assertOwner();
        // 本次查找、导入或遍历使用的身份数组下标，只有引用非零的行才存活。
        int row = row(key);
        if (row < 0 || users[row] != userId)
            throw new IllegalArgumentException("unknown runtime client key: " + userId + '/' + key);
        return names[row];
    }

    /**
     * 要求键及完整身份匹配后撤回一个引用；不是无条件删除整行，最后一个引用撤回后才回收行。
     * @param key 由运行时字典计算的正数客户身份键
     * @param userId 此客户号身份所属的用户编号
     * @param name 用户提供的非空白客户订单号文本
     */
    public void rollback(long key, long userId, String name) {
        assertOwner();
        // 本次查找、导入或遍历使用的身份数组下标，只有引用非零的行才存活。
        int row = row(key);
        if (row < 0) throw new IllegalStateException("deterministic client identity collision");
        requireIdentity(row, userId, name);
        releaseRow(key, row);
    }

    /**
     * 键存在且归属用户匹配时释放一个引用；缺失或归属不同直接跳过。
     * @param key 由运行时字典计算的正数客户身份键
     * @param userId 此客户号身份所属的用户编号
     */
    public void release(long key, long userId) {
        assertOwner();
        // 本次查找、导入或遍历使用的身份数组下标，只有引用非零的行才存活。
        int row = row(key);
        if (row >= 0 && users[row] == userId) releaseRow(key, row);
    }

    /**
     * 借用身份字典及用户编号，遍历该订单的全部客户别名并释放引用；本方法不删除订单反向索引。
     * @param identities 用于释放客户订单身份引用的运行时字典
     * @param orderId 本次处理的订单编号
     * @param userId 本次处理的用户编号，路由已由外层选择到本分片
     */
    public void releaseOrderClientIdentities(RuntimeIdentityRegistry identities, long orderId, long userId) {
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

    /**
     * 仅移除仍指向该订单的用户客户号映射，再删除订单到别名的反向记录；避免删掉已被新订单接管的映射。
     * @param userId 本次处理的用户编号，路由已由外层选择到本分片
     * @param orderId 本次处理的订单编号
     */
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

    /**
     * 核对指定行的用户和客户号文本，防止确定性键碰撞后误用其他身份。
     * @param row 索引已经定位的身份数组行下标
     * @param userId 此客户号身份所属的用户编号
     * @param name 用户提供的非空白客户订单号文本
     */
    private void requireIdentity(int row, long userId, String name) {
        if (users[row] != userId || !names[row].equals(name))
            throw new IllegalStateException("deterministic client identity collision");
    }

    /**
     * 把索引保存的行号加一还原成数组下标；索引中的零表示缺失，返回 -1。
     * @param key 由运行时字典计算的正数客户身份键
     */
    private int row(long key) { return (int) rowsByKey.get(key) - 1; }

    /**
     * 校验正数键、用户、引用数及非空客户号，优先取空行，必要时成倍扩数组，最后填行并增加存活数量。
     * @param key 由运行时字典计算的正数客户身份键
     * @param userId 此客户号身份所属的用户编号
     * @param name 用户提供的非空白客户订单号文本
     * @param count 冷复制时要保留的正数引用次数
     */
    private void insert(long key, long userId, String name, long count) {
        if (key <= 0 || userId <= 0 || name == null || name.isBlank() || count <= 0)
            throw new IllegalArgumentException("invalid client identity");
        if (rowsByKey.containsKey(key)) throw new IllegalStateException("duplicate imported client identity");
        // 本次查找、导入或遍历使用的身份数组下标，只有引用非零的行才存活。
        int row = freeCount == 0 ? nextRow : freeRows[freeCount - 1];
        if (row == keys.length) {
            // 现有数组长度精确翻倍后的容量，所有按行数组同步扩容。
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

    /**
     * 递减引用；归零后从键表删除、清除键和用户及文本、把数组行压入空闲栈，并减少发布数量。
     * @param key 由运行时字典计算的正数客户身份键
     * @param row 索引已经定位的身份数组行下标
     */
    private void releaseRow(long key, int row) {
        if (references[row] <= 0) throw new IllegalStateException("client identity released twice");
        if (--references[row] != 0) return;
        rowsByKey.remove(key);
        keys[row] = users[row] = 0;
        names[row] = null;
        freeRows[freeCount++] = row;
        publishedCount--;
    }

    /**
     * 账户分片拥有的订单到客户身份别名反向索引。一个别名直接保存在原语表，出现多个不同别名才创建集合；调用方保证串行访问及键非负，零仍是合法别名。
     */
    public static final class OrderKeyIndex {
        // 客户别名键由调用方保证非负，零仍是有效别名。
        /** 订单到唯一别名的原语映射，-1 表示缺失，因此客户别名零无需特殊排除。 */
        private final Long2LongHashMap single = new Long2LongHashMap(-1);
        /** 仅为具有多个别名的订单保存集合；降为一个别名时释放该集合关联。 */
        private final Long2ObjectHashMap<LongHashSet> multiple = new Long2ObjectHashMap<>();

        /**
         * 给订单加入别名，重复加入不增加成员；第一次直接写单别名表，第二个不同别名到来时提升为集合。
         * @param orderId 需要维护或遍历客户别名的订单编号
         * @param key 由调用方保证非负的客户别名键，零合法，-1 被单别名表保留为缺失值
         */
        public void add(long orderId, long key) {
            // 该订单已有的多别名集合；不存在时查看单别名表。
            LongHashSet keys = multiple.get(orderId);
            if (keys != null) {
                keys.add(key);
                return;
            }
            // 单别名表用 -1 表示缺失，其他非负键含零都合法，一次查找即可判断是否已有别名。
            // 单别名表中的原别名，-1 表示尚未登记此订单。
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

        /**
         * 只移除指定别名；多别名集合剩一个时降回单别名表，不匹配或不存在时不删除其他关联。
         * @param orderId 需要维护或遍历客户别名的订单编号
         * @param key 由调用方保证非负的客户别名键，零合法，-1 被单别名表保留为缺失值
         */
        public void remove(long orderId, long key) {
            // 该订单已有的多别名集合；不存在时查看单别名表。
            LongHashSet keys = multiple.get(orderId);
            if (keys == null) {
                if (single.get(orderId) == key) single.remove(orderId);
            } else if (keys.remove(key) && keys.size() == 1) {
                single.put(orderId, keys.longIterator().next());
                multiple.remove(orderId);
            }
        }

        /**
         * 把该订单当前的全部别名交给回调，零别名也正常输出；遍历顺序沿用容器，不保证排序。
         * @param orderId 需要维护或遍历客户别名的订单编号
         * @param consumer 在当前分片访问范围内逐项处理别名的回调
         */
        public void forEach(long orderId, LongProcedure consumer) {
            // 该订单已有的多别名集合；不存在时查看单别名表。
            LongHashSet keys = multiple.get(orderId);
            if (keys != null) keys.forEach(consumer);
            else {
                // 从单别名表取出的唯一别名，只有 -1 才表示不存在。
                long key = single.get(orderId);
                if (key != -1) consumer.value(key);
            }
        }

        /**
         * 删除订单的所有反向别名记录；身份字典引用和用户客户号映射需由外层另行清理。
         * @param orderId 需要维护或遍历客户别名的订单编号
         */
        public void remove(long orderId) {
            single.remove(orderId);
            multiple.remove(orderId);
        }
    }
}
