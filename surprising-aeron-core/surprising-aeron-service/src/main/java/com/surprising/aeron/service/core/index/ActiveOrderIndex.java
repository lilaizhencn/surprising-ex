package com.surprising.aeron.service.core.index;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.InstrumentId;
import com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.OrderFundsReservation.Snapshot;
import com.surprising.aeron.service.order.Order;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.LongHashSet;

/** Owner 持有的活跃订单路由索引，只收录 OPEN 订单的订单号、用户和标的，不持有账户 Lane 的权威订单正文。路由对象和查询暂存均不能跨索引修改长期借用。 */
public final class ActiveOrderIndex {

    public static final int MAX_PAGE_SIZE = 1_024;
    private static final int INITIAL_INDEX_CAPACITY = 256;
    private static final int INITIAL_USER_INDEX_CAPACITY = 64;
    private static final int INITIAL_SYMBOL_INDEX_CAPACITY = 8;
    // 成员表采用后移删除，避免有界订单周转留下墓碑。
    /** 用户到 OPEN 订单号成员集合，Owner 更新。 */
    private final Long2ObjectHashMap<LongHashSet> idsByUser =
            new Long2ObjectHashMap<>(INITIAL_USER_INDEX_CAPACITY, 0.65f, false);
    /** 永久标的文本到 OPEN 订单号成员集合，Owner 更新。 */
    private final Map<String, LongHashSet> idsBySymbol =
            new org.agrona.collections.Object2ObjectHashMap<>(INITIAL_INDEX_CAPACITY, 0.65f, false);
    private final Long2ObjectHashMap<IndexedOrder> ordersById =
            new Long2ObjectHashMap<>(INITIAL_INDEX_CAPACITY, 0.65f, false);
    /** Owner 独占的已清空路由对象池，随索引存活。 */
    private final ArrayDeque<IndexedOrder> recycledOrders = new ArrayDeque<>(INITIAL_INDEX_CAPACITY);
    /** Owner 独占的空成员桶池，桶中无旧订单身份。 */
    private final ArrayDeque<LongHashSet> recycledMemberships = new ArrayDeque<>();
    private static final int MAX_RECYCLED_ORDERS = 4_096;
    private static final int MAX_RECYCLED_MEMBERSHIPS = 64, MAX_RECYCLED_MEMBERSHIP_CAPACITY = 4_096;

    /** Owner 独占且可复用的轻量订单路由，不是订单正文。借出后不能跨下次索引修改持有。 */
    public static final class IndexedOrder {
        private long orderId, userId;
        @Accessors(fluent = true, chain = false)
        @Getter
        private String instrumentId;
        /** 建立只含订单、用户、标的的路由持有者，不保存余额、预留或 Order。 */
        IndexedOrder(long orderId, long userId, String instrumentId) { reset(orderId, userId, instrumentId); }
        /** 原地改写路由，供新增或原对象复用。 */
        void reset(long orderId, long userId, String instrumentId) {
            this.orderId = orderId; this.userId = userId; this.instrumentId = instrumentId;
        }
        /** 回收前清零数字并释放标的文本，不让池中对象保留已结束订单身份。 */
        void clear() { orderId = userId = 0; instrumentId = null; }
        /** 读取该路由当前订单号。 */
        public long orderId() { return orderId; }
        /** 读取该路由当前用户。 */
        public long userId() { return userId; }
    }
    // Owner 独占的有界页筛选暂存，不按全订单深度分配。
    /** Owner 独占、延迟创建的一千零二十五槽页筛选堆，不按全订单深度扩容。 */
    private long[] pageScratch;

    /** 从已提交冷状态重建 OPEN 订单索引。 */
    public ActiveOrderIndex(TradingCoreState state) {
        for (CoreOrderState order : state.orders().values()) {
            if (!isActive(order)) continue;
            CoreInstrument instrument = state.instruments().get(order.instrumentId());
            if (instrument == null) {
                throw new IllegalStateException("instrument is not registered: " + order.instrumentId());
            }
            applyRoute(order.orderId(), order.userId(), order.instrumentId());
        }

    }

    /** 借出当前可变路由对象供撤单定位。无订单为空，调用方不能保留到下一次索引修改之后。 */
    public IndexedOrder activeOrderRoute(long orderId) {
        return ordersById.get(orderId);
    }

    /** 按用户、标的及排他的订单号上界查询降序页。 */
    public Page page(long userId, String instrumentId, long beforeOrderId, int limit) {
        if (beforeOrderId < 0 || limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("invalid active-order page");
        }
        String normalizedSymbol = instrumentId == null || instrumentId.isBlank() ? null : InstrumentId.requireValid(instrumentId);
        LongHashSet source;
        Long2ObjectHashMap<IndexedOrder>.KeyIterator allOrders = null;
        LongHashSet filter = null;
        if (userId == 0 && (normalizedSymbol == null || normalizedSymbol.isBlank())) {
            source = null;
            allOrders = ordersById.keySet().iterator();
        } else if (userId == 0) {
            source = idsBySymbol.get(normalizedSymbol);
        } else if (normalizedSymbol == null || normalizedSymbol.isBlank()) {
            source = idsByUser.get(userId);
        } else {
            LongHashSet userIds = idsByUser.get(userId);
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
        long[] descending = pageScratch;
        int size = 0;
        var iterator = allOrders == null ? source.iterator() : null;
        while (allOrders != null ? allOrders.hasNext() : iterator.hasNext()) {
            long orderId = allOrders != null ? allOrders.nextLong() : iterator.nextValue();
            if (beforeOrderId != 0 && orderId >= beforeOrderId
                    || filter != null && !filter.contains(orderId)) continue;
            if (size < limit + 1) {
                int child = size++;
                while (child > 0) {
                    int parent = (child - 1) >>> 1;
                    if (descending[parent] <= orderId) break;
                    descending[child] = descending[parent];
                    child = parent;
                }
                descending[child] = orderId;
            } else if (orderId > descending[0]) {
                int parent = 0;
                while (parent * 2 + 1 < size) {
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

    /** 生命周期撤单的一页订单描述和下一页游标。 */
    public record LifecycleOrderChunk(List<CancellationOrder> orders, long nextCursorOrderId) {
        /** 根据下一页订单游标是否为零，判断生命周期撤单是否还有后续页。 */
        public boolean more() { return nextCursorOrderId != 0; }
    }

    /** 独立只读的降序订单号页及排他游标。 */
    public record Page(ImmutableLongArrayList orderIds, long nextCursorOrderId) {
        /** 编号列表已不可变，不再次复制或校验排序及页大小。 */
        public Page {
            if (orderIds == null || nextCursorOrderId < 0) throw new IllegalArgumentException("invalid page");
        }
    }

    /** 变更后仍为 OPEN 才保留路由，并用身份表还原标的文本。 */
    public void apply(long orderId, Order after, IdIndex identities) {
        Order current = after != null && after.status() == CoreOrderStatus.OPEN ? after : null;
        applyRoute(orderId, current == null ? 0 : current.userId(),
                current == null ? null : identities.instrumentId(current.symbolId()));
    }

    private void applyRoute(long orderId, long userId, String instrumentId) {
        if (instrumentId == null) {
            IndexedOrder previous = ordersById.remove(orderId);
            if (previous != null) {
                remove(idsByUser, previous.userId(), orderId);
                remove(idsBySymbol, previous.instrumentId(), orderId);
                previous.clear();
                if (recycledOrders.size() < MAX_RECYCLED_ORDERS) recycledOrders.addFirst(previous);
            }
            return;
        }
        IndexedOrder previous = ordersById.get(orderId);
        if (previous == null) {
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

    private static boolean isActive(CoreOrderState order) {
        return order != null && order.status() == CoreOrderStatus.OPEN;
    }

    private void add(IndexedOrder order) {
        ordersById.put(order.orderId(), order);
        add(idsByUser, order.userId(), order.orderId());
        add(idsBySymbol, order.instrumentId(), order.orderId());
    }

    private void remove(Long2ObjectHashMap<LongHashSet> values, long key, long id) {
        LongHashSet ids = values.get(key);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) { values.remove(key); recycleMembership(ids); }
    }

    private <K> void remove(Map<K, LongHashSet> values, K key, long id) {
        LongHashSet ids = values.get(key);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) { values.remove(key); recycleMembership(ids); }
    }

    private <K> void add(Map<K, LongHashSet> values, K key, long id) {
        LongHashSet ids = values.get(key);
        if (ids == null) {
            ids = membership();
            values.put(key, ids);
        }
        ids.add(id);
    }

    private void add(Long2ObjectHashMap<LongHashSet> values, long key, long id) {
        LongHashSet ids = values.get(key);
        if (ids == null) {
            ids = membership();
            values.put(key, ids);
        }
        ids.add(id);
    }

    private LongHashSet membership() {
        LongHashSet ids = recycledMemberships.pollFirst();
        return ids == null ? new LongHashSet(INITIAL_SYMBOL_INDEX_CAPACITY, 0.65f, false) : ids;
    }

    private void recycleMembership(LongHashSet ids) {
        if (ids.capacity() <= MAX_RECYCLED_MEMBERSHIP_CAPACITY
                && recycledMemberships.size() < MAX_RECYCLED_MEMBERSHIPS) recycledMemberships.addFirst(ids);
    }

}
