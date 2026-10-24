package com.surprising.aeron.service.core.index;

import com.surprising.aeron.model.CoreCancelAllAfterKey;
import com.surprising.aeron.model.CoreCancelAllAfterState;
import com.surprising.aeron.model.TradingCoreState;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 延时全撤计时器的 Owner 调度索引。按时间、用户和标的范围定位候选，保留上次应用值供删除旧键；查询正文仍由调用方提供的映射或 lookup 读取，不在此执行撤单。
 */
public final class CancelAllAfterIndex {

    /** 按用户组织的计时器有序候选集合。 */
    private final Long2ObjectOpenHashMap<NavigableSet<TimerKey>> idsByUser = new Long2ObjectOpenHashMap<>();
    /** 全部已索引计时器按时间、用户和范围文本排序的集合，包含非 ACTIVE 状态。 */
    private final NavigableSet<TimerKey> allDue = new TreeSet<>();
    /** 上次应用的计时器值，供更新时移除原时间键，查询正文使用外部 lookup。 */
    private final Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> valuesByKey = new java.util.HashMap<>();

    /**
     * 从冷状态重建所有计时器的全局及按用户调度桶。
     * @param state 索引重建使用的已提交冷状态
     */
    public CancelAllAfterIndex(TradingCoreState state) {
        rebuild(state);
    }

    /**
     * 把调用方计时器映射的 get 作为正文读取函数，复用同一套候选查询。
     * @param userId 计时器所属用户，查询零表示不过滤用户
     * @param symbolScope 计时器标的范围文本，查询空文本表示不过滤范围
     * @param dueAtEpochMillis 到期筛选包含上界，零表示不按执行资格筛选
     * @param limit 希望返回数量，统一夹在一至一万
     * @param values 调用方当前计时器正文映射，按候选业务键读取
     */
    public List<CoreCancelAllAfterKey> query(long userId, String symbolScope, long dueAtEpochMillis, int limit,
                                             Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> values) {
        return query(userId, symbolScope, dueAtEpochMillis, limit, values::get);
    }

    /**
     * 要求 lookup 非空，把数量夹在一到一万，按时间及用户取候选后再读真实值过滤范围。到期时间为零时不限制状态；非零时返回已到期 ACTIVE 或认领超过三十秒的 TRIGGERING，输出键按时间、用户、范围文本排序。
     * @param userId 计时器所属用户，查询零表示不过滤用户
     * @param symbolScope 计时器标的范围文本，查询空文本表示不过滤范围
     * @param dueAtEpochMillis 到期筛选包含上界，零表示不按执行资格筛选
     * @param limit 希望返回数量，统一夹在一至一万
     * @param lookup 按计时器业务键读取当前正文的函数，必须非空
     */
    public List<CoreCancelAllAfterKey> query(long userId, String symbolScope, long dueAtEpochMillis, int limit,
                                             Function<CoreCancelAllAfterKey, CoreCancelAllAfterState> lookup) {
        if (lookup == null) throw new IllegalArgumentException("cancel-all-after lookup is required");
        // 夹在一至一万的本次最大返回数
        int boundedLimit = Math.max(1, Math.min(limit, 10_000));
        // 指定用户的调度集合，用户零则选择全局集合
        NavigableSet<TimerKey> candidates = userId == 0 ? allDue : idsByUser.get(userId);
        if (candidates == null) return List.of();
        // 按到期包含上界截出的有序范围，零时间则不截取
        NavigableSet<TimerKey> dueCandidates = dueAtEpochMillis == 0
                ? candidates : candidates.headSet(new TimerKey(dueAtEpochMillis, Long.MAX_VALUE, "\uffff"), true);
        // 按候选顺序收集的计时器业务键，最后复制成只读列表
        List<CoreCancelAllAfterKey> result = new ArrayList<>(Math.min(boundedLimit, dueCandidates.size()));
        for (TimerKey /* 当前按时间顺序遍历的调度排序键 */ timer : dueCandidates) {
            // 由排序键还原或由计时器状态构造的用户/范围业务键
            CoreCancelAllAfterKey key = new CoreCancelAllAfterKey(timer.userId(), timer.symbolScope());
            // lookup 当前读出的真实计时器值，查询时据它再次过滤
            CoreCancelAllAfterState value = lookup.apply(key);
            if (value == null || (userId != 0 && value.userId() != userId)
                    || (!symbolScope.isEmpty() && !value.symbolScope().equals(symbolScope))
                    || (dueAtEpochMillis != 0 && !value.claimableAt(dueAtEpochMillis))) {
                continue;
            }
            result.add(key);
            if (result.size() == boundedLimit) break;
        }
        return List.copyOf(result);
    }

    /**
     * 先按原键找上次应用值并移除旧时间桶，再加入非空新值；空表示删除，调用方保证传入键和新状态自身 key 对应。
     * @param key 计时器用户/范围业务键，更新时须与新状态自身键一致
     * @param after 计时器变更后的值，空表示删除
     */
    public void apply(CoreCancelAllAfterKey key, CoreCancelAllAfterState after) {
        // 该业务键上次已应用的计时器，用于撤旧时间键
        CoreCancelAllAfterState previous = valuesByKey.remove(key);
        if (previous != null) remove(previous);
        if (after != null) add(after);
    }

    /**
     * 清全部桶及旧值表，再从冷状态计时器逐一登记。
     * @param state 索引重建使用的已提交冷状态
     */
    public void rebuild(TradingCoreState state) {
        idsByUser.clear();
        allDue.clear();
        valuesByKey.clear();
        state.cancelAllAfterTimers().values().forEach(this::add);
    }

    /**
     * 保存计时器当前值作旧键移除依据，把时间、用户和范围组成有序键加入全局及用户桶，不在加入时筛掉非 ACTIVE 状态。
     * @param value 当前要新增或从旧调度桶移除的计时器值
     */
    private void add(CoreCancelAllAfterState value) {
        valuesByKey.put(value.key(), value);
        // 由排序键还原或由计时器状态构造的用户/范围业务键
        TimerKey key = new TimerKey(value.triggerAtEpochMillis(), value.userId(), value.symbolScope());
        allDue.add(key);
        idsByUser.computeIfAbsent(value.userId(), /* 已确定的用户分组键，此回调只创建空排序集合 */ ignored -> new TreeSet<>()).add(key);
    }

    /**
     * 按旧计时器时间与范围移除全局及用户调度键，用户桶空后删除。
     * @param value 当前要新增或从旧调度桶移除的计时器值
     */
    private void remove(CoreCancelAllAfterState value) {
        valuesByKey.remove(value.key());
        // 由排序键还原或由计时器状态构造的用户/范围业务键
        TimerKey key = new TimerKey(value.triggerAtEpochMillis(), value.userId(), value.symbolScope());
        allDue.remove(key);
        // 旧计时器所属用户的原调度桶，移除后可能需要删空桶
        NavigableSet<TimerKey> values = idsByUser.get(value.userId());
        if (values == null) return;
        values.remove(key);
        if (values.isEmpty()) idsByUser.remove(value.userId());
    }

    /**
     * 全撤计时器调度排序键，以毫秒时间、用户及范围文本共同打破并列。
     * @param triggerAtEpochMillis 排序采用的触发毫秒时间
     * @param userId 计时器所属用户，查询零表示不过滤用户
     * @param symbolScope 计时器标的范围文本，查询空文本表示不过滤范围
     */
    private record TimerKey(long triggerAtEpochMillis, long userId, String symbolScope)
            implements Comparable<TimerKey> {
        /**
         * 先按到期时间、再按用户 ID、最后按范围文本自然字典序比较；范围是文本排序，不当作数字标的比较。
         * @param other 需要按同一计时器排序规则比较的另一键
         */
        @Override
        public int compareTo(TimerKey other) {
            // 两计时器到期时间比较结果，非零则直接作为排序结果
            int due = Long.compare(triggerAtEpochMillis, other.triggerAtEpochMillis);
            if (due != 0) return due;
            // 到期相同时两用户 ID 的比较结果
            int user = Long.compare(userId, other.userId);
            return user != 0 ? user : symbolScope.compareTo(other.symbolScope);
        }
    }
}
