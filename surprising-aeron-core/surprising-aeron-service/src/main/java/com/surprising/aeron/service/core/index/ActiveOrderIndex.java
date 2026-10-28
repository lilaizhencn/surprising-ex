package com.surprising.aeron.service.core.index;

import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.order.state.OrderRuntime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.LongHashSet;

/**
 * Owner 持有的活跃订单路由索引，只收录 OPEN 订单的订单号、用户和标的，不持有账户 Lane 的权威订单正文。按用户和标的维护成员集合，供撤单路由及查询使用；路由对象和查询暂存均不能跨索引修改长期借用。
 */
public final class ActiveOrderIndex {

    /** 单页允许的最多订单数，一千零二十四。 */
    public static final int MAX_PAGE_SIZE = 1_024;
    /** 按标的及订单号总表的初始容量二百五十六，后续仍可增长。 */
    private static final int INITIAL_INDEX_CAPACITY = 256;
    /** 按用户分组表初始容量六十四。 */
    private static final int INITIAL_USER_INDEX_CAPACITY = 64;
    /** 新成员桶初始容量八，便于短生命周期桶复用。 */
    private static final int INITIAL_SYMBOL_INDEX_CAPACITY = 8;
    // 成员表采用后移删除，避免有界订单周转留下墓碑；
    // 每次查询使用独立迭代器，嵌套读取不覆盖彼此游标。
    /** 用户到 OPEN 订单号成员集合，Owner 更新。 */
    private final Long2ObjectHashMap<LongHashSet> idsByUser =
            new Long2ObjectHashMap<>(INITIAL_USER_INDEX_CAPACITY, 0.65f, false);
    /** 永久标的文本到 OPEN 订单号成员集合，Owner 更新。 */
    private final Map<String, LongHashSet> idsBySymbol =
            new org.agrona.collections.Object2ObjectHashMap<>(INITIAL_INDEX_CAPACITY, 0.65f, false);
    /** 订单号到可变轻量路由，不保留账户 OrderRuntime。 */
    private final Long2ObjectHashMap<IndexedOrder> ordersById =
            new Long2ObjectHashMap<>(INITIAL_INDEX_CAPACITY, 0.65f, false);
    /** Owner 独占的已清空路由对象池，随索引存活。 */
    private final ArrayDeque<IndexedOrder> recycledOrders = new ArrayDeque<>(INITIAL_INDEX_CAPACITY);
    /** Owner 独占的空成员桶池，桶中无旧订单身份。 */
    private final ArrayDeque<LongHashSet> recycledMemberships = new ArrayDeque<>();
    /** 最多保留四千零九十六个空路由对象。 */
    private static final int MAX_RECYCLED_ORDERS = 4_096;
    /**
     * MAX_RECYCLED_MEMBERSHIPS：最多保留六十四个空成员桶。
     * MAX_RECYCLED_MEMBERSHIP_CAPACITY：可回收的单个成员桶容量上限四千零九十六。
     */
    private static final int MAX_RECYCLED_MEMBERSHIPS = 64, MAX_RECYCLED_MEMBERSHIP_CAPACITY = 4_096;

    /**
     * Owner 独占且可复用的轻量订单路由，不是订单正文；借出后不能跨下次索引修改持有。
     */
    public static final class IndexedOrder {
        /**
         * orderId：路由当前订单号，回收后为零。
         * userId：路由当前用户，回收后为零。
         */
        private long orderId, userId;
        /**  路由当前永久数字标的文本，回收后为空。 读取该路由当前永久数字标的文本。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private String instrumentId;
        /**
         * 建立只含订单、用户、标的的路由持有者，不保存余额、预留或 OrderRuntime。
         * @param orderId 需要定位或更新的订单号
         * @param userId 用户 ID；page 中零表示不按用户筛选
         * @param instrumentId 永久数字标的文本；page 中空白或空值表示不按标的筛选
         */
        IndexedOrder(long orderId, long userId, String instrumentId) { reset(orderId, userId, instrumentId); }
        /**
         * 原地改写路由，供新增或原对象复用；调用方负责同步成员桶。
         * @param orderId 需要定位或更新的订单号
         * @param userId 用户 ID；page 中零表示不按用户筛选
         * @param instrumentId 永久数字标的文本；page 中空白或空值表示不按标的筛选
         */
        void reset(long orderId, long userId, String instrumentId) {
            this.orderId = orderId; this.userId = userId; this.instrumentId = instrumentId;
        }
        /**
         * 回收前清零数字并释放标的文本，不让池中对象保留已结束订单身份。
         */
        void clear() { orderId = userId = 0; instrumentId = null; }
        /**
         * 读取该路由当前订单号。
         */
        public long orderId() { return orderId; }
        /**
         * 读取该路由当前用户。
         */
        public long userId() { return userId; }
    }
    // Owner 独占的有界页筛选暂存，不按全订单深度分配。
    /** Owner 独占、延迟创建的一千零二十五槽页筛选堆，不按全订单深度扩容。 */
    private long[] pageScratch;

    /**
     * 从已提交冷状态重建 OPEN 订单索引。
     * @param state 只在冷边界用于重建的已提交状态
     */
    public ActiveOrderIndex(TradingCoreState state) {
        for (CoreOrderState /* 冷状态中当前需要判断并重建的订单 */ order : state.orders().values()) {
            if (!isActive(order)) continue;
            // 该冷订单的已注册合约，缺失则重建拒绝
            CoreInstrument instrument = state.instruments().get(order.instrumentId());
            if (instrument == null) {
                throw new IllegalStateException("instrument is not registered: " + order.instrumentId());
            }
            applyRoute(order.orderId(), order.userId(), order.instrumentId());
        }

    }

    /**
     * 借出当前可变路由对象供撤单定位；无订单为空，调用方不能保留到下一次索引修改之后。
     * @param orderId 需要定位或更新的订单号
     */
    public IndexedOrder activeOrderRoute(long orderId) {
        return ordersById.get(orderId);
    }

    /**
     * 按用户、标的及排他的订单号上界查询降序页。扫描整个选中候选桶，用至多 limit 加一的小顶堆保留最大 ID，再输出一页；只有确有后续结果才返回下一页游标。暂存大小有界不代表扫描条数有界。
     * @param userId 用户 ID；page 中零表示不按用户筛选
     * @param instrumentId 永久数字标的文本；page 中空白或空值表示不按标的筛选
     * @param beforeOrderId 排他的订单号上界，零表示不设上界，负值拒绝
     * @param limit 本页最多返回数，范围一到 MAX_PAGE_SIZE
     */
    public Page page(long userId, String instrumentId, long beforeOrderId, int limit) {
        if (beforeOrderId < 0 || limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("invalid active-order page");
        }
        // 本次页查询验证后的标的文本，不筛选标的时为空
        String normalizedSymbol = instrumentId == null || instrumentId.isBlank() ? null : OrderReservation.requireInstrumentId(instrumentId);
        // 选中扫描的较小成员桶；分页无筛选时为空并遍历全订单表
        LongHashSet source;
        // 无用户和标的条件时使用的全订单键迭代器
        Long2ObjectHashMap<IndexedOrder>.KeyIterator allOrders = null;
        // 另一个成员桶，用包含判断完成交集，未筛另一维时为空
        LongHashSet filter = null;
        if (userId == 0 && (normalizedSymbol == null || normalizedSymbol.isBlank())) {
            source = null;
            allOrders = ordersById.keySet().iterator();
        } else if (userId == 0) {
            source = idsBySymbol.get(normalizedSymbol);
        } else if (normalizedSymbol == null || normalizedSymbol.isBlank()) {
            source = idsByUser.get(userId);
        } else {
            // 用户维度的原成员桶
            LongHashSet userIds = idsByUser.get(userId);
            // 标的维度的原成员桶
            LongHashSet symbolIds = idsBySymbol.get(normalizedSymbol);
            if (userIds == null || symbolIds == null) return new Page(ImmutableLongArrayList.empty(), 0);
            if (userIds.size() <= symbolIds.size()) {
                source = userIds;
                filter = symbolIds;
            } else {
                source = symbolIds;
                filter = userIds;
            }
        }
        if (allOrders == null && (source == null || source.isEmpty())) return new Page(ImmutableLongArrayList.empty(), 0);
        if (pageScratch == null) pageScratch = new long[MAX_PAGE_SIZE + 1];
        // 借用的分页暂存数组，筛选期间实际按小顶堆维护，输出前升序排序
        long[] descending = pageScratch;
        // 交集实际命中数或分页小顶堆当前有效长度
        int size = 0;
        // 本次调用独占的成员桶游标
        var iterator = allOrders == null ? source.iterator() : null;
        while (allOrders != null ? allOrders.hasNext() : iterator.hasNext()) {
            // 当前候选或当前页中要输出的订单号
            long orderId = allOrders != null ? allOrders.nextLong() : iterator.nextValue();
            if (beforeOrderId != 0 && orderId >= beforeOrderId
                    || filter != null && !filter.contains(orderId)) continue;
            if (size < limit + 1) {
                // 堆上浮或下沉过程中当前子节点下标
                int child = size++;
                while (child > 0) {
                    // 堆调整过程中当前父节点下标
                    int parent = (child - 1) >>> 1;
                    if (descending[parent] <= orderId) break;
                    descending[child] = descending[parent];
                    child = parent;
                }
                descending[child] = orderId;
            } else if (orderId > descending[0]) {
                // 堆调整过程中当前父节点下标
                int parent = 0;
                while (parent * 2 + 1 < size) {
                    // 堆上浮或下沉过程中当前子节点下标
                    int child = parent * 2 + 1;
                    if (child + 1 < size && descending[child + 1] < descending[child]) child++;
                    if (descending[child] >= orderId) break;
                    descending[parent] = descending[child];
                    parent = child;
                }
                descending[parent] = orderId;
            }
        }
        java.util.Arrays.sort(descending, 0, size);
        int count = Math.min(limit, size);
        long[] result = new long[count];
        for (int index = 0; index < count; index++) result[index] = descending[size - 1 - index];
        long nextCursor = size > limit ? result[count - 1] : 0;
        return new Page(ImmutableLongArrayList.takeOwnership(result), nextCursor);
    }

    /** 从同一 Owner 索引页直接取撤单路由，生命周期处理与撮合控制共用这一选择顺序。 */
    public LifecycleOrderChunk lifecycleOrders(long userId, String instrumentId, long cursorOrderId, int maxOrders) {
        Page page = page(userId, instrumentId, cursorOrderId, maxOrders);
        ArrayList<CancellationOrder> selected = new ArrayList<>(page.orderIds().size());
        for (int index = 0; index < page.orderIds().size(); index++) {
            long orderId = page.orderIds().valueAt(index);
            var route = activeOrderRoute(orderId);
            if (route == null) throw new IllegalStateException("settlement active order index differs from runtime");
            selected.add(new CancellationOrder(route.orderId(), route.userId(), route.instrumentId()));
        }
        return new LifecycleOrderChunk(selected, page.nextCursorOrderId());
    }

    /**
     * 生命周期撤单的一页订单描述和下一页游标；本 record 沿用传入列表，不自行复制或校验。
     * @param orders 本页按顺序借用的生命周期撤单描述
     * @param nextCursorOrderId 下一页继续扫描的订单游标，零表示结束
     */
    public record LifecycleOrderChunk(List<CancellationOrder> orders, long nextCursorOrderId) {
        /**
         * 根据下一页订单游标是否为零，判断生命周期撤单是否还有后续页。
         */
        public boolean more() { return nextCursorOrderId != 0; }
    }

    /**
     * 独立只读的降序订单号页及排他游标；游标为零表示当前扫描没有后续结果。
     * @param orderIds 按降序排列的本页独立结果列表
     * @param nextCursorOrderId 有后续结果时为本页末尾 ID，结束为零
     */
    public record Page(ImmutableLongArrayList orderIds, long nextCursorOrderId) {
        /**
         * 检查结果列表非空、游标非负；编号列表已不可变，不再次复制或校验排序及页大小。
         */
        public Page {
            if (orderIds == null || nextCursorOrderId < 0) throw new IllegalArgumentException("invalid page");
        }
    }

    /**
     * 变更后仍为 OPEN 才保留路由，并用身份表还原标的文本；其他状态及空值均删除，调用方保证订单号与状态身份对应。
     * @param orderId 需要定位或更新的订单号
     * @param after 账户变更后的订单正文，空值或非 OPEN 状态删除路由
     * @param identities 内部编号到永久标的文本的身份表；冷构造器不读取此参数
     */
    public void apply(long orderId, OrderRuntime after, RuntimeIdentityRegistry identities) {
        // 变更后仍为 OPEN 的订单正文，其他状态转换为空以删除路由
        OrderRuntime current = after != null && after.status() == CoreOrderStatus.OPEN ? after : null;
        applyRoute(orderId, current == null ? 0 : current.userId(),
                current == null ? null : identities.instrumentId(current.symbolId()));
    }

    /**
     * 移除时一次取走旧路由、撤旧成员并清空回收；新增借用池对象；已存在时只迁移发生变化的用户或标的桶，再原地重置路由。
     * @param orderId 需要定位或更新的订单号
     * @param userId 用户 ID；page 中零表示不按用户筛选
     * @param instrumentId 永久数字标的文本；page 中空白或空值表示不按标的筛选
     */
    private void applyRoute(long orderId, long userId, String instrumentId) {
        if (instrumentId == null) {
            // 删除已经返回旧路由，直接用它清成员桶，无须再次查询同一订单。
            // 当前订单号的原可变路由，供撤旧桶、原地更新或清空回收
            IndexedOrder previous = ordersById.remove(orderId);
            if (previous != null) {
                remove(idsByUser, previous.userId(), orderId);
                remove(idsBySymbol, previous.instrumentId(), orderId);
                previous.clear();
                if (recycledOrders.size() < MAX_RECYCLED_ORDERS) recycledOrders.addFirst(previous);
            }
            return;
        }
        // 当前订单号的原可变路由，供撤旧桶、原地更新或清空回收
        IndexedOrder previous = ordersById.get(orderId);
        if (previous == null) {
            // 查到或从池借出的轻量订单路由
            IndexedOrder entry = recycledOrders.pollFirst();
            if (entry == null) entry = new IndexedOrder(orderId, userId, instrumentId);
            else entry.reset(orderId, userId, instrumentId);
            add(entry);
            return;
        }
        if (previous.userId() != userId) {
            remove(idsByUser, previous.userId(), orderId);
            add(idsByUser, userId, orderId);
        }
        if (!previous.instrumentId.equals(instrumentId)) {
            remove(idsBySymbol, previous.instrumentId, orderId);
            add(idsBySymbol, instrumentId, orderId);
        }
        previous.reset(orderId, userId, instrumentId);
    }

    /**
     * 只把非空且状态为 OPEN 的冷订单视为活跃。
     * @param order 待加入索引的路由，或需要判断活跃状态的冷订单
     */
    private static boolean isActive(CoreOrderState order) {
        return order != null && order.status() == CoreOrderStatus.OPEN;
    }

    /**
     * 把新路由登记到按订单、用户、标的的三张表。
     * @param order 待加入索引的路由，或需要判断活跃状态的冷订单
     */
    private void add(IndexedOrder order) {
        ordersById.put(order.orderId(), order);
        add(idsByUser, order.userId(), order.orderId());
        add(idsBySymbol, order.instrumentId(), order.orderId());
    }

    /**
     * 从 primitive 用户桶移除订单号，空桶删键并尝试回收。
     * @param values 需要更新或复制的成员表、成员集合
     * @param key 成员分组键，primitive 重载为用户，对象键重载为标的
     * @param id 需要加入或移除的订单号
     */
    private void remove(Long2ObjectHashMap<LongHashSet> values, long key, long id) {
        // 当前选中、复制或待复用的 primitive 订单号成员集合
        LongHashSet ids = values.get(key);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) { values.remove(key); recycleMembership(ids); }
    }

    /**
     * 从对象键成员桶移除订单号，空桶删键并尝试回收。
     * @param <K> 成员表分组键的类型，不改变订单号 primitive 表示
     * @param values 需要更新或复制的成员表、成员集合
     * @param key 成员分组键，primitive 重载为用户，对象键重载为标的
     * @param id 需要加入或移除的订单号
     */
    private <K> void remove(Map<K, LongHashSet> values, K key, long id) {
        // 当前选中、复制或待复用的 primitive 订单号成员集合
        LongHashSet ids = values.get(key);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) { values.remove(key); recycleMembership(ids); }
    }

    /**
     * 向对象键成员桶加入订单号，不存在时借用或新建空桶。
     * @param <K> 成员表分组键的类型，不改变订单号 primitive 表示
     * @param values 需要更新或复制的成员表、成员集合
     * @param key 成员分组键，primitive 重载为用户，对象键重载为标的
     * @param id 需要加入或移除的订单号
     */
    private <K> void add(Map<K, LongHashSet> values, K key, long id) {
        // 当前选中、复制或待复用的 primitive 订单号成员集合
        LongHashSet ids = values.get(key);
        if (ids == null) {
            ids = membership();
            values.put(key, ids);
        }
        ids.add(id);
    }

    /**
     * 向 primitive 用户桶加入订单号，不存在时借用或新建空桶。
     * @param values 需要更新或复制的成员表、成员集合
     * @param key 成员分组键，primitive 重载为用户，对象键重载为标的
     * @param id 需要加入或移除的订单号
     */
    private void add(Long2ObjectHashMap<LongHashSet> values, long key, long id) {
        // 当前选中、复制或待复用的 primitive 订单号成员集合
        LongHashSet ids = values.get(key);
        if (ids == null) {
            ids = membership();
            values.put(key, ids);
        }
        ids.add(id);
    }

    /**
     * 优先取已清空回收桶，没有则以小容量新建；独立迭代器支持嵌套读取。
     */
    private LongHashSet membership() {
        // 当前选中、复制或待复用的 primitive 订单号成员集合
        LongHashSet ids = recycledMemberships.pollFirst();
        return ids == null ? new LongHashSet(INITIAL_SYMBOL_INDEX_CAPACITY, 0.65f, false) : ids;
    }

    /**
     * 调用方保证集合已空，本方法仅在集合容量及池中数量均受限时留作复用，不在此再次清空。
     * @param ids 由调用方移空、准备回收的 primitive 成员桶
     */
    private void recycleMembership(LongHashSet ids) {
        if (ids.capacity() <= MAX_RECYCLED_MEMBERSHIP_CAPACITY
                && recycledMemberships.size() < MAX_RECYCLED_MEMBERSHIPS) recycledMemberships.addFirst(ids);
    }

}
