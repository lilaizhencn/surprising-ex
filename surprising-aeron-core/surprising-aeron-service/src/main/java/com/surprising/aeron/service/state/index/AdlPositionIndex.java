package com.surprising.aeron.service.state.index;

import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.ledger.SystemLedgerLane;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.position.RuntimePositionIndexValue;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

public final class AdlPositionIndex {
    private Thread businessOwner;
    void bindBusinessOwner(Thread thread) { businessOwner = thread; }
    private void assertBusinessOwner() {
        if (businessOwner != null && businessOwner != Thread.currentThread())
            throw new IllegalStateException("position business index requires SystemLedgerLane");
    }

    private final Map<String, LongHashSet> keysByAsset = new HashMap<>();
    private final LongObjectHashMap<IndexedPosition> positions = new LongObjectHashMap<>();

    public AdlPositionIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        rebuild(state, identities);
    }

    private volatile int positionCount;
    public int count() { return positionCount; }

    public Set<PositionKey> positions(String asset) {
        return positions(asset, Integer.MAX_VALUE);
    }

    /** 在物化查询路由之前检查扫描边界，不为超限请求复制整个候选集合。 */
    public Set<PositionKey> positions(String asset, int maximumPositions) {
        assertBusinessOwner();
        LongHashSet values = keysByAsset.get(AssetBalance.normalizeAsset(asset));
        if (values == null || values.isEmpty()) return Set.of();
        if (values.size() > maximumPositions)
            throw new com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException();
        PositionKey[] captured = new PositionKey[values.size()];
        int[] cursor = {0};
        values.forEach(positionKey -> {
            IndexedPosition value = positions.get(positionKey);
            if (value != null) captured[cursor[0]++] = new PositionKey(
                    value.userId(), value.instrumentId(), value.positionSide());
        });
        PositionKey[] ordered = cursor[0] == captured.length
                ? captured : java.util.Arrays.copyOf(captured, cursor[0]);
        java.util.Arrays.sort(ordered);
        LinkedHashSet<PositionKey> result = new LinkedHashSet<>(ordered.length);
        Collections.addAll(result, ordered);
        return Collections.unmodifiableSet(result);
    }

    public IndexedPosition value(long positionKey) {
        assertBusinessOwner();
        return positions.get(positionKey);
    }

    void apply(long positionKey, IndexedPosition previous, RuntimePositionIndexValue current) {
        apply(positionKey, previous, current == null ? 0 : current.userId(),
                current == null ? null : current.instrumentId(), current == null ? null : current.asset(),
                current == null ? null : current.positionSide(), current == null ? 0 : current.signedQuantitySteps());
    }

    /** The index owns its entries; no command-owned after-image is retained after application. */
    void apply(long positionKey, IndexedPosition previous, long userId, String instrumentId, String asset,
            com.surprising.aeron.protocol.CorePositionSide side, long quantity) {
        assertBusinessOwner();
        if (positions.get(positionKey) != previous)
            throw new IllegalStateException("ADL position index differs from shared position index");
        boolean previousActive = previous != null && previous.signedQuantitySteps() != 0;
        boolean currentActive = userId != 0 && quantity != 0;
        boolean membershipChanged = previousActive != currentActive
                || (previousActive && !previous.asset().equals(asset));
        // Read the previous membership before updating this same owned cell.
        if (membershipChanged && previousActive) remove(previous, positionKey);
        if (membershipChanged && currentActive)
            keysByAsset.computeIfAbsent(asset, ignored -> new LongHashSet()).add(positionKey);
        if (userId == 0) { positions.remove(positionKey); positionCount = positions.size(); }
        else {
            IndexedPosition entry = previous;
            if (entry == null) {
                entry = new IndexedPosition();
                positions.put(positionKey, entry); positionCount = positions.size();
            }
            entry.userId = userId; entry.instrumentId = instrumentId; entry.asset = asset;
            entry.positionSide = side; entry.signedQuantitySteps = quantity;
        }
    }

    /** Mutable primitive index cell, exclusively owned by the index's writer. Never handed to a Lane. */
    static final class IndexedPosition {
        private long userId, signedQuantitySteps;
        private String instrumentId, asset;
        private com.surprising.aeron.protocol.CorePositionSide positionSide;
        long userId() { return userId; }
        long signedQuantitySteps() { return signedQuantitySteps; }
        String instrumentId() { return instrumentId; }
        String asset() { return asset; }
        com.surprising.aeron.protocol.CorePositionSide positionSide() { return positionSide; }
    }

    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        assertBusinessOwner();
        keysByAsset.clear();
        positions.clear(); positionCount = 0;
        state.users().values().forEach(user -> user.positions().forEach((key, position) -> {
            long positionKey = identities.positionKey(user.userId(), key);
            apply(positionKey, null, user.userId(), position.instrumentId(), position.marginAsset(),
                    position.positionSide(), position.signedQuantitySteps());
        }));
    }

    private void remove(IndexedPosition position, long positionKey) {
        if (position.signedQuantitySteps() == 0) return;
        LongHashSet values = keysByAsset.get(position.asset());
        if (values == null) return;
        values.remove(positionKey);
        // Asset identity remains registered. Keep its empty membership storage for reopen churn;
        // queries already treat an empty set as absent, and rebuild clears the cache.
    }

    public record PositionKey(long userId, String instrumentId, com.surprising.aeron.protocol.CorePositionSide positionSide)
            implements Comparable<PositionKey> {
        @Override
        public int compareTo(PositionKey other) {
            int user = Long.compare(userId, other.userId);
            if (user != 0) return user;
            int symbolCompare = instrumentId.compareTo(other.instrumentId);
            return symbolCompare != 0 ? symbolCompare
                    : Integer.compare(positionSide.ordinal(), other.positionSide.ordinal());
        }
    }
}
