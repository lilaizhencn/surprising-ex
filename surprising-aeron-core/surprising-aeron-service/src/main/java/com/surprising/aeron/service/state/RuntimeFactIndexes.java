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
    private final RiskSnapshotIndex riskSnapshots;
    private ApplyStats lastApplyStats = ApplyStats.EMPTY;
    private RuntimeFactFrame.IdentityView activeIdentities;
    private int positionVisits;
    private int triggerVisits;
    private int algoVisits;
    private int liquidationVisits;
    private int timerVisits;
    private int orderVisits;
    private int riskVisits;

    public RuntimeFactIndexes(PositionUserIndex positionUsers, OpenInterestIndex openInterest,
                                TriggerOrderIndex triggers, AlgoOrderIndex algos,
                                LiquidationIndex liquidations, CancelAllAfterIndex timers,
                                ActiveOrderIndex activeOrders, AdlPositionIndex adlPositions,
                                RiskSnapshotIndex riskSnapshots) {
        this.positionUsers = require(positionUsers, "position-user");
        this.openInterest = require(openInterest, "open-interest");
        this.triggers = require(triggers, "trigger");
        this.algos = require(algos, "algo");
        this.liquidations = require(liquidations, "liquidation");
        this.timers = require(timers, "timer");
        this.activeOrders = require(activeOrders, "active-order");
        this.adlPositions = require(adlPositions, "ADL-position");
        this.riskSnapshots = require(riskSnapshots, "risk-snapshot");
    }

    public void apply(RuntimeFactFrame patch) {
        if (patch == null) throw new IllegalArgumentException("runtime commit patch is required");
        RuntimeFactFrame.IdentityView identities = patch.identities();
        int positionUserVisits = 0;
        int openInterestVisits = 0;
        int triggerVisits = 0;
        int algoVisits = 0;
        int liquidationVisits = 0;
        int timerVisits = 0;
        int activeOrderVisits = 0;
        int adlPositionVisits = 0;
        int riskSnapshotVisits = 0;
        for (RuntimeFactFrame.AccountLaneOwnerGroup group : patch.accountLaneGroups()) {
            if (!group.positions().isEmpty()) {
                positionUsers.apply(group.positions(), identities);
                positionUserVisits++;
                openInterest.apply(group.positions(), identities);
                openInterestVisits++;
            }
            if (!group.triggerOrders().isEmpty()) {
                triggers.apply(group.triggerOrders());
                triggerVisits++;
            }
            if (!group.algoOrders().isEmpty()) {
                algos.apply(group.algoOrders());
                algoVisits++;
            }
            if (!group.liquidations().isEmpty()) {
                liquidations.apply(group.liquidations(), identities);
                liquidationVisits++;
            }
            if (!group.timers().isEmpty()) {
                timers.apply(group.timers());
                timerVisits++;
            }
            if (!group.orders().isEmpty()) {
                activeOrders.apply(group.orders(), identities);
                activeOrderVisits++;
            }
            if (!group.positions().isEmpty()) {
                adlPositions.apply(group.positions(), identities);
                adlPositionVisits++;
            }
            if (!group.riskSnapshots().isEmpty()) {
                riskSnapshots.apply(group.riskSnapshots(), identities);
                riskSnapshotVisits++;
            }
        }
        lastApplyStats = new ApplyStats(positionUserVisits, openInterestVisits, triggerVisits, algoVisits,
                liquidationVisits, timerVisits, activeOrderVisits, adlPositionVisits, riskSnapshotVisits);
    }

    public void apply(RuntimeFactFrame.Builder builder, RuntimeFactFrame.IdentityView identities) {
        if (builder == null || identities == null || activeIdentities != null) {
            throw new IllegalArgumentException("runtime change frame is invalid");
        }
        activeIdentities = identities;
        positionVisits = triggerVisits = algoVisits = liquidationVisits = timerVisits = orderVisits = riskVisits = 0;
        try {
            builder.visitChangedIndexes(this);
            lastApplyStats = new ApplyStats(positionVisits, positionVisits, triggerVisits, algoVisits,
                    liquidationVisits, timerVisits, orderVisits, positionVisits, riskVisits);
        } finally {
            activeIdentities = null;
        }
    }

    public void applyCurrent(TradingRuntimeState runtime, RuntimeFactFrame.IdentityView identities) {
        if (runtime == null || identities == null || activeIdentities != null) {
            throw new IllegalArgumentException("runtime changed indexes are invalid");
        }
        activeIdentities = identities;
        positionVisits = triggerVisits = algoVisits = liquidationVisits = timerVisits = orderVisits = riskVisits = 0;
        try {
            runtime.visitChangedIndexes(this);
            lastApplyStats = new ApplyStats(positionVisits, positionVisits, triggerVisits, algoVisits,
                    liquidationVisits, timerVisits, orderVisits, positionVisits, riskVisits);
        } finally {
            activeIdentities = null;
        }
    }

    @Override
    public void order(long orderId, OrderRuntime before, OrderRuntime after) {
        activeOrders.apply(orderId, after, activeIdentities);
        orderVisits++;
    }

    @Override
    public void position(long positionKey, PositionRuntime before, PositionRuntime after) {
        positionUsers.apply(positionKey, after, activeIdentities);
        openInterest.apply(positionKey, after, activeIdentities);
        adlPositions.apply(positionKey, after, activeIdentities);
        positionVisits++;
    }

    @Override
    public void liquidation(long liquidationId, LiquidationRuntime before, LiquidationRuntime after) {
        liquidations.apply(liquidationId, after, activeIdentities);
        liquidationVisits++;
    }

    @Override
    public void riskSnapshot(long riskKey, RiskSnapshotRuntime before, RiskSnapshotRuntime after) {
        riskSnapshots.apply(riskKey, after, activeIdentities);
        riskVisits++;
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
        riskSnapshots.rebuild(state);
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
                      int adlPositionVisits, int riskSnapshotVisits) {
        private static final ApplyStats EMPTY = new ApplyStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
