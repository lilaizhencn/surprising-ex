package com.surprising.aeron.service.core.index;

import com.surprising.aeron.model.CoreAlgoOrderState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import com.surprising.product.api.utils.InstrumentIds;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * Owner 持有的算法单身份与调度索引。路由只保留用户、标的和调度时间；数量、状态和子单正文留在账户 Lane。终态仍可保留身份，但不会进入到期候选集。
 */
public final class AlgoOrderIndex {

    /** 调度时间优先、同时间算法 ID 升序的固定比较规则。 */
    private static final Comparator<AlgoDueKey> DUE_ORDER = Comparator
            .comparingLong(AlgoDueKey::nextSliceAtEpochMillis)
            .thenComparingLong(AlgoDueKey::algoOrderId);

    /** 用户到待调度键集合，采用统一 DUE_ORDER 排序。 */
    private final Long2ObjectOpenHashMap<NavigableSet<AlgoDueKey>> idsByUser = new Long2ObjectOpenHashMap<>();
    /** 整数标的到待调度键集合，采用统一 DUE_ORDER 排序。 */
    private final Int2ObjectOpenHashMap<NavigableSet<AlgoDueKey>> idsBySymbol = new Int2ObjectOpenHashMap<>();
    /** 所有非终态算法的统一调度键集。 */
    private final NavigableSet<AlgoDueKey> allDue = new TreeSet<>(DUE_ORDER);
    /** 每个已应用算法业务 ID 的轻量路由，供过滤及移除旧键。 */
    private final Long2ObjectOpenHashMap<AlgoRoute> valuesById = new Long2ObjectOpenHashMap<>();

    /**
     * 不可变算法单调度和身份路由，不含数量、完整状态或子单清单。
     * @param algoOrderId 算法单业务 ID；增量更新时须与新状态一致
     * @param userId 算法单所属用户，查询为零表示不按用户筛选
     * @param instrumentId 标的身份，文本入口使用规范数字 ID，路由及内部键保存整数
     * @param nextSliceAtEpochMillis 路由计算后的有效调度毫秒时间，可能受持续期截止提前限制
     */
    public record AlgoRoute(long algoOrderId, long userId, int instrumentId,
                            long nextSliceAtEpochMillis) { }

    /**
     * 从已提交冷状态重建算法单路由、身份和待调度桶。
     * @param state 索引重建使用的本产品线已提交冷状态
     */
    public AlgoOrderIndex(TradingCoreState state) {
        state.algoOrders().values().forEach(this::add);
    }

    /**
     * 借出已索引的不可变算法路由，不去读账户 Lane 的完整算法状态。
     * @param algoOrderId 算法单业务 ID；增量更新时须与新状态一致
     */
    public AlgoRoute value(long algoOrderId) { return valuesById.get(algoOrderId); }

    /**
     * 遍历现有轻量路由交给回调，供导出初始化使用，不复制账户正文或创建路由快照；遍历期间由调用方维持索引稳定。
     * @param consumer 立即消费轻量路由的回调，使用期间保持索引稳定
     */
    public void forEachRoute(java.util.function.Consumer<AlgoRoute> consumer) {
        valuesById.values().forEach(consumer);
    }

    /**
     * 把数量限制夹在一到一万，按用户和标的选较小可用桶，再按时间及另一维路由过滤；零到期时间表示不设截止。结果按调度时间再算法 ID 升序，以 primitive 不可变列表返回。
     * @param userId 算法单所属用户，查询为零表示不按用户筛选
     * @param instrumentId 标的身份，文本入口使用规范数字 ID，路由及内部键保存整数
     * @param dueAtEpochMillis 包含的到期时间上界，为零表示不筛时间
     * @param limit 希望返回数量，方法夹到一至一万
     */
    public List<Long> query(long userId, String instrumentId, long dueAtEpochMillis, int limit) {
        // 夹在一到一万的本次最大返回数
        int boundedLimit = Math.max(1, Math.min(limit, 10_000));
        // 筛选标的的规范整数身份，不筛标的时为零
        int normalizedSymbol = instrumentId == null || instrumentId.isEmpty()
                ? 0 : InstrumentIds.parse(instrumentId);
        // 选中的用户、标的或全局调度桶
        NavigableSet<AlgoDueKey> candidates = candidateSet(userId, normalizedSymbol);
        if (candidates == null) return List.of();
        // 按截止时间截取的包含上界范围，零截止则用全桶
        NavigableSet<AlgoDueKey> dueCandidates = dueAtEpochMillis == 0
                ? candidates
                : candidates.headSet(new AlgoDueKey(dueAtEpochMillis, Long.MAX_VALUE), true);
        // 本次 primitive long 结果构造器，按调度顺序冻结交出
        var result = new ImmutableLongArrayList.Builder(Math.min(boundedLimit, candidates.size()));
        for (AlgoDueKey /* 当前候选或待加入/移除的有效调度时间与算法 ID 组合 */ key : dueCandidates) {
            // 当前候选在反向表中的算法路由，用它再核对另一维筛选
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

    /**
     * 按传入 ID 移除旧路由及旧调度键，再加入非空新状态；空表示删除，调用方保证参数 ID 与新状态身份相同。
     * @param algoOrderId 算法单业务 ID；增量更新时须与新状态一致
     * @param after 该 ID 变更后的完整算法状态，空表示删除
     */
    public void apply(long algoOrderId, CoreAlgoOrderState after) {
        // 本次更新前的旧路由，用来移除旧调度键
        AlgoRoute previous = valuesById.remove(algoOrderId);
        if (previous != null) remove(previous);
        if (after != null) add(after);
    }

    /**
     * 无筛选时使用全局桶；指定维度缺桶时返回空，否则选较小的候选桶，不构造交集。
     * @param userId 算法单所属用户，查询为零表示不按用户筛选
     * @param instrumentId 标的身份，文本入口使用规范数字 ID，路由及内部键保存整数
     */
    private NavigableSet<AlgoDueKey> candidateSet(long userId, int instrumentId) {
        if (userId == 0 && instrumentId == 0) return allDue;
        // 指定用户的原调度桶，没有用户条件或桶不存在时为空
        NavigableSet<AlgoDueKey> userSet = userId == 0 ? null : idsByUser.get(userId);
        // 指定标的的原调度桶，没有标的条件或桶不存在时为空
        NavigableSet<AlgoDueKey> symbolSet = instrumentId == 0 ? null : idsBySymbol.get(instrumentId);
        if ((userId != 0 && userSet == null) || (instrumentId != 0 && symbolSet == null)) return null;
        if (userSet == null) return symbolSet;
        if (symbolSet == null) return userSet;
        // 查询再用轻量路由过滤另一个条件，无须先生成用户和标的集合的交集。
        return userSet.size() <= symbolSet.size() ? userSet : symbolSet;
    }

    /**
     * 下一切片时间非正时先用开始时间；早期状态再取它与持续期截止的较小值，截止溢出饱和到 long 最大值。所有状态保留路由，只有非终态加入调度集。
     * @param value 需要加入索引的完整算法状态或需要删除的原轻量路由
     */
    private void add(CoreAlgoOrderState value) {
        // 本次从下一切片或开始时间算出的有效调度点，早期状态再受截止限制
        long due = value.nextSliceAtEpochMillis() > 0 ? value.nextSliceAtEpochMillis() : value.startAtEpochMillis();
        if (value.statusCode() < 2) {
            // 算法计划持续秒数，用于计算最晚结束时间
            long duration = value.durationSeconds();
            // 开始时间加持续期毫秒，超过 long 上界时饱和为最大值
            long deadline = duration > (Long.MAX_VALUE - value.startAtEpochMillis()) / 1000
                    ? Long.MAX_VALUE : value.startAtEpochMillis() + duration * 1000;
            due = Math.min(due, deadline);
        }
        // 本次新生成的算法轻量身份与有效调度路由
        AlgoRoute route = new AlgoRoute(value.algoOrderId(), value.userId(), InstrumentIds.parse(value.instrumentId()),
                due);
        valuesById.put(value.algoOrderId(), route);
        if (value.terminal()) return;
        // 当前候选或待加入/移除的有效调度时间与算法 ID 组合
        AlgoDueKey key = new AlgoDueKey(due, value.algoOrderId());
        allDue.add(key);
        idsByUser.computeIfAbsent(value.userId(), /* 已经确定的用户或标的分组键，回调只负责创建空调度桶 */ ignored -> new TreeSet<>(DUE_ORDER)).add(key);
        idsBySymbol.computeIfAbsent(route.instrumentId(), /* 已经确定的用户或标的分组键，回调只负责创建空调度桶 */ ignored -> new TreeSet<>(DUE_ORDER)).add(key);
    }

    /**
     * 按旧路由删除全局、用户、标的调度键；路由已由 apply 移除。
     * @param value 需要加入索引的完整算法状态或需要删除的原轻量路由
     */
    private void remove(AlgoRoute value) {
        // 当前候选或待加入/移除的有效调度时间与算法 ID 组合
        AlgoDueKey key = new AlgoDueKey(value.nextSliceAtEpochMillis(), value.algoOrderId());
        allDue.remove(key);
        remove(idsByUser, value.userId(), key);
        remove(idsBySymbol, value.instrumentId(), key);
    }

    /**
     * 从 long 用户键对应调度桶移除指定键，空桶从表中删掉。
     * @param values 用户或标的到有序调度桶的原索引表
     * @param key 需要移除调度键的用户 long 键或标的 int 键
     * @param dueKey 该桶中需要移除的原调度时间与算法 ID 组合
     */
    private static void remove(Long2ObjectOpenHashMap<NavigableSet<AlgoDueKey>> values, long key, AlgoDueKey dueKey) {
        // 该用户或标的的原调度桶，移除后为空则删桶
        NavigableSet<AlgoDueKey> ids = values.get(key);
        if (ids == null) return;
        ids.remove(dueKey);
        if (ids.isEmpty()) values.remove(key);
    }

    /**
     * 从 int 标的键对应调度桶移除指定键，空桶从表中删掉。
     * @param values 用户或标的到有序调度桶的原索引表
     * @param key 需要移除调度键的用户 long 键或标的 int 键
     * @param dueKey 该桶中需要移除的原调度时间与算法 ID 组合
     */
    private static void remove(Int2ObjectOpenHashMap<NavigableSet<AlgoDueKey>> values, int key, AlgoDueKey dueKey) {
        // 该用户或标的的原调度桶，移除后为空则删桶
        NavigableSet<AlgoDueKey> ids = values.get(key);
        if (ids == null) return;
        ids.remove(dueKey);
        if (ids.isEmpty()) values.remove(key);
    }

    /**
     * 调度有序键，先比较有效调度时间，再比较算法业务 ID。
     * @param nextSliceAtEpochMillis 路由计算后的有效调度毫秒时间，可能受持续期截止提前限制
     * @param algoOrderId 算法单业务 ID；增量更新时须与新状态一致
     */
    private record AlgoDueKey(long nextSliceAtEpochMillis, long algoOrderId) {
    }

}
