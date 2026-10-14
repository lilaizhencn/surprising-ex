package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.index.AlgoOrderIndex;
import com.surprising.aeron.service.state.index.CancelAllAfterIndex;
import com.surprising.aeron.service.state.index.LiquidationIndex;
import com.surprising.aeron.service.state.index.TriggerOrderIndex;

import com.surprising.aeron.service.state.model.CoreAlgoOrderState;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterKey;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterState;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;

public final class RuntimeFactIndexes implements TradingRuntimeState.ChangedIndexConsumer {
    private final PositionUserIndex positionUsers;
    private final OpenInterestIndex openInterest;
    private final TriggerOrderIndex triggers;
    private final AlgoOrderIndex algos;
    private final LiquidationIndex liquidations;
    private final CancelAllAfterIndex timers;
    private final ActiveOrderIndex activeOrders;
    private final AdlPositionIndex adlPositions;
    private RuntimeIdentityRegistry activeIdentities;

    public RuntimeFactIndexes(PositionUserIndex positionUsers, OpenInterestIndex openInterest,
                                TriggerOrderIndex triggers, AlgoOrderIndex algos,
                                LiquidationIndex liquidations, CancelAllAfterIndex timers,
                                ActiveOrderIndex activeOrders, AdlPositionIndex adlPositions) {
        this.positionUsers = require(positionUsers, "position-user");
        this.openInterest = require(openInterest, "open-interest");
        this.triggers = require(triggers, "trigger");
        this.algos = require(algos, "algo");
        this.liquidations = require(liquidations, "liquidation");
        this.timers = require(timers, "timer");
        this.activeOrders = require(activeOrders, "active-order");
        this.adlPositions = require(adlPositions, "ADL-position");
    }

    public com.surprising.aeron.service.state.index.LiquidationIndex.LiquidationRoute liquidationRoute(long id) {
        return liquidations.route(id);
    }
    public boolean hasUnresolvedLiquidation(String instrumentId) { return liquidations.hasUnresolved(instrumentId); }

    public com.surprising.aeron.service.state.index.AlgoOrderIndex.AlgoRoute algoRoute(long id) { return algos.value(id); }

    public TriggerOrderIndex.TriggerRoute triggerRoute(long id) { return triggers.route(id); }

    void bindBusinessOwner(Thread thread) {
        positionUsers.bindBusinessOwner(thread); openInterest.bindBusinessOwner(thread); adlPositions.bindBusinessOwner(thread);
    }

    long admissionOpenInterest(String instrumentId) { return openInterest.openInterestSteps(instrumentId); }

    /** Frozen Lane output is read on Ledger before the settlement completion is released. */
    void applySettlementPositions(MatcherSettlementChanges changes, long laneMask,
            RuntimeIdentityRegistry identities) {
        while (laneMask != 0) {
            int laneId = Long.numberOfTrailingZeros(laneMask);
            laneMask &= laneMask - 1;
            applyPositions(changes.laneDeltas[laneId].positions, identities, null);
        }
    }

    void applyPositions(RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue> positions, RuntimeIdentityRegistry identities,
            PositionUndo undo) {
        for (int slot = 0; slot < positions.size(); slot++) {
            long key = positions.keyAt(slot);
            PositionRuntime value = positions.valueAt(slot);
            RuntimePositionIndexValue prepared = positions.preparedAt(slot);
            boolean hasPrepared = positions.hasPreparedAt(slot);
            long userId = hasPrepared ? prepared == null ? 0 : prepared.userId() : value == null ? 0 : value.userId();
            String symbol = hasPrepared ? prepared == null ? null : prepared.instrumentId()
                    : value == null ? null : identities.instrumentId(value.symbolId());
            String asset = hasPrepared ? prepared == null ? null : prepared.asset()
                    : value == null ? null : identities.asset(value.assetId());
            var side = hasPrepared ? prepared == null ? null : prepared.positionSide()
                    : value == null ? null : value.positionSide();
            long quantity = hasPrepared ? prepared == null ? 0 : prepared.signedQuantitySteps()
                    : value == null ? 0 : value.signedQuantitySteps();
            var previous = adlPositions.value(key);
            if (previous == null && userId == 0 || previous != null && previous.userId() == userId
                    && previous.signedQuantitySteps() == quantity
                    && java.util.Objects.equals(previous.instrumentId(), symbol)
                    && java.util.Objects.equals(previous.asset(), asset) && previous.positionSide() == side) continue;
            if (undo != null) undo.capture(key, previous);
            applyPosition(key, previous, userId, symbol, asset, side, quantity);
        }
    }

    void applyControlPositions(OwnerIndexedChanges<PositionRuntime, RuntimePositionIndexValue> positions,
            RuntimeIdentityRegistry identities, PositionUndo undo) {
        applyPositions(positions.directBuffer(), identities, undo);
        long remaining = positions.retainedLaneMask();
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            applyPositions(positions.laneBuffer(lane), identities, undo);
        }
    }

    /** Minimal before-values for an unpublished control scope, never mutable index cells. */
    static final class PositionUndo {
        private int size;
        private long[] keys = new long[8], users = new long[8], quantities = new long[8];
        private String[] symbols = new String[8], assets = new String[8];
        private com.surprising.aeron.protocol.CorePositionSide[] sides = new com.surprising.aeron.protocol.CorePositionSide[8];

        void capture(long key, AdlPositionIndex.IndexedPosition previous) {
            if (size == keys.length) {
                int capacity = Math.multiplyExact(size, 2);
                keys = java.util.Arrays.copyOf(keys, capacity); users = java.util.Arrays.copyOf(users, capacity);
                quantities = java.util.Arrays.copyOf(quantities, capacity);
                symbols = java.util.Arrays.copyOf(symbols, capacity); assets = java.util.Arrays.copyOf(assets, capacity);
                sides = java.util.Arrays.copyOf(sides, capacity);
            }
            keys[size] = key;
            users[size] = previous == null ? 0 : previous.userId();
            quantities[size] = previous == null ? 0 : previous.signedQuantitySteps();
            symbols[size] = previous == null ? null : previous.instrumentId();
            assets[size] = previous == null ? null : previous.asset();
            sides[size] = previous == null ? null : previous.positionSide();
            size++;
        }

        void rollback(RuntimeFactIndexes indexes) {
            for (int slot = size - 1; slot >= 0; slot--)
                indexes.applyPosition(keys[slot], users[slot], symbols[slot], assets[slot], sides[slot], quantities[slot]);
            clear();
        }

        void clear() {
            java.util.Arrays.fill(symbols, 0, size, null); java.util.Arrays.fill(assets, 0, size, null);
            java.util.Arrays.fill(sides, 0, size, null); size = 0;
        }
    }

    public void applyCurrent(TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (runtime == null || identities == null || activeIdentities != null) {
            throw new IllegalArgumentException("runtime changed indexes are invalid");
        }
        activeIdentities = identities;
        try {
            runtime.visitChangedIndexes(this);
        } finally {
            activeIdentities = null;
        }
    }

    void preparedPosition(long positionKey, RuntimePositionIndexValue current) {
        applyPosition(positionKey, current == null ? 0 : current.userId(), current == null ? null : current.instrumentId(),
                current == null ? null : current.asset(), current == null ? null : current.positionSide(),
                current == null ? 0 : current.signedQuantitySteps());
    }

    private void applyPosition(long key, long userId, String symbol, String asset,
            com.surprising.aeron.protocol.CorePositionSide side, long quantity) {
        applyPosition(key, adlPositions.value(key), userId, symbol, asset, side, quantity);
    }

    private void applyPosition(long key, AdlPositionIndex.IndexedPosition previous,
            long userId, String symbol, String asset, com.surprising.aeron.protocol.CorePositionSide side, long quantity) {
        positionUsers.applyRoutes(previous == null ? 0 : previous.userId(), previous == null ? null : previous.instrumentId(), userId, symbol);
        openInterest.applyQuantities(previous == null ? null : previous.instrumentId(),
                previous == null ? 0 : previous.signedQuantitySteps(), symbol, quantity);
        adlPositions.apply(key, previous, userId, symbol, asset, side, quantity);
    }

    @Override
    public void order(long orderId, OrderRuntime before, OrderRuntime after) {
        activeOrders.apply(orderId, after, activeIdentities);
    }

    @Override
    public void position(long positionKey, PositionRuntime before, PositionRuntime after) {
        applyPosition(positionKey, after == null ? 0 : after.userId(),
                after == null ? null : activeIdentities.instrumentId(after.symbolId()),
                after == null ? null : activeIdentities.asset(after.assetId()),
                after == null ? null : after.positionSide(), after == null ? 0 : after.signedQuantitySteps());
    }

    @Override
    public void liquidation(long liquidationId, LiquidationRuntime before, LiquidationRuntime after) {
        liquidations.apply(liquidationId, after, activeIdentities);
    }

    @Override
    public void algoOrder(long algoOrderId, CoreAlgoOrderState before, CoreAlgoOrderState after) {
        algos.apply(algoOrderId, after);
    }

    @Override
    public void triggerOrder(long triggerOrderId, CoreTriggerOrderState before,
                             CoreTriggerOrderState after) {
        triggers.apply(triggerOrderId, after);
    }

    @Override
    public void timer(CoreCancelAllAfterKey key, CoreCancelAllAfterState before,
                      CoreCancelAllAfterState after) {
        timers.apply(key, after);
    }

    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        positionUsers.rebuild(state, identities);
        openInterest.rebuild(state, identities);
        triggers.rebuild(state);
        algos.rebuild(state);
        liquidations.rebuild(state);
        timers.rebuild(state);
        activeOrders.rebuild(state, identities);
        adlPositions.rebuild(state, identities);
    }

    private static <T> T require(T index, String name) {
        if (index == null) throw new IllegalArgumentException(name + " index is required");
        return index;
    }

}
