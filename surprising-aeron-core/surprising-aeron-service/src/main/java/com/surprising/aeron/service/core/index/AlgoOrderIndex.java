package com.surprising.aeron.service.core.index;

import com.surprising.aeron.model.CoreAlgoOrderState;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import com.surprising.product.api.utils.InstrumentIds;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/** Owner 持有的算法单身份与调度索引。 */
public final class AlgoOrderIndex {

    private static final Comparator<AlgoDueKey> DUE_ORDER = Comparator
            .comparingLong(AlgoDueKey::nextSliceAtEpochMillis)
            .thenComparingLong(AlgoDueKey::algoOrderId);

    private final Long2ObjectOpenHashMap<NavigableSet<AlgoDueKey>> idsByUser = new Long2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<NavigableSet<AlgoDueKey>> idsBySymbol = new Int2ObjectOpenHashMap<>();
    private final NavigableSet<AlgoDueKey> allDue = new TreeSet<>(DUE_ORDER);
    private final Long2ObjectOpenHashMap<AlgoRoute> valuesById = new Long2ObjectOpenHashMap<>();

    /** 不可变算法单调度和身份路由，不含数量、完整状态或子单清单。 */
    public record AlgoRoute(long algoOrderId, long userId, int instrumentId,
                            long nextSliceAtEpochMillis) { }

    /** 从已提交冷状态重建算法单路由、身份和待调度桶。 */
    public AlgoOrderIndex(TradingCoreState state) {
        state.algoOrders().values().forEach(this::add);
    }

    /** 借出已索引的不可变算法路由，不去读账户 Lane 的完整算法状态。 */
    public AlgoRoute value(long algoOrderId) { return valuesById.get(algoOrderId); }

    /** 遍历现有轻量路由交给回调，供导出初始化使用，不复制账户正文或创建路由快照。 */
    public void forEachRoute(java.util.function.Consumer<AlgoRoute> consumer) {
        valuesById.values().forEach(consumer);
    }

    /** 把数量限制夹在一到一万，按用户和标的选较小可用桶，再按时间及另一维路由过滤。结果按调度时间再算法 ID 升序，以 primitive 不可变列表返回。 */
    public List<Long> query(long userId, String instrumentId, long dueAtEpochMillis, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 10_000));
        int normalizedSymbol = instrumentId == null || instrumentId.isEmpty()
                ? 0 : InstrumentIds.parse(instrumentId);
        NavigableSet<AlgoDueKey> candidates = candidateSet(userId, normalizedSymbol);
        if (candidates == null) return List.of();
        NavigableSet<AlgoDueKey> dueCandidates = dueAtEpochMillis == 0
                ? candidates
                : candidates.headSet(new AlgoDueKey(dueAtEpochMillis, Long.MAX_VALUE), true);
        var result = new ImmutableLongArrayList.Builder(Math.min(boundedLimit, candidates.size()));
        for (AlgoDueKey key : dueCandidates) {
            AlgoRoute value = valuesById.get(key.algoOrderId());
            if (value == null || (userId != 0 && value.userId() != userId)
                    || (normalizedSymbol != 0 && value.instrumentId() != normalizedSymbol)
                    || (dueAtEpochMillis != 0 && (value.nextSliceAtEpochMillis() == 0
                    || value.nextSliceAtEpochMillis() > dueAtEpochMillis))) {
                continue;
            }
            result.add(key.algoOrderId());
            if (result.size() == boundedLimit) break;
        }
        return result.freeze();
    }

    /** 按传入 ID 移除旧路由及旧调度键，再加入非空新状态。 */
    public void apply(long algoOrderId, CoreAlgoOrderState after) {
        AlgoRoute previous = valuesById.remove(algoOrderId);
        if (previous != null) remove(previous);
        if (after != null) add(after);
    }

    /** 指定维度缺桶时返回空，否则选较小的候选桶，不构造交集。 */
    private NavigableSet<AlgoDueKey> candidateSet(long userId, int instrumentId) {
        if (userId == 0 && instrumentId == 0) return allDue;
        NavigableSet<AlgoDueKey> userSet = userId == 0 ? null : idsByUser.get(userId);
        NavigableSet<AlgoDueKey> symbolSet = instrumentId == 0 ? null : idsBySymbol.get(instrumentId);
        if ((userId != 0 && userSet == null) || (instrumentId != 0 && symbolSet == null)) return null;
        if (userSet == null) return symbolSet;
        if (symbolSet == null) return userSet;
        return userSet.size() <= symbolSet.size() ? userSet : symbolSet;
    }

    /** 早期状态再取它与持续期截止的较小值，截止溢出饱和到 long 最大值。 */
    private void add(CoreAlgoOrderState value) {
        long due = value.nextSliceAtEpochMillis() > 0 ? value.nextSliceAtEpochMillis() : value.startAtEpochMillis();
        if (value.statusCode() < 2) {
            long duration = value.durationSeconds();
            long deadline = duration > (Long.MAX_VALUE - value.startAtEpochMillis()) / 1000
                    ? Long.MAX_VALUE : value.startAtEpochMillis() + duration * 1000;
            due = Math.min(due, deadline);
        }
        AlgoRoute route = new AlgoRoute(value.algoOrderId(), value.userId(), InstrumentIds.parse(value.instrumentId()),
                due);
        valuesById.put(value.algoOrderId(), route);
        if (value.terminal()) return;
        AlgoDueKey key = new AlgoDueKey(due, value.algoOrderId());
        allDue.add(key);
        idsByUser.computeIfAbsent(value.userId(), ignored -> new TreeSet<>(DUE_ORDER)).add(key);
        idsBySymbol.computeIfAbsent(route.instrumentId(), ignored -> new TreeSet<>(DUE_ORDER)).add(key);
    }

    private void remove(AlgoRoute value) {
        AlgoDueKey key = new AlgoDueKey(value.nextSliceAtEpochMillis(), value.algoOrderId());
        allDue.remove(key);
        remove(idsByUser, value.userId(), key);
        remove(idsBySymbol, value.instrumentId(), key);
    }

    private static void remove(Long2ObjectOpenHashMap<NavigableSet<AlgoDueKey>> values, long key, AlgoDueKey dueKey) {
        NavigableSet<AlgoDueKey> ids = values.get(key);
        if (ids == null) return;
        ids.remove(dueKey);
        if (ids.isEmpty()) values.remove(key);
    }

    private static void remove(Int2ObjectOpenHashMap<NavigableSet<AlgoDueKey>> values, int key, AlgoDueKey dueKey) {
        NavigableSet<AlgoDueKey> ids = values.get(key);
        if (ids == null) return;
        ids.remove(dueKey);
        if (ids.isEmpty()) values.remove(key);
    }

    /** 调度有序键，先比较有效调度时间，再比较算法业务 ID。 */
    private record AlgoDueKey(long nextSliceAtEpochMillis, long algoOrderId) {
    }

}
