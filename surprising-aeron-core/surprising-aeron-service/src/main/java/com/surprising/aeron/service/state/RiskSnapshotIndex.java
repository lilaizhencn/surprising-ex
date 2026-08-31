package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class RiskSnapshotIndex {

    private final Map<Long, NavigableSet<String>> keysByUser = new TreeMap<>();
    private final NavigableSet<String> allKeys = new TreeSet<>();

    public RiskSnapshotIndex(TradingCoreState state) {
        rebuild(state);
    }

    public Set<String> keys() {
        return Collections.unmodifiableNavigableSet(allKeys);
    }

    public Set<String> keys(long userId) {
        NavigableSet<String> keys = keysByUser.get(userId);
        return keys == null ? Set.of() : Collections.unmodifiableNavigableSet(keys);
    }

    void apply(java.util.List<RuntimeCommitPatch.RiskSnapshotChange> changes,
               RuntimeCommitPatch.IdentityView identities) {
        for (RuntimeCommitPatch.RiskSnapshotChange change : changes) {
            RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(change.riskKey());
            String key = identity.userId() + ":" + identity.positionKey();
            remove(identity.userId(), key);
            if (change.after() != null) add(identity.userId(), key);
        }
    }

    public void rebuild(TradingCoreState state) {
        keysByUser.clear();
        allKeys.clear();
        state.riskState().snapshots().values().forEach(snapshot -> add(snapshot.userId(), snapshot.key()));
    }

    private void add(long userId, String key) {
        keysByUser.computeIfAbsent(userId, ignored -> new TreeSet<>()).add(key);
        allKeys.add(key);
    }

    private void remove(long userId, String key) {
        NavigableSet<String> userKeys = keysByUser.get(userId);
        if (userKeys != null) {
            userKeys.remove(key);
            if (userKeys.isEmpty()) keysByUser.remove(userId);
        }
        allKeys.remove(key);
    }
}
