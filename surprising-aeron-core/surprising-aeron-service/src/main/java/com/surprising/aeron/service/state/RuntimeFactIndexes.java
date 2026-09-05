package com.surprising.aeron.service.state;

public final class RuntimeFactIndexes implements RuntimeFactFrame.ChangeConsumer {
    private final PositionUserIndex positionUsers;
    private final OpenInterestIndex openInterest;
    private final TriggerOrderIndex triggers;
    private final AlgoOrderIndex algos;
    private final LiquidationIndex liquidations;
    private final CancelAllAfterIndex timers;
    private final ActiveOrderIndex activeOrders;
    private final AdlPositionIndex adlPositions;
    private ApplyStats lastApplyStats = ApplyStats.EMPTY;
    private RuntimeFactFrame.IdentityView activeIdentities;
    private int positionVisits;
    private int triggerVisits;
    private int algoVisits;
    private int liquidationVisits;
    private int timerVisits;
    private int orderVisits;

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

    public void applyCurrent(TradingRuntimeState runtime, RuntimeFactFrame.IdentityView identities) {
        if (runtime == null || identities == null || activeIdentities != null) {
            throw new IllegalArgumentException("runtime changed indexes are invalid");
        }
        activeIdentities = identities;
        positionVisits = triggerVisits = algoVisits = liquidationVisits = timerVisits = orderVisits = 0;
        try {
            runtime.visitPreparedMatcherIndexes(this);
            runtime.visitChangedIndexes(this);
            lastApplyStats = new ApplyStats(positionVisits, positionVisits, triggerVisits, algoVisits,
                    liquidationVisits, timerVisits, orderVisits, positionVisits);
        } finally {
            activeIdentities = null;
        }
    }

    void preparedOrder(long orderId, CoreOrderState current) {
        activeOrders.applySnapshot(orderId, current);
        orderVisits++;
    }

    void preparedPosition(long positionKey, RuntimePositionIndexValue current) {
        RuntimePositionIndexValue previous = adlPositions.value(positionKey);
        positionUsers.apply(previous, current);
        openInterest.apply(previous, current);
        adlPositions.apply(positionKey, previous, current);
        positionVisits++;
    }

    @Override
    public void order(long orderId, OrderRuntime before, OrderRuntime after) {
        activeOrders.apply(orderId, after, activeIdentities);
        orderVisits++;
    }

    @Override
    public void position(long positionKey, PositionRuntime before, PositionRuntime after) {
        RuntimePositionIndexValue previous = adlPositions.value(positionKey);
        RuntimePositionIndexValue current = after == null
                ? null : RuntimePositionIndexValue.from(after, activeIdentities);
        positionUsers.apply(previous, current);
        openInterest.apply(previous, current);
        adlPositions.apply(positionKey, previous, current);
        positionVisits++;
    }

    @Override
    public void liquidation(long liquidationId, LiquidationRuntime before, LiquidationRuntime after) {
        liquidations.apply(liquidationId, after, activeIdentities);
        liquidationVisits++;
    }

    @Override
    public void algoOrder(long algoOrderId, CoreAlgoOrderState before, CoreAlgoOrderState after) {
        algos.apply(algoOrderId, after);
        algoVisits++;
    }

    @Override
    public void triggerOrder(long triggerOrderId, CoreTriggerOrderState before,
                             CoreTriggerOrderState after) {
        triggers.apply(triggerOrderId, after);
        triggerVisits++;
    }

    @Override
    public void timer(CoreCancelAllAfterKey key, CoreCancelAllAfterState before,
                      CoreCancelAllAfterState after) {
        timers.apply(key, after);
        timerVisits++;
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
        lastApplyStats = ApplyStats.EMPTY;
    }

    ApplyStats lastApplyStats() {
        return lastApplyStats;
    }

    private static <T> T require(T index, String name) {
        if (index == null) throw new IllegalArgumentException(name + " index is required");
        return index;
    }

    record ApplyStats(int positionUserVisits, int openInterestVisits, int triggerVisits, int algoVisits,
                      int liquidationVisits, int timerVisits, int activeOrderVisits,
                      int adlPositionVisits) {
        private static final ApplyStats EMPTY = new ApplyStats(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
