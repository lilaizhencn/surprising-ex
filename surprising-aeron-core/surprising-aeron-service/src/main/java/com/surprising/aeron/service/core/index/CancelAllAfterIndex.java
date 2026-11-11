package com.surprising.aeron.service.core.index;

import com.surprising.aeron.model.CoreCancelAllAfterKey;
import com.surprising.aeron.model.CoreCancelAllAfterState;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.Function;

/** 延时全撤计时器的 Owner 调度索引。 */
public final class CancelAllAfterIndex {

    private final Long2ObjectOpenHashMap<NavigableSet<TimerKey>> idsByUser = new Long2ObjectOpenHashMap<>();
    private final NavigableSet<TimerKey> allDue = new TreeSet<>();
    private final Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> valuesByKey = new java.util.HashMap<>();

    /** 从冷状态重建所有计时器的全局及按用户调度桶。 */
    public CancelAllAfterIndex(TradingCoreState state) {
        rebuild(state);
    }

    /** 要求 lookup 非空，把数量夹在一到一万，按时间及用户取候选后再读真实值过滤范围。 */
    public List<CoreCancelAllAfterKey> query(long userId, String symbolScope, long dueAtEpochMillis, int limit,
                                             Function<CoreCancelAllAfterKey, CoreCancelAllAfterState> lookup) {
        if (lookup == null) throw new IllegalArgumentException("cancel-all-after lookup is required");
        int boundedLimit = Math.max(1, Math.min(limit, 10_000));
        NavigableSet<TimerKey> candidates = userId == 0 ? allDue : idsByUser.get(userId);
        if (candidates == null) return List.of();
        NavigableSet<TimerKey> dueCandidates = dueAtEpochMillis == 0
                ? candidates : candidates.headSet(new TimerKey(dueAtEpochMillis, Long.MAX_VALUE, "\uffff"), true);
        List<CoreCancelAllAfterKey> result = new ArrayList<>(Math.min(boundedLimit, dueCandidates.size()));
        for (TimerKey timer : dueCandidates) {
            CoreCancelAllAfterKey key = new CoreCancelAllAfterKey(timer.userId(), timer.symbolScope());
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

    /** 先按原键找上次应用值并移除旧时间桶，再加入非空新值。 */
    public void apply(CoreCancelAllAfterKey key, CoreCancelAllAfterState after) {
        CoreCancelAllAfterState previous = valuesByKey.remove(key);
        if (previous != null) remove(previous);
        if (after != null) add(after);
    }

    /** 清全部桶及旧值表，再从冷状态计时器逐一登记。 */
    public void rebuild(TradingCoreState state) {
        idsByUser.clear();
        allDue.clear();
        valuesByKey.clear();
        state.cancelAllAfterTimers().values().forEach(this::add);
    }

    private void add(CoreCancelAllAfterState value) {
        valuesByKey.put(value.key(), value);
        TimerKey key = new TimerKey(value.triggerAtEpochMillis(), value.userId(), value.symbolScope());
        allDue.add(key);
        idsByUser.computeIfAbsent(value.userId(), ignored -> new TreeSet<>()).add(key);
    }

    private void remove(CoreCancelAllAfterState value) {
        valuesByKey.remove(value.key());
        TimerKey key = new TimerKey(value.triggerAtEpochMillis(), value.userId(), value.symbolScope());
        allDue.remove(key);
        NavigableSet<TimerKey> values = idsByUser.get(value.userId());
        if (values == null) return;
        values.remove(key);
        if (values.isEmpty()) idsByUser.remove(value.userId());
    }

    /** 全撤计时器调度排序键，以毫秒时间、用户及范围文本共同打破并列。 */
    private record TimerKey(long triggerAtEpochMillis, long userId, String symbolScope)
            implements Comparable<TimerKey> {
        /** 先按到期时间、再按用户 ID、最后按范围文本自然字典序比较。 */
        @Override
        public int compareTo(TimerKey other) {
            int due = Long.compare(triggerAtEpochMillis, other.triggerAtEpochMillis);
            if (due != 0) return due;
            int user = Long.compare(userId, other.userId);
            return user != 0 ? user : symbolScope.compareTo(other.symbolScope);
        }
    }
}
