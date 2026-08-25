package com.surprising.aeron.service.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

public final class CancelAllAfterIndex {

    private final Map<Long, NavigableSet<TimerKey>> idsByUser = new TreeMap<>();
    private final NavigableSet<TimerKey> allDue = new TreeSet<>();

    public CancelAllAfterIndex(TradingCoreState state) {
        rebuild(state);
    }

    public List<CoreCancelAllAfterKey> query(long userId, String symbolScope, long dueAtEpochMillis, int limit,
                                             Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> values) {
        return query(userId, symbolScope, dueAtEpochMillis, limit, values::get);
    }

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
                    || (dueAtEpochMillis != 0 && (value.status() != com.surprising.aeron.protocol.CoreCancelAllAfterStatus.ACTIVE
                    || value.triggerAtEpochMillis() > dueAtEpochMillis))) {
                continue;
            }
            result.add(key);
            if (result.size() == boundedLimit) break;
        }
        return List.copyOf(result);
    }

    public void update(TradingCoreState before, TradingCoreState after) {
        if (before.cancelAllAfterTimers() == after.cancelAllAfterTimers()) return;
        StateMapSupport.requireDeltaLineage(before.cancelAllAfterTimers(),
                after.cancelAllAfterTimers(), "cancel-all-after timers");
        Set<CoreCancelAllAfterKey> changed = StateMapSupport.changedKeys(after.cancelAllAfterTimers());
        for (CoreCancelAllAfterKey key : changed) {
            if (key == null) continue;
            CoreCancelAllAfterState previous = before.cancelAllAfterTimers().get(key);
            CoreCancelAllAfterState current = after.cancelAllAfterTimers().get(key);
            if (previous != null) remove(previous);
            if (current != null) add(current);
        }
    }

    public void rebuild(TradingCoreState state) {
        idsByUser.clear();
        allDue.clear();
        state.cancelAllAfterTimers().values().forEach(this::add);
    }

    private void add(CoreCancelAllAfterState value) {
        TimerKey key = new TimerKey(value.triggerAtEpochMillis(), value.userId(), value.symbolScope());
        allDue.add(key);
        idsByUser.computeIfAbsent(value.userId(), ignored -> new TreeSet<>()).add(key);
    }

    private void remove(CoreCancelAllAfterState value) {
        TimerKey key = new TimerKey(value.triggerAtEpochMillis(), value.userId(), value.symbolScope());
        allDue.remove(key);
        NavigableSet<TimerKey> values = idsByUser.get(value.userId());
        if (values == null) return;
        values.remove(key);
        if (values.isEmpty()) idsByUser.remove(value.userId());
    }

    private record TimerKey(long triggerAtEpochMillis, long userId, String symbolScope)
            implements Comparable<TimerKey> {
        @Override
        public int compareTo(TimerKey other) {
            int due = Long.compare(triggerAtEpochMillis, other.triggerAtEpochMillis);
            if (due != 0) return due;
            int user = Long.compare(userId, other.userId);
            return user != 0 ? user : symbolScope.compareTo(other.symbolScope);
        }
    }
}
